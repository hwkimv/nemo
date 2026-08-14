package com.nemo.backend.global.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nemo.backend.domain.auth.dto.RefreshRequest;
import com.nemo.backend.domain.auth.jwt.JwtUtil;
import com.nemo.backend.domain.auth.token.RefreshToken;
import com.nemo.backend.domain.auth.token.RefreshTokenRepository;
import com.nemo.backend.domain.map.util.NaverApiClient;
import com.nemo.backend.domain.user.entity.User;
import com.nemo.backend.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.services.s3.S3Client;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P0-4 회귀 테스트.
 *
 * 예전에는 SecurityConfig와 JwtAuthenticationFilter가 존재하지도 않는 /api/auth/logout을 공개로 열어두고,
 * 진짜 endpoint인 /api/users/logout은 보호 경로로 막아뒀다.
 * 그래서 "Access Token이 만료되어 로그아웃하려는" 가장 흔한 상황에서 Controller에 도달조차 못 하고 401이 났다.
 *
 * 동시에, 고친다고 /api/users/** 전체를 열어버리면 안 된다는 것도 함께 고정한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@DisplayName("logout endpoint와 보안 공개 경로가 일치하는지")
class LogoutEndpointSecurityTest {

    @MockitoBean
    private S3Client s3Client;
    @MockitoBean
    private NaverApiClient naverApiClient;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private RefreshTokenRepository refreshTokenRepository;
    @Autowired
    private UserRepository userRepository;

    @Value("${app.jwt.secret}")
    private String jwtSecret;
    @Value("${app.jwt.issuer}")
    private String jwtIssuer;

    private User user;

    @BeforeEach
    void setUp() {
        User u = new User();
        u.setEmail("logout-test-" + UUID.randomUUID() + "@nemo.test");
        u.setPassword("{noop}irrelevant");
        u.setNickname("logout-test");
        u.setProvider("local");
        user = userRepository.save(u);
    }

    private String persistValidRefreshToken() {
        RefreshToken token = new RefreshToken();
        token.setUserId(user.getId());
        token.setToken(UUID.randomUUID().toString());
        token.setExpiry(LocalDateTime.now().plusDays(14));
        return refreshTokenRepository.saveAndFlush(token).getToken();
    }

    /** 만료 시각이 clock skew(3분)보다 훨씬 과거인 Access Token */
    private String expiredAccessToken() {
        JwtUtil expiredIssuer = new JwtUtil(jwtSecret, jwtIssuer, -600_000L);
        return expiredIssuer.createAccessToken(user.getId(), user.getEmail());
    }

    @Test
    @DisplayName("만료된 Access Token + 유효한 Refresh Token으로 로그아웃할 수 있다")
    void expiredAccessTokenCanStillLogout() throws Exception {
        String refreshToken = persistValidRefreshToken();

        mockMvc.perform(post("/api/users/logout")
                        .header("Authorization", "Bearer " + expiredAccessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshRequest(refreshToken))))
                .andExpect(status().isOk());

        assertThat(refreshTokenRepository.findByToken(refreshToken))
                .as("로그아웃하면 Refresh Token은 폐기되어야 한다")
                .isEmpty();
    }

    @Test
    @DisplayName("Access Token 없이 유효한 Refresh Token만으로도 로그아웃된다")
    void refreshTokenAloneIsEnoughToLogout() throws Exception {
        String refreshToken = persistValidRefreshToken();

        mockMvc.perform(post("/api/users/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshRequest(refreshToken))))
                .andExpect(status().isOk());

        assertThat(refreshTokenRepository.findByToken(refreshToken)).isEmpty();
    }

    @Test
    @DisplayName("로그아웃한 Refresh Token은 재사용할 수 없다")
    void loggedOutRefreshTokenCannotBeReused() throws Exception {
        String refreshToken = persistValidRefreshToken();
        String body = objectMapper.writeValueAsString(new RefreshRequest(refreshToken));

        mockMvc.perform(post("/api/users/logout")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        // 폐기된 토큰으로 Access Token을 다시 받아낼 수 없어야 한다.
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("로그아웃을 열었다고 해서 /api/users/** 전체가 열리지는 않는다")
    void otherUserEndpointsStillRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/users/me")
                        .header("Authorization", "Bearer " + expiredAccessToken()))
                .andExpect(status().isUnauthorized());
    }
}
