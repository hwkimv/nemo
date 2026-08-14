package com.nemo.backend.domain.auth.service;

import com.nemo.backend.domain.auth.dto.RefreshRequest;
import com.nemo.backend.domain.auth.dto.RefreshResponse;
import com.nemo.backend.domain.auth.token.RefreshToken;
import com.nemo.backend.domain.auth.token.RefreshTokenRepository;
import com.nemo.backend.domain.map.util.NaverApiClient;
import com.nemo.backend.domain.user.entity.User;
import com.nemo.backend.domain.user.repository.UserRepository;
import com.nemo.backend.global.exception.ApiException;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import software.amazon.awssdk.services.s3.S3Client;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P0-3 회귀 테스트.
 *
 * AuthService.refresh()는 이름만 보면 "조회"지만 실제로는 DB에 쓴다.
 *  - 만료된 Refresh Token row 삭제
 *  - 만료 임박 시 token/expiry 회전 후 저장
 *
 * 예전에는 이 메서드에 @Transactional(readOnly = true)가 붙어 있었다.
 * readOnly 트랜잭션은 Hibernate FlushMode를 MANUAL로 두기 때문에 위 변경이 flush되지 않고 조용히 사라진다.
 * 이 테스트는 "요청이 끝난 뒤 DB에 실제로 반영됐는가"를 트랜잭션 밖에서 다시 읽어 확인한다.
 *
 * 주의: 이 테스트는 dev 프로필(H2)에서 돈다. flush 유실은 Hibernate 레벨 동작이라 H2로도 재현되지만,
 * 운영 PostgreSQL 동작까지 증명한 것은 아니다. (문서의 "H2 통과를 PostgreSQL 증거로 확대하지 않는다" 원칙)
 */
@SpringBootTest
@ActiveProfiles("dev")
@DisplayName("refresh()의 삭제·회전이 실제로 커밋되는지")
class AuthServiceRefreshTransactionTest {

    @MockitoBean
    private S3Client s3Client;
    @MockitoBean
    private NaverApiClient naverApiClient;

    @Autowired
    private AuthService authService;
    @Autowired
    private RefreshTokenRepository refreshTokenRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private EntityManager em;

    @Value("${jwt.refresh-exp-days:14}")
    private long refreshExpDays;

    private User user;

    @BeforeEach
    void createUser() {
        refreshTokenRepository.deleteAll();
        User u = new User();
        u.setEmail("refresh-test-" + UUID.randomUUID() + "@nemo.test");
        u.setPassword("{noop}irrelevant");
        u.setNickname("refresh-test");
        u.setProvider("local");
        user = userRepository.save(u);
    }

    private RefreshToken persistToken(String value, LocalDateTime expiry) {
        RefreshToken token = new RefreshToken();
        token.setUserId(user.getId());
        token.setToken(value);
        token.setExpiry(expiry);
        RefreshToken saved = refreshTokenRepository.saveAndFlush(token);
        em.clear(); // 영속성 컨텍스트 캐시가 아니라 DB에서 다시 읽도록
        return saved;
    }

    @Test
    @DisplayName("만료된 토큰으로 refresh하면 해당 row가 DB에서 실제로 삭제된다")
    void expiredTokenRowIsActuallyDeleted() {
        String expired = UUID.randomUUID().toString();
        persistToken(expired, LocalDateTime.now().minusDays(1));

        assertThatThrownBy(() -> authService.refresh(new RefreshRequest(expired)))
                .isInstanceOf(ApiException.class);

        em.clear();
        assertThat(refreshTokenRepository.findByToken(expired))
                .as("만료 토큰은 refresh 시 삭제되어야 한다 (readOnly 트랜잭션이면 삭제가 유실됨)")
                .isEmpty();
    }

    @Test
    @DisplayName("만료 임박 토큰은 회전되고, 응답의 새 토큰과 DB 저장값이 일치한다")
    void rotatedTokenIsCommittedAndMatchesResponse() {
        String nearExpiry = UUID.randomUUID().toString();
        // 남은 시간을 전체의 1/3 미만으로 만들어 회전 조건을 만족시킨다.
        long remainingHours = Math.max(1, (refreshExpDays * 24) / 4);
        persistToken(nearExpiry, LocalDateTime.now().plusHours(remainingHours));

        RefreshResponse response = authService.refresh(new RefreshRequest(nearExpiry));

        assertThat(response.refreshToken())
                .as("만료 임박이면 새 refreshToken이 응답에 포함되어야 한다")
                .isNotBlank();

        em.clear();
        // 옛 토큰은 더 이상 통하지 않아야 하고,
        assertThat(refreshTokenRepository.findByToken(nearExpiry)).isEmpty();
        // 응답으로 준 새 토큰이 DB에 커밋되어 있어야 한다.
        assertThat(refreshTokenRepository.findByToken(response.refreshToken()))
                .as("회전된 토큰이 커밋되지 않으면 사용자는 다음 요청에서 로그아웃된다")
                .isPresent()
                .get()
                .satisfies(stored -> {
                    assertThat(stored.getUserId()).isEqualTo(user.getId());
                    assertThat(stored.getExpiry()).isAfter(LocalDateTime.now().plusDays(refreshExpDays - 1));
                });
    }

    @Test
    @DisplayName("아직 여유 있는 토큰은 회전하지 않고 불필요한 write도 하지 않는다")
    void healthyTokenIsNotRotated() {
        String healthy = UUID.randomUUID().toString();
        LocalDateTime expiry = LocalDateTime.now().plusDays(refreshExpDays);
        persistToken(healthy, expiry);

        RefreshResponse response = authService.refresh(new RefreshRequest(healthy));

        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.refreshToken())
                .as("회전하지 않은 경우 명세상 refreshToken은 응답에 포함하지 않는다")
                .isNull();

        em.clear();
        assertThat(refreshTokenRepository.findByToken(healthy))
                .isPresent()
                .get()
                .satisfies(stored -> assertThat(stored.getToken()).isEqualTo(healthy));
    }
}
