package com.nemo.backend.domain.map.util;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.nemo.backend.global.exception.ApiException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * <h2>외부 API 호출 간격이 동시 요청에서도 지켜지는지 검증한다.</h2>
 *
 * <h3>무엇을 재는가</h3>
 * 응답시간이 아니라 <b>외부로 나간 호출들의 시각</b>을 잰다.
 * 레이트 리미터의 목적은 빠르게 처리하는 것이 아니라 <b>네이버로 나가는 호출을 억제</b>하는 것이다.
 * 그래서 "몇 초 걸렸나"가 아니라 "연속한 두 호출 사이가 얼마나 떨어져 있나"를 본다.
 *
 * <h3>왜 실제 HTTP를 쓰지 않는가</h3>
 * 실제 네트워크가 끼면 지연이 흔들려 <b>flaky</b>해진다.
 * 여기서 확인하려는 것은 네트워크가 아니라 <b>리미터의 동시성 정확성</b>이다.
 * 가짜 RestTemplate이 호출 시각만 기록한다.
 */
@DisplayName("지도 외부 API 호출 간격 제한")
class NaverApiRateLimitTest {

    /** 설정 기본값. 초당 5회를 의도한 값이다. */
    private static final long MIN_INTERVAL_MS = 200;

    /**
     * 1초 안에 허용되는 최대 호출 수.
     *
     * <p>5가 아니라 6인 이유: 창의 시작점이 첫 호출과 정확히 겹치면
     * 0ms, 200, 400, 600, 800 다섯 건에 더해 1000ms 직전 한 건이 더 들어올 수 있다.
     * 경계 하나를 허용하는 것이며, 동시성 버그가 있으면 이 값을 훨씬 크게 넘는다
     * (수정 전 실측: 동시 16에서 1초 안에 16회).
     */
    private static final int MAX_PER_SECOND = 6;

    /**
     * 외부 호출이 일어난 시각(ms)을 순서대로 기록하는 클라이언트를 만든다.
     */
    private static Recorded newClient() {
        List<Long> callTimes = java.util.Collections.synchronizedList(new ArrayList<>());
        RestTemplate restTemplate = mock(RestTemplate.class);

        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenAnswer(invocation -> {
                    callTimes.add(System.nanoTime() / 1_000_000);
                    return ResponseEntity.ok(Map.of("items", List.of()));
                });

        // 캐시를 끈다. 캐시가 켜져 있으면 같은 검색어가 hit이 되어
        // 외부로 나가지 않고, 그러면 리미터를 재는 의미가 없다.
        NaverApiClient client = new NaverApiClient(
                restTemplate, 0L, 1000L, 0L, 1000L, new SimpleMeterRegistry());
        ReflectionTestUtils.setField(client, "endpoint", "https://naver.test/search/v1/local");
        ReflectionTestUtils.setField(client, "clientId", "test-id");
        ReflectionTestUtils.setField(client, "clientSecret", "test-secret");
        ReflectionTestUtils.setField(client, "reverseEndpoint", "https://naver.test/map-reversegeocode/v2/gc");
        ReflectionTestUtils.setField(client, "mapClientId", "test-map-id");
        ReflectionTestUtils.setField(client, "mapClientSecret", "test-map-secret");
        ReflectionTestUtils.setField(client, "minIntervalMs", MIN_INTERVAL_MS);
        // 대기 상한을 넉넉히 둔다. 이 테스트가 보려는 것은 "거절 정책"이 아니라
        // "호출률이 지켜지는가"다. 상한에 걸려 거절되면 호출이 줄어 측정이 무의미해진다.
        // 거절 동작은 RateLimitPolicy 테스트가 따로 본다.
        ReflectionTestUtils.setField(client, "maxWaitMs", 120_000L);
        return new Recorded(client, callTimes);
    }

    private record Recorded(NaverApiClient client, List<Long> callTimes) {

        /** 연속한 두 외부 호출 사이의 간격들 */
        List<Long> gaps() {
            List<Long> sorted = new ArrayList<>(callTimes);
            java.util.Collections.sort(sorted);
            List<Long> gaps = new ArrayList<>();
            for (int i = 1; i < sorted.size(); i++) {
                gaps.add(sorted.get(i) - sorted.get(i - 1));
            }
            return gaps;
        }

