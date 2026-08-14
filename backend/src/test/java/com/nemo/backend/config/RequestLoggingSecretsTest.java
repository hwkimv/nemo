package com.nemo.backend.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nemo.backend.global.logging.RequestIdFilter;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0-1 회귀 테스트.
 *
 * "Authorization 헤더 원문이 로그에 남지 않는다"를 코드로 고정한다.
 * 예전 LogConfig는 log.info("Authorization={}", ...)로 Access Token 전체를 INFO 로그에 찍었다.
 * 이 테스트가 실패한다면 누군가 다시 토큰을 로그에 노출시킨 것이다.
 */
class RequestLoggingSecretsTest {

    /** 진짜 비밀값이 아니라 테스트용 더미 토큰이다. */
    private static final String DUMMY_ACCESS_TOKEN =
            "eyJhbGciOiJIUzI1NiJ9.test-secret-token-payload.test-secret-token-signature";

    private Logger logConfigLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        logConfigLogger = (Logger) LoggerFactory.getLogger(LogConfig.class);
        appender = new ListAppender<>();
        appender.start();
        logConfigLogger.addAppender(appender);
        logConfigLogger.setLevel(Level.TRACE);
    }

    @AfterEach
    void detachAppender() {
        logConfigLogger.detachAppender(appender);
        MDC.clear();
    }

    @Test
    void accessLogKeepsTraceInfoButNeverLogsAuthorizationHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/albums");
        request.addHeader("Authorization", "Bearer " + DUMMY_ACCESS_TOKEN);
        request.addHeader("Cookie", "refreshToken=" + DUMMY_ACCESS_TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        HandlerInterceptor interceptor = accessLogInterceptor();
        interceptor.preHandle(request, response, new Object());
        interceptor.afterCompletion(request, response, new Object(), null);

        String logged = renderedLog();

        // 1) 토큰 원문도, 앞 몇 글자도, Bearer 뒤 값 어느 조각도 남지 않는다.
        assertThat(logged).doesNotContain(DUMMY_ACCESS_TOKEN);
        assertThat(logged).doesNotContain(DUMMY_ACCESS_TOKEN.substring(0, 16));
        assertThat(logged).doesNotContain("Bearer");
        assertThat(logged).doesNotContainIgnoringCase("Authorization");

        // 2) 요청 추적에 필요한 비밀이 아닌 정보는 계속 남는다.
        assertThat(logged).contains("GET");
        assertThat(logged).contains("/api/albums");
        assertThat(logged).contains("status=200");
    }

    @Test
    void requestIdFilterPutsSameIdInMdcAndResponseHeader() throws Exception {
        RequestIdFilter filter = new RequestIdFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/albums");
        MockHttpServletResponse response = new MockHttpServletResponse();

        String[] idSeenInsideChain = new String[1];
        FilterChain chain = (req, res) -> idSeenInsideChain[0] = MDC.get(RequestIdFilter.MDC_KEY);

        filter.doFilter(request, response, chain);

        String headerId = response.getHeader(RequestIdFilter.REQUEST_ID_HEADER);
        assertThat(headerId).isNotBlank();
        // 응답으로 돌려준 id와 로그에 찍히는 id가 같아야 사용자가 겪은 오류를 로그에서 찾을 수 있다.
        assertThat(idSeenInsideChain[0]).isEqualTo(headerId);
        // 요청이 끝나면 MDC는 비워져 다음 요청 로그에 섞이지 않는다.
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void requestIdFilterRejectsHeaderInjectionFromClient() throws Exception {
        RequestIdFilter filter = new RequestIdFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/albums");
        request.addHeader(RequestIdFilter.REQUEST_ID_HEADER, "abc\nFAKE LOG LINE injected");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        assertThat(response.getHeader(RequestIdFilter.REQUEST_ID_HEADER))
                .doesNotContain("FAKE LOG LINE")
                .doesNotContain("\n");
    }

    private String renderedLog() {
        List<ILoggingEvent> events = appender.list;
        assertThat(events).isNotEmpty();
        StringBuilder sb = new StringBuilder();
        for (ILoggingEvent event : events) {
            sb.append(event.getFormattedMessage()).append('\n');
            sb.append(event.getMessage()).append('\n'); // 포맷 이전 원본 패턴까지 검사
        }
        return sb.toString();
    }

    /** LogConfig가 실제로 등록하는 인터셉터 인스턴스를 꺼내온다. */
    private HandlerInterceptor accessLogInterceptor() throws Exception {
        InterceptorRegistry registry = new InterceptorRegistry();
        new LogConfig().addInterceptors(registry);

        Field registrationsField = InterceptorRegistry.class.getDeclaredField("registrations");
        registrationsField.setAccessible(true);
        List<?> registrations = (List<?>) registrationsField.get(registry);
        assertThat(registrations).hasSize(1);

        Object registration = registrations.get(0);
        Field interceptorField = registration.getClass().getDeclaredField("interceptor");
        interceptorField.setAccessible(true);
        return (HandlerInterceptor) interceptorField.get(registration);
    }
}
