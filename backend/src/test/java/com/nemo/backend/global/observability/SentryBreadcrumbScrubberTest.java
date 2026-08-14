package com.nemo.backend.global.observability;

import io.sentry.Breadcrumb;
import io.sentry.Hint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * breadcrumb으로 비밀값이 새어나가지 않는지 고정한다.
 *
 * {@code sentry.logging.minimum-breadcrumb-level: info} 이므로 INFO 이상 로그가
 * 전부 breadcrumb으로 Sentry에 실려 간다. request 스크러빙만으로는 이 경로를 막지 못한다.
 *
 * 실제 Sentry UI에서 breadcrumb에 우리 로그가 그대로 실려 있는 것을 보고 발견했다.
 * 당시 새는 값은 없었지만(자격증명은 헤더로 나가고 URI엔 없다), 구조적으로 뚫려 있었다.
 */
@DisplayName("Sentry breadcrumb 스크러빙")
class SentryBreadcrumbScrubberTest {

    /** 진짜 비밀값이 아니라 테스트용 더미다. */
    private static final String DUMMY_JWT = "eyJhbGciOiJIUzI1NiJ9.ZHVtbXktcGF5bG9hZA.ZHVtbXktc2ln";

    private final SentryBreadcrumbScrubber scrubber = new SentryBreadcrumbScrubber();

    private String scrubMessage(String message) {
        Breadcrumb crumb = new Breadcrumb();
        crumb.setMessage(message);
        return scrubber.execute(crumb, new Hint()).getMessage();
    }

    @Test
    @DisplayName("로그 메시지의 Bearer 토큰을 가린다")
    void redactsBearerToken() {
        String result = scrubMessage("upstream call failed. Authorization: Bearer " + DUMMY_JWT);

        assertThat(result).doesNotContain(DUMMY_JWT);
        assertThat(result).contains("upstream call failed"); // 진단 정보는 남는다
    }

    @Test
    @DisplayName("이름이 붙은 비밀값을 가린다 (token=, password=, client_secret=)")
    void redactsNamedSecrets() {
        String result = scrubMessage(
                "retry uri=http://api.test/search?query=cafe&token=SUPERSECRET&display=5");

        assertThat(result).doesNotContain("SUPERSECRET");
        assertThat(result).contains("token=[redacted]");
        // 나머지 파라미터는 남아야 원인 파악이 된다
        assertThat(result).contains("query=cafe").contains("display=5");

        assertThat(scrubMessage("login failed password=hunter2")).doesNotContain("hunter2");
        assertThat(scrubMessage("cfg client_secret: abc123xyz")).doesNotContain("abc123xyz");
    }

    @Test
    @DisplayName("이름 없이 JWT 모양만 있어도 가린다")
    void redactsBareJwtByShape() {
        // 이름표가 없는 경우 — 모양으로 잡아야 한다
        String result = scrubMessage("decoded " + DUMMY_JWT + " ok");

        assertThat(result).doesNotContain(DUMMY_JWT);
        assertThat(result).contains("decoded").contains("ok");
    }

    @Test
    @DisplayName("비밀이 아닌 메시지는 그대로 둔다")
    void keepsHarmlessMessagesIntact() {
        String message = "[NAVER][EX][LOCAL] attempt 3/3 → 2000ms 대기 후 재시도. display=5&start=1";

        assertThat(scrubMessage(message)).isEqualTo(message);
    }

    @Test
    @DisplayName("data 맵은 키 이름이 민감하면 값을 가린다")
    void redactsSensitiveDataKeys() {
        Breadcrumb crumb = new Breadcrumb();
        crumb.setData("accessToken", DUMMY_JWT);
        crumb.setData("userId", 42);
        crumb.setData("note", "Bearer " + DUMMY_JWT);

        Breadcrumb result = scrubber.execute(crumb, new Hint());

        assertThat(result.getData("accessToken")).isEqualTo("[redacted]");
        assertThat(result.getData("userId")).isEqualTo(42);          // 진단에 필요한 값은 유지
        assertThat(result.getData("note").toString()).doesNotContain(DUMMY_JWT);
    }

    @Test
    @DisplayName("메시지가 없는 breadcrumb도 안전하게 통과한다")
    void handlesNullMessage() {
        Breadcrumb crumb = new Breadcrumb();

        assertThat(scrubber.execute(crumb, new Hint())).isNotNull();
    }
}
