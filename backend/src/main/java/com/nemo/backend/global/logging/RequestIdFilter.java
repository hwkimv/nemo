// backend/src/main/java/com/nemo/backend/global/logging/RequestIdFilter.java
package com.nemo.backend.global.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 요청 하나를 끝까지 따라갈 수 있도록 request id를 부여한다.
 * - 클라이언트가 X-Request-Id를 보내면 그대로 이어받고, 없으면 새로 만든다.
 * - MDC에 넣어 두면 해당 요청이 남기는 모든 로그에 같은 id가 찍힌다.
 * - 응답 헤더로도 돌려주므로, 사용자가 겪은 오류를 로그에서 바로 찾을 수 있다.
 *
 * 보안 필터보다 먼저 동작해야 401/403 응답도 같은 id로 추적된다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    /** 외부에서 넘어온 값을 그대로 믿지 않기 위한 길이 제한 (로그 오염 방지) */
    private static final int MAX_INBOUND_ID_LENGTH = 64;

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse res,
                                    FilterChain chain)
            throws ServletException, IOException {

        String requestId = resolveRequestId(req);
        MDC.put(MDC_KEY, requestId);
        res.setHeader(REQUEST_ID_HEADER, requestId);

        try {
            chain.doFilter(req, res);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private String resolveRequestId(HttpServletRequest req) {
        String inbound = req.getHeader(REQUEST_ID_HEADER);
        if (StringUtils.hasText(inbound) && inbound.length() <= MAX_INBOUND_ID_LENGTH && isSafe(inbound)) {
            return inbound;
        }
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** 개행·제어문자로 로그를 조작하지 못하게 영문/숫자/-/_ 만 허용한다. */
    private boolean isSafe(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '-' || c == '_';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }
}
