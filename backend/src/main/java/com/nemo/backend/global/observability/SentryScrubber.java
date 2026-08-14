// backend/src/main/java/com/nemo/backend/global/observability/SentryScrubber.java
package com.nemo.backend.global.observability;

import io.sentry.SentryEvent;
import io.sentry.SentryOptions;
import io.sentry.protocol.Request;
import io.sentry.protocol.User;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Sentry로 나가는 이벤트에서 비밀값과 개인정보를 지운다.
 *
 * 왜 필요한가:
 * Sentry SDK는 오류 맥락을 풍부하게 만들려고 요청 헤더·쿠키·쿼리스트링을 함께 보낸다.
 * 그 안에는 Authorization 헤더(Access Token)가 그대로 들어 있다.
 * 우리는 이미 서버 로그에서 토큰을 지웠는데, 오류 추적 도구로 같은 값이 나가면
 * 지운 의미가 없다. Sentry는 외부 서비스이므로 오히려 더 넓게 퍼진다.
 *
 * 기본 원칙은 <b>허용 목록이 아니라 차단 목록</b>이 아니라는 점이다.
 * 헤더는 "남길 것만 남기는" 방식으로 처리한다. 새 헤더가 추가됐을 때
 * 아무도 손대지 않아도 자동으로 안전한 쪽에 서기 때문이다.
 *
 * @see com.nemo.backend.config.LogConfig 같은 기준으로 서버 로그에서도 토큰을 지운다
 */
@Component
public class SentryScrubber implements SentryOptions.BeforeSendCallback {

    /**
     * 이벤트에 남겨도 되는 헤더.
     * 여기에 없는 헤더는 전부 지운다. (Authorization, Cookie, X-Api-Key 등이 자동으로 걸린다)
     */
    private static final Set<String> ALLOWED_HEADERS = Set.of(
            "accept",
            "accept-encoding",
            "accept-language",
            "content-type",
            "content-length",
            "user-agent",
            "referer",
            "x-request-id"   // 서버 로그와 이벤트를 이어주는 값
    );

    /** 쿼리스트링·바디에서 이 단어가 이름에 들어가면 값을 가린다. */
    private static final Set<String> SENSITIVE_NAME_PARTS = Set.of(
            "token", "password", "secret", "authorization", "credential",
            "apikey", "api_key", "clientsecret", "client_secret", "code"
    );

    private static final String REDACTED = "[redacted]";

    @Override
    public SentryEvent execute(SentryEvent event, io.sentry.Hint hint) {
        Request request = event.getRequest();
        if (request != null) {
            request.setHeaders(scrubHeaders(request.getHeaders()));
            request.setCookies(null);                       // 쿠키는 통째로 버린다
            request.setQueryString(scrubQuery(request.getQueryString()));
            request.setData(null);                          // 요청 본문에는 비밀번호가 들어올 수 있다
            request.setEnvs(null);                          // 서버 환경변수 (DB 비밀번호 등)
        }

        // 사용자 식별은 내부 id만 남긴다. 이메일·IP는 개인정보다.
        User user = event.getUser();
        if (user != null) {
            User safe = new User();
            safe.setId(user.getId());
            event.setUser(safe);
        }

        event.removeExtra("password");
        return event;
    }

    private Map<String, String> scrubHeaders(Map<String, String> headers) {
        if (headers == null) {
            return null;
        }
        Map<String, String> safe = new LinkedHashMap<>();
        headers.forEach((name, value) -> {
            if (name != null && ALLOWED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                safe.put(name, value);
            }
        });
        return safe;
    }

    /**
     * 쿼리스트링에서 민감한 이름의 값만 가린다.
     * 통째로 버리지 않는 이유: 어떤 파라미터로 요청했는지가 원인 파악에 필요하다.
     */
    private String scrubQuery(String queryString) {
        if (queryString == null || queryString.isBlank()) {
            return queryString;
        }
        String[] pairs = queryString.split("&");
        StringBuilder out = new StringBuilder();
        for (String pair : pairs) {
            if (!out.isEmpty()) {
                out.append('&');
            }
            int eq = pair.indexOf('=');
            if (eq < 0) {
                out.append(pair);
                continue;
            }
            String name = pair.substring(0, eq);
            out.append(name).append('=');
            out.append(isSensitive(name) ? REDACTED : pair.substring(eq + 1));
        }
        return out.toString();
    }

    private boolean isSensitive(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return SENSITIVE_NAME_PARTS.stream().anyMatch(lower::contains);
    }
}
