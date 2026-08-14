// src/main/java/com/nemo/backend/config/LogConfig.java
package com.nemo.backend.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * API 요청 접근 로그.
 *
 * 남기는 값은 비밀이 아닌 것만으로 제한한다: HTTP method, URI(쿼리스트링 제외), status, 처리시간.
 * Authorization 헤더(Access/Refresh Token), 쿠키, QR payload, 외부 provider token은 절대 남기지 않는다.
 * 토큰은 임시 출입증이라 로그 수집기·개발 PC·운영 콘솔에 한 줄이라도 남으면 만료 전까지 그대로 도용될 수 있다.
 *
 * 요청 식별은 {@link com.nemo.backend.global.logging.RequestIdFilter}가 MDC에 넣는 requestId로 한다.
 */
@Slf4j
@Configuration
public class LogConfig implements WebMvcConfigurer {

    private static final String START_TIME_ATTRIBUTE = LogConfig.class.getName() + ".startTime";

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
                req.setAttribute(START_TIME_ATTRIBUTE, System.nanoTime());
                return true;
            }

            @Override
            public void afterCompletion(HttpServletRequest req, HttpServletResponse res, Object handler, Exception ex) {
                Object startedAt = req.getAttribute(START_TIME_ATTRIBUTE);
                long elapsedMs = (startedAt instanceof Long start)
                        ? (System.nanoTime() - start) / 1_000_000L
                        : -1L;

                log.info("[REQ] {} {} status={} {}ms",
                        req.getMethod(), req.getRequestURI(), res.getStatus(), elapsedMs);
            }
        }).addPathPatterns("/api/**");
    }
}
