// domain/auth/jwt/JwtAuthenticationFilter.java
package com.nemo.backend.domain.auth.jwt;

import com.nemo.backend.domain.auth.principal.UserPrincipal;
import com.nemo.backend.global.security.PublicEndpoints;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 매 요청마다 Authorization 헤더를 검사해 유효한 경우 SecurityContext에 인증 주체(UserPrincipal)를 설정한다.
 * - 공개 경로(로그인/회원가입/이메일 인증/비밀번호 재설정/Swagger/H2 등)는 필터를 건너뜀
 * - 보호 경로에서 토큰이 없거나 잘못됐으면 401로 응답
 */
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    // 🔓 이 경로들은 필터를 건너뜁니다 (공개, 토큰 필요 없음)
    //  - SecurityConfig와 같은 목록을 공유해 두 정책이 어긋나지 않게 한다.
    private static final List<String> PUBLIC_PATTERNS = PublicEndpoints.PATTERNS;

    // 🔒 이 경로들은 토큰이 반드시 필요합니다 (보호 대상)
    //  - PUBLIC_PATTERNS 에 포함된 것들은 예외
    private static final List<String> PROTECTED_PATTERNS = List.of(
            "/api/**"      // 전체 API 보호, 위의 PUBLIC 은 스킵
    );

    public JwtAuthenticationFilter(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse res,
                                    FilterChain chain)
            throws ServletException, IOException {

        String uri = req.getRequestURI();

        // 1) 공개 경로: 토큰 검사 없이 바로 통과
        if (matchesAny(uri, PUBLIC_PATTERNS)) {
            chain.doFilter(req, res);
            return;
        }

        // 2) 이미 인증된 경우(다른 필터에서 넣었을 때): 그대로 통과
        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            chain.doFilter(req, res);
            return;
        }

        // 3) 보호 경로: 토큰 필수
        if (matchesAny(uri, PROTECTED_PATTERNS)) {
            String authHeader = req.getHeader("Authorization");

            if (!StringUtils.hasText(authHeader)) {
                writeUnauthorized(res, "Authorization header is missing");
                return;
            }

            try {
                Long userId = jwtUtil.getUserId(authHeader);
                String email = null; // 필요하면 jwtUtil.getEmail(authHeader) 사용

                UserPrincipal principal = new UserPrincipal(userId, email);
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(principal, null, null);

                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (Exception e) {
                log.debug("JWT parse/verify failed: {}", e.getMessage());
                writeUnauthorized(res, e.getMessage());
                return;
            }
        }

        // 4) 그 외 경로: 추가 정책 없이 그대로 진행
        chain.doFilter(req, res);
    }

    private boolean matchesAny(String uri, List<String> patterns) {
        for (String p : patterns) {
            if (pathMatcher.match(p, uri)) {
                return true;
            }
        }
        return false;
    }

    private void writeUnauthorized(HttpServletResponse res, String message) throws IOException {
        res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write("{\"error\":\"UNAUTHORIZED\",\"message\":\"" + message + "\"}");
    }
}