        long minGap() {
            return gaps().stream().mapToLong(Long::longValue).min().orElse(Long.MAX_VALUE);
        }

        /**
         * 1초짜리 창을 밀어가며, 어느 창에든 몇 건이 들어갔는지 중 최댓값.
         *
         * <p><b>이것이 쿼터가 실제로 요구하는 성질이다.</b> "초당 5회"는
         * "연속한 두 호출이 200ms 떨어져 있어야 한다"가 아니라
         * "어떤 1초를 잘라도 5회를 넘지 않아야 한다"는 뜻이다.
         *
         * <p>최소 간격만 보면 오탐이 난다. JVM이 GC 등으로 잠깐 멈췄다가 재개되면
         * 그 직후 한 건이 곧바로 나가면서 간격이 짧아진다. 하지만 멈춰 있는 동안
         * 아무 호출도 안 나갔으므로 <b>쿼터를 넘긴 것이 아니다.</b>
         * 창으로 세면 이 경우가 정상으로 잡힌다.
         */
        int maxCallsInAnyOneSecond() {
            List<Long> sorted = new ArrayList<>(callTimes);
            java.util.Collections.sort(sorted);
            int max = 0;
            for (int i = 0; i < sorted.size(); i++) {
                int count = 0;
                for (int j = i; j < sorted.size() && sorted.get(j) - sorted.get(i) < 1000; j++) {
                    count++;
                }
                max = Math.max(max, count);
            }
            return max;
        }

        /** 관측된 초당 호출 수 = 호출 수 / 첫 호출부터 마지막 호출까지의 시간 */
        double observedRatePerSecond() {
            if (callTimes.size() < 2) return 0;
            List<Long> sorted = new ArrayList<>(callTimes);
            java.util.Collections.sort(sorted);
            long spanMs = sorted.get(sorted.size() - 1) - sorted.get(0);
            if (spanMs <= 0) return Double.POSITIVE_INFINITY;
            // 첫 호출은 대기 없이 나가므로, 간격의 개수(n-1)를 기준으로 센다.
            return (callTimes.size() - 1) * 1000.0 / spanMs;
        }
    }

    // ─────────────────────── 단일 스레드 ───────────────────────

    @Nested
    @DisplayName("단일 스레드")
    class SingleThread {

        @Test
        @DisplayName("연속 호출 사이가 최소 간격 이상 벌어진다")
        void keepsMinimumGap() {
            Recorded r = newClient();

            for (int i = 0; i < 6; i++) {
                r.client().searchLocal("검색어" + i, 5, 1, "random");
            }

            assertThat(r.callTimes()).hasSize(6);
            assertThat(r.maxCallsInAnyOneSecond())
                    .as("""
                            어느 1초를 잘라도 %d회를 넘으면 안 된다.
                            실제 간격: %s""".formatted(MAX_PER_SECOND, r.gaps()))
                    .isLessThanOrEqualTo(MAX_PER_SECOND);
        }
    }

    // ─────────────────────── 동시 요청 ───────────────────────

    @Nested
    @DisplayName("동시 요청")
    class Concurrent {

        @Test
        @DisplayName("4개 스레드")
        void fourThreads() throws Exception {
            assertRateIsRespected(4);
        }

        @Test
        @DisplayName("8개 스레드")
        void eightThreads() throws Exception {
            assertRateIsRespected(8);
        }

        @Test
        @DisplayName("16개 스레드")
        void sixteenThreads() throws Exception {
            assertRateIsRespected(16);
        }

        /** 스레드 하나가 보내는 호출 수. 뒤에 설명이 있다. */
        private static final int CALLS_PER_THREAD = 3;

