// backend/src/main/java/com/nemo/backend/global/security/PublicEndpoints.java
package com.nemo.backend.global.security;

import java.util.List;

/**
 * 토큰 없이 접근할 수 있는 경로의 단일 정의.
 *
 * 예전에는 {@link SecurityConfig}와 {@link com.nemo.backend.domain.auth.jwt.JwtAuthenticationFilter}가
 * 같은 목록을 따로 들고 있었고, 그래서 실제로 존재하지도 않는 {@code /api/auth/logout}이 공개로 열려 있는 동안
 * 진짜 endpoint인 {@code /api/users/logout}은 보호 경로로 막혀 있었다.
 * 두 곳이 항상 같은 목록을 보도록 여기에 모아둔다.
 */
public final class PublicEndpoints {

    private PublicEndpoints() {
    }

    public static final List<String> PATTERNS = List.of(
            // infra / 문서
            "/h2-console/**",
            "/swagger-ui/**",
            "/v3/api-docs/**",
            "/files/**",
            "/actuator/health",
            "/actuator/info",

            // 회원 가입 / 로그인
            "/api/users/signup",
            "/api/users/login",

            // 로그아웃: Access Token이 이미 만료된 사용자도 Refresh Token을 폐기할 수 있어야 한다.
            // 실제 자격 증명은 body의 refreshToken이며, AuthService.logout()이 소유자를 검증한다.
            // 반드시 실제 endpoint와 같은 경로여야 한다. (/api/users/** 전체를 열지 않는다)
            "/api/users/logout",

            // 소셜 로그인
            "/api/auth/oauth/**",

            // 이메일 인증 (ex. /api/auth/email/verification/send, /confirm)
            "/api/auth/email/**",

            // 비밀번호 찾기 / 재설정 계열
            "/api/auth/password/**",
            "/api/users/password/**",

            // Cloudflare Turnstile 캡챠 검증 (로그인 전에 호출되므로 토큰이 없다)
            "/api/auth/turnstile",

            // 토큰 재발급 / 개발용 시드
            "/api/auth/refresh",
            "/api/auth/dev/**"
    );

    public static String[] asArray() {
        return PATTERNS.toArray(new String[0]);
    }
}
