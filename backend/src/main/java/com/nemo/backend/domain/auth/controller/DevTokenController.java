// backend/src/main/java/com/nemo/backend/domain/auth/controller/DevTokenController.java
package com.nemo.backend.domain.auth.controller;

import com.nemo.backend.domain.auth.jwt.JwtUtil;
import com.nemo.backend.domain.auth.token.RefreshToken;
import com.nemo.backend.domain.auth.token.RefreshTokenRepository;
import com.nemo.backend.domain.user.entity.User;
import com.nemo.backend.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

@Profile({"local", "dev", "benchmark"}) // 로컬/개발/성능 측정 환경에서만 활성화
@RestController
@RequestMapping("/api/auth/dev")
@RequiredArgsConstructor
public class DevTokenController {

    // --------------------------------------------------------
    // ⭐ 의존성 주입
    // --------------------------------------------------------
    private final JwtUtil jwtUtil;                       // AccessToken 발급/검증용
    private final UserRepository userRepository;         // 유저 조회/생성
    private final RefreshTokenRepository refreshTokenRepository; // RefreshToken upsert
    private final PasswordEncoder passwordEncoder;       // 🔥 dev 유저 생성 시 더미 비밀번호 암호화용

    /**
     * 🔧 개발용 토큰 생성 엔드포인트
     *
     * 예)
     *  - POST /api/auth/dev/seed?userId=4
     *  - POST /api/auth/dev/seed?email=hwkimv@test.com
     *
     * 동작 규칙
     *  1) userId가 주어지면 → 해당 유저를 먼저 찾고
     *  2) 없으면 email로 유저를 찾는다.
     *  3) 그래도 없으면 새 유저를 생성한다.
     *     - 이때 DB 제약조건(password NOT NULL)을 맞추기 위해
     *       "dev 전용 더미 비밀번호"를 하나 넣어준다.
     *  4) RefreshToken은 userId 기준으로 upsert(있으면 갱신, 없으면 새로 생성)
     *  5) JwtUtil을 이용해 AccessToken 발급
     *  6) userId / email / accessToken / refreshToken 을 한 번에 응답
     */
    @PostMapping("/seed")
    @Transactional
    public ResponseEntity<SeedResponse> seed(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false, defaultValue = "demo4@nemo.app") String email
    ) {

        // ----------------------------------------------------
        // 1) 사용자 찾기 (userId 우선, 없으면 email 기준)
        // ----------------------------------------------------
        User user = null;

        if (userId != null) {
            user = userRepository.findById(userId).orElse(null);
        }
        if (user == null) {
            user = userRepository.findByEmail(email).orElse(null);
        }

        // ----------------------------------------------------
        // 2) 유저가 없으면 새로 생성 (dev 전용 계정)
        //    - password NOT NULL 제약을 맞추기 위해 더미 비밀번호 저장
        // ----------------------------------------------------
        if (user == null) {
            user = new User();
            user.setEmail(email);
            user.setNickname(email.split("@")[0]);           // 예: "demo4"
            user.setProvider("local");                       // 다른 곳과 provider 값 일관성 유지
            user.setSocialId(null);
            user.setProfileImageUrl("");

            // 🔥 H2/MariaDB에서 password 컬럼이 NOT NULL 이므로
            //    dev 계정용 더미 비밀번호를 하나 넣어준다.
            //    (실제 로그인에 쓰지 않을 계정)
            String dummyPassword = "dev-password";
            user.setPassword(passwordEncoder.encode(dummyPassword));

            user = userRepository.save(user);
        }

        // ----------------------------------------------------
        // 3) RefreshToken upsert (userId 기준으로 1개 유지)
        // ----------------------------------------------------
        RefreshToken refresh = refreshTokenRepository.findFirstByUserId(user.getId())
                .orElseGet(RefreshToken::new);

        refresh.setUserId(user.getId());
        refresh.setToken("dev-refresh-token-" + user.getId());          // 개발용 고정 토큰 패턴
        refresh.setExpiry(LocalDateTime.now().plusDays(7));             // 7일짜리 dev 토큰
        refreshTokenRepository.save(refresh);

        // ----------------------------------------------------
        // 4) AccessToken 발급 (JwtUtil 기준으로 통일)
        // ----------------------------------------------------
        String access = jwtUtil.createAccessToken(user.getId(), user.getEmail());

        // ----------------------------------------------------
        // 5) 응답 반환 (Swagger / 프론트에서 바로 복사해서 사용 가능)
        // ----------------------------------------------------
        return ResponseEntity.ok(new SeedResponse(
                user.getId(),
                user.getEmail(),
                access,
                refresh.getToken(),
                refresh.getExpiry()
        ));
    }

    /**
     * 개발용 Seed 응답 DTO
     * - Swagger에서 dev 계정 생성 후 바로 토큰을 확인하고 복사할 수 있도록 설계
     */
    public record SeedResponse(
            Long userId,
            String email,
            String accessToken,
            String refreshToken,
            LocalDateTime refreshExpiry
    ) {}
}