        /**
         * 스레드 n개가 동시에 외부 호출을 시도해도 호출률이 지켜지는지 본다.
         *
         * <p>스레드마다 <b>서로 다른 검색어</b>를 쓴다. 같은 검색어면 의미가 흐려지고,
         * 실제 뷰포트 요청도 키워드가 전부 다르다.
         *
         * <p><b>스레드당 3회씩 보내는 이유</b> — 1회씩만 보내면 동시성 4에서
         * 총 4건이라 1초 예산(6건) 안에 들어가 <b>버그가 있어도 통과한다.</b>
         * 실제로 처음에 1회씩으로 짰다가 수정 전 코드에서 동시성 4가 통과하는 것을 보고 늘렸다.
         * 3회씩이면 동시성 4에서 12건이라, 버그가 있으면 한 창에 12건이 몰려 확실히 잡힌다.
         */
        private void assertRateIsRespected(int threads) throws Exception {
            Recorded r = newClient();
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);

            for (int i = 0; i < threads; i++) {
                final int idx = i;
                pool.submit(() -> {
                    try {
                        ready.countDown();
                        start.await();                 // 최대한 같은 순간에 출발시킨다
                        for (int c = 0; c < CALLS_PER_THREAD; c++) {
                            r.client().searchLocal("동시검색" + idx + "-" + c, 5, 1, "random");
                        }
                    } catch (Exception ignored) {
                        // 호출 실패는 이 테스트의 관심사가 아니다
                    } finally {
                        done.countDown();
                    }
                });
            }

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(done.await(120, TimeUnit.SECONDS)).isTrue();
            pool.shutdownNow();

