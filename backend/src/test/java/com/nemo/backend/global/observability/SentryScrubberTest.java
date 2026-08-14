package com.nemo.backend.global.observability;

import io.sentry.Hint;
import io.sentry.SentryEvent;
import io.sentry.protocol.Request;
import io.sentry.protocol.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sentry로 나가는 이벤트에 비밀값·개인정보가 섞이지 않는지 고정한다.
 *
 * 서버 로그에서는 이미 토큰을 지웠다(CS 03). 그런데 오류 추적 도구로 같은 값이 나가면
 * 지운 의미가 없다. Sentry는 외부 서비스이므로 오히려 더 넓게 퍼진다.
 *
 * Sentry SDK는 기본적으로 요청 헤더·쿠키·쿼리스트링을 이벤트에 붙인다.
 * 그 안에 Authorization 헤더가 그대로 들어 있다.
 */
@DisplayName("Sentry 전송 전 스크러빙")
class SentryScrubberTest {

    /** 진짜 비밀값이 아니라 테스트용 더미다. */
    private static final String DUMMY_TOKEN = "eyJhbGciOiJIUzI1NiJ9.dummy-payload.dummy-signature";

    private final SentryScrubber scrubber = new SentryScrubber();

    private SentryEvent eventWithRequest(Map<String, String> headers, String query) {
        SentryEvent event = new SentryEvent();
        Request request = new Request();
        request.setHeaders(new LinkedHashMap<>(headers));
        request.setQueryString(query);
        request.setCookies("refreshToken=" + DUMMY_TOKEN);
        request.setData(Map.of("password", "hunter2"));
        request.setEnvs(Map.of("DB_PASSWORD", "super-secret"));
        event.setRequest(request);
        return event;
    }

    @Test
    @DisplayName("Authorization 헤더는 이벤트에서 제거된다")
    void authorizationHeaderIsRemoved() {
        SentryEvent event = eventWithRequest(Map.of(
                "Authorization", "Bearer " + DUMMY_TOKEN,
                "Content-Type", "application/json"), null);

        SentryEvent result = scrubber.execute(event, new Hint());

        Map<String, String> headers = result.getRequest().getHeaders();
        assertThat(headers).doesNotContainKey("Authorization");
        assertThat(headers.toString()).doesNotContain(DUMMY_TOKEN);
        assertThat(headers).containsKey("Content-Type"); // 진단에 필요한 것은 남는다
    }

    @Test
    @DisplayName("허용 목록에 없는 헤더는 이름을 몰라도 자동으로 걸러진다")
    void unknownHeadersAreDroppedByDefault() {
        // 차단 목록 방식이면 새 헤더가 생길 때마다 사람이 추가해야 하고, 빠뜨리면 샌다.
        SentryEvent event = eventWithRequest(Map.of(
                "X-Api-Key", "secret-key-value",
                "X-Internal-Session", "session-value",
                "User-Agent", "curl/8.5.0"), null);

        Map<String, String> headers = scrubber.execute(event, new Hint()).getRequest().getHeaders();

        assertThat(headers).containsOnlyKeys("User-Agent");
        assertThat(headers.toString())
                .doesNotContain("secret-key-value")
                .doesNotContain("session-value");
    }

    @Test
    @DisplayName("X-Request-Id는 남는다 — 서버 로그와 이벤트를 잇는 값")
    void requestIdSurvives() {
        SentryEvent event = eventWithRequest(Map.of("X-Request-Id", "a3f9c1d2"), null);

        Map<String, String> headers = scrubber.execute(event, new Hint()).getRequest().getHeaders();

        assertThat(headers).containsEntry("X-Request-Id", "a3f9c1d2");
    }

    @Test
    @DisplayName("쿼리스트링은 민감한 이름의 값만 가리고 나머지는 남긴다")
    void queryStringKeepsDiagnosticValues() {
        SentryEvent event = eventWithRequest(Map.of(),
                "page=0&size=10&token=SUPERSECRET&access_token=ALSO_SECRET");

        String query = scrubber.execute(event, new Hint()).getRequest().getQueryString();

        // 어떤 파라미터로 요청했는지는 원인 파악에 필요하므로 통째로 버리지 않는다.
        assertThat(query).contains("page=0").contains("size=10");
        assertThat(query).doesNotContain("SUPERSECRET").doesNotContain("ALSO_SECRET");
        assertThat(query).contains("token=[redacted]");
    }

    @Test
    @DisplayName("쿠키·요청 본문·서버 환경변수는 통째로 버린다")
    void cookiesBodyAndEnvsAreDropped() {
        SentryEvent event = eventWithRequest(Map.of("Accept", "*/*"), "a=b");

        Request request = scrubber.execute(event, new Hint()).getRequest();

        assertThat(request.getCookies()).isNull();   // refreshToken이 들어 있다
        assertThat(request.getData()).isNull();      // 비밀번호가 들어올 수 있다
        assertThat(request.getEnvs()).isNull();      // DB 비밀번호 등
    }

    @Test
    @DisplayName("사용자는 내부 id만 남기고 이메일·IP는 지운다")
    void userKeepsOnlyInternalId() {
        SentryEvent event = new SentryEvent();
        User user = new User();
        user.setId("42");
        user.setEmail("someone@nemo.test");
        user.setUsername("someone");
        user.setIpAddress("203.0.113.9");
        event.setUser(user);

        User result = scrubber.execute(event, new Hint()).getUser();

        assertThat(result.getId()).isEqualTo("42");
        assertThat(result.getEmail()).isNull();
        assertThat(result.getUsername()).isNull();
        assertThat(result.getIpAddress()).isNull();
    }

    @Test
    @DisplayName("요청 정보가 없는 이벤트도 그대로 통과한다")
    void eventWithoutRequestIsUnchanged() {
        SentryEvent event = new SentryEvent();

        assertThat(scrubber.execute(event, new Hint())).isSameAs(event);
    }
}