            assertThat(r.callTimes()).hasSize(threads * CALLS_PER_THREAD);
            assertThat(r.maxCallsInAnyOneSecond())
                    .as("""
                            동시 요청 %d개에서 1초 안에 최대 %d회가 나갔다. 상한은 %d회다.
                            최소 호출 간격 %dms (의도 %dms)
                            관측 호출률 %.1f회/초 (의도 %.1f회/초)
                            간격 전체: %s"""
                            .formatted(threads, r.maxCallsInAnyOneSecond(), MAX_PER_SECOND,
                                    r.minGap(), MIN_INTERVAL_MS,
                                    r.observedRatePerSecond(), 1000.0 / MIN_INTERVAL_MS, r.gaps()))
                    .isLessThanOrEqualTo(MAX_PER_SECOND);
        }
    }

    // ─────────────────────── 대기 정책 ───────────────────────

    @Nested
    @DisplayName("대기 정책")
    class WaitPolicy {

        /**
         * 리미터를 정상화하면 새 문제가 생긴다 — <b>다들 앞에서 기다린다.</b>
         * 동시 16이면 마지막 스레드는 16 × 200ms를 기다린다.
         * 그 사이 클라이언트는 이미 떠났을 수 있고, 서버는 스레드만 붙잡고 있게 된다.
         */
        @Test
        @DisplayName("예상 대기가 상한을 넘으면 기다리지 않고 바로 거절한다")
        void rejectsWhenWaitExceedsLimit() {
            Recorded r = newClient();
            // 상한을 한 간격(200ms)보다 낮게 둔다.
            // 그러면 "바로 다음 차례"조차 상한을 넘으므로 결정적으로 거절된다.
            //
            // 처음에는 상한을 300ms로 두고 앞에서 5건을 순차로 불러 큐를 쌓으려 했는데
            // 거절되지 않았다. 순차 호출은 각자 기다린 뒤 나가므로 <b>큐가 쌓이지 않는다.</b>
            // 밀리는 건 동시에 들어올 때뿐이고, 그건 시간에 의존해 흔들린다.
            ReflectionTestUtils.setField(r.client(), "maxWaitMs", 100L);

            r.client().searchLocal("첫요청", 5, 1, "random");   // 대기 0이라 통과

            assertThatThrownBy(() -> r.client().searchLocal("밀린요청", 5, 1, "random"))
                    .as("""
                            다음 차례까지 200ms 기다려야 하는데 상한이 100ms다.
                            여기서 기다려 봐야 클라이언트는 떠난 뒤다.
                            스레드를 붙잡고 있느니 빨리 실패하는 편이 낫다.""")
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("몰려");
        }

        @Test
        @DisplayName("거절해도 외부 API는 부르지 않는다")
        void rejectedRequestDoesNotCallExternalApi() {
            Recorded r = newClient();
            ReflectionTestUtils.setField(r.client(), "maxWaitMs", 0L);   // 무조건 거절

            r.client().searchLocal("첫요청", 5, 1, "random");   // 첫 건은 대기 0이라 통과
            int afterFirst = r.callTimes().size();

            assertThatThrownBy(() -> r.client().searchLocal("두번째", 5, 1, "random"))
                    .isInstanceOf(ApiException.class);

            assertThat(r.callTimes())
                    .as("거절은 '부르지 않는 것'이다. 부르고 나서 실패하면 쿼터만 쓴다")
                    .hasSize(afterFirst);
        }

        @Test
        @DisplayName("기다리는 중 인터럽트되면 플래그를 되살리고 실패시킨다")
        void restoresInterruptFlag() throws Exception {
            Recorded r = newClient();
            r.client().searchLocal("선점", 5, 1, "random");   // 다음 차례를 200ms 뒤로 민다

            AtomicBoolean flagRestored = new AtomicBoolean(false);
            AtomicBoolean threw = new AtomicBoolean(false);
            CountDownLatch done = new CountDownLatch(1);

            Thread t = new Thread(() -> {
                try {
                    r.client().searchLocal("인터럽트될요청", 5, 1, "random");
                } catch (ApiException e) {
                    threw.set(true);
                    // 인터럽트를 삼키면 호출자가 취소를 알 수 없다.
                    flagRestored.set(Thread.currentThread().isInterrupted());
                } finally {
                    done.countDown();
                }
            });
            t.start();
            Thread.sleep(30);       // 리미터 앞에서 자고 있을 시점
            t.interrupt();

            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(threw).as("인터럽트되면 이 요청은 실패해야 한다").isTrue();
            assertThat(flagRestored)
                    .as("인터럽트 플래그를 되살려야 상위에서 취소를 인지할 수 있다")
                    .isTrue();
        }
    }

    // ─────────────────────── 캐시와의 관계 ───────────────────────

    @Nested
    @DisplayName("캐시 hit은 리미터를 거치지 않는다")
    class CacheInteraction {

        @Test
        @DisplayName("같은 검색어를 반복하면 외부 호출도, 리미터 대기도 없다")
        void cacheHitSkipsLimiter() {
            // 이번에는 캐시를 켠다.
            List<Long> callTimes = java.util.Collections.synchronizedList(new ArrayList<>());
            RestTemplate restTemplate = mock(RestTemplate.class);
            when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                    .thenAnswer(inv -> {
                        callTimes.add(System.nanoTime() / 1_000_000);
                        return ResponseEntity.ok(Map.of("items", List.of()));
                    });
            NaverApiClient client = new NaverApiClient(
                    restTemplate, 300L, 1000L, 1800L, 1000L, new SimpleMeterRegistry());
            ReflectionTestUtils.setField(client, "endpoint", "https://naver.test/search/v1/local");
            ReflectionTestUtils.setField(client, "clientId", "id");
            ReflectionTestUtils.setField(client, "clientSecret", "secret");
            ReflectionTestUtils.setField(client, "reverseEndpoint", "https://naver.test/gc");
            ReflectionTestUtils.setField(client, "mapClientId", "id");
            ReflectionTestUtils.setField(client, "mapClientSecret", "secret");

            long t0 = System.nanoTime();
            for (int i = 0; i < 20; i++) {
                client.searchLocal("같은검색어", 5, 1, "random");
            }
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

            assertThat(callTimes)
                    .as("20번 요청했지만 외부 호출은 1번뿐이다")
                    .hasSize(1);
            assertThat(elapsedMs)
                    .as("""
                            캐시 hit이 리미터 뒤에 있었다면 20번 × 200ms = 약 4초가 걸린다.
                            실제 %dms. 캐시가 리미터보다 앞에 있다는 뜻이다.
                            리미터는 '외부로 나가는 호출'만 제한해야 한다.""".formatted(elapsedMs))
                    .isLessThan(1000);
        }
    }
}
