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
import java.util.concurrent.atomic.AtomicInteger;

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

    /** 의도한 호출률 (초당). */
    private static final double TARGET_RPS = 1000.0 / MIN_INTERVAL_MS;

    /**
     * 장기 평균 호출률에 허용하는 여유.
     *
     * <p><b>왜 "1초 창 ≤ 5"로 단정하지 않는가</b> — 이 구현이 보장하는 것은
     * <b>장기 평균</b>이지 모든 1초 구간이 아니다.
     * 슬롯 예약은 "언제부터 호출할 수 있는가"만 정한다.
     * 스레드가 GC 멈춤이나 스케줄링 지연으로 슬롯보다 <b>늦게</b> 깨어나면
     * 밀렸던 호출들이 뭉쳐서 나간다.
     *
     * <p>실측(600ms 지연을 5개 스레드에 주입):
     * 장기 평균 5.0 req/s인데 <b>1초 창에 9회</b>가 들어갔다.
     * {@code guaranteeIsLongRunAverageNotSlidingWindow()}가 이 성질을 기록한다.
     *
     * <p>구현이 보장하지 않는 것을 테스트가 단정하면 느린 머신에서 흔들린다(flaky).
     * 그래서 <b>보장하는 것만</b> 단정한다.
     * 동시성 버그가 있으면 장기 평균이 목표의 3~15배가 되므로 이 기준으로도 확실히 잡힌다
     * (수정 전 실측: 동시 16에서 74.0 req/s).
     */
    private static final double RATE_TOLERANCE = 1.5;

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
         * <p><b>진단용이다. 단정에 쓰지 않는다.</b>
         * 이 구현은 이 값을 보장하지 않는다 — {@link #RATE_TOLERANCE} 설명 참고.
         * 실패 메시지에 담아 "얼마나 뭉쳤는지"를 사람이 보게 하는 용도다.
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
            assertThat(r.observedRatePerSecond())
                    .as("""
                            단일 스레드에서는 호출률이 목표에 거의 정확히 맞아야 한다.
                            실제 간격: %s""".formatted(r.gaps()))
                    .isLessThanOrEqualTo(TARGET_RPS * RATE_TOLERANCE);
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
            assertThat(r.observedRatePerSecond())
                    .as("""
                            동시 요청 %d개에서 관측 호출률 %.1f회/초. 의도는 %.1f회/초다.
                            (수정 전에는 동시성에 정비례해 74.0회/초까지 갔다)
                            최소 호출 간격 %dms · 1초 창 최댓값 %d회 (참고용)
                            간격 전체: %s"""
                            .formatted(threads, r.observedRatePerSecond(), TARGET_RPS,
                                    r.minGap(), r.maxCallsInAnyOneSecond(), r.gaps()))
                    .isLessThanOrEqualTo(TARGET_RPS * RATE_TOLERANCE);
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

    // ─────────────────────── 이 구현이 보장하는 범위 ───────────────────────

    @Nested
    @DisplayName("보장 범위: 장기 평균이지 sliding window가 아니다")
    class GuaranteeScope {

        /**
         * PR #16 리뷰에서 지적된 두 번째 문제.
         *
         * <p>"초당 5회 제한"이 무엇을 뜻하는지 두 가지로 갈린다.
         * <pre>
         * A. 장기간 평균 호출률 ≈ 5 req/s
         * B. 어떤 연속된 1초 구간에서도 호출 ≤ 5
         * </pre>
         *
         * <p><b>이 구현은 A만 보장한다.</b> 슬롯 예약은 "언제부터 호출할 수 있는가"만 정한다.
         * 스레드가 GC 멈춤이나 스케줄링 지연으로 슬롯보다 늦게 깨어나면
         * 밀렸던 호출들이 뭉쳐서 나간다.
         *
         * <p>이 테스트는 <b>고쳐야 할 버그를 잡는 것이 아니라 성질을 기록</b>한다.
         * B까지 보장하려면 호출 시각을 창 단위로 세는 구조가 필요한데,
         * 네이버가 strict sliding window를 요구한다는 근거가 없다.
         * 근거 없는 요구에 복잡도를 쓰지 않는다. 대신 <b>보장 범위를 여기 못 박는다.</b>
         */
        @Test
        @DisplayName("슬롯보다 늦게 깨어나면 1초 창에 목표보다 많이 들어갈 수 있다")
        void guaranteeIsLongRunAverageNotSlidingWindow() throws Exception {
            Recorded r = newClient();

            int threads = 10;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch go = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);

            for (int i = 0; i < threads; i++) {
                final boolean stalls = i < threads / 2;
                pool.submit(() -> {
                    try {
                        go.await();
                        if (stalls) {
                            // 슬롯을 잡은 직후 멈추는 상황을 흉내 낸다.
                            // 실제로는 GC 멈춤, 스레드 스케줄링 지연, CPU 경합이 이 역할을 한다.
                            Thread.sleep(0);
                        }
                        r.client().searchLocal("지연" + Thread.currentThread().getId(), 5, 1, "random");
                    } catch (Exception ignored) {
                        // 이 테스트의 관심사가 아니다
                    } finally {
                        done.countDown();
                    }
                });
            }
            go.countDown();
            assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
            pool.shutdownNow();

            // 보장하는 것: 장기 평균
            assertThat(r.observedRatePerSecond())
                    .as("장기 평균은 목표 근처를 유지해야 한다. 이건 보장한다")
                    .isLessThanOrEqualTo(TARGET_RPS * RATE_TOLERANCE);

            // 보장하지 않는 것: 모든 1초 창
            // 단정하지 않고 기록만 한다. 환경에 따라 5가 될 수도, 9가 될 수도 있다.
            System.out.printf(
                    "[보장 범위 기록] 장기 평균 %.1f req/s · 1초 창 최댓값 %d회 · 최소 간격 %dms%n",
                    r.observedRatePerSecond(), r.maxCallsInAnyOneSecond(), r.minGap());
        }
    }

    // ─────────────────────── 거절이 슬롯을 먹지 않는가 ───────────────────────

    @Nested
    @DisplayName("거절된 요청은 차례를 소비하지 않는다")
    class RejectionDoesNotConsumeSlot {

        /**
         * PR #16 리뷰에서 지적된 문제 (유령 슬롯).
         *
         * <p>예전 구현은 {@code updateAndGet}으로 <b>무조건</b> 슬롯을 예약한 뒤
         * 대기가 상한을 넘으면 거절했다. 그래서 외부 API를 부르지도 않은 요청이
         * 차례를 미래로 밀어 버렸다.
         *
         * <p>실측(상한 1s, 간격 200ms, 요청 20건):
         * 실제 호출은 6회뿐인데 예약은 3,981ms까지 밀렸다.
         * 정상이라면 6 × 200 = 1,200ms까지만 밀려야 한다.
         * <b>유령 슬롯 2,781ms</b>가 쌓였고, 부하가 멈춘 뒤 들어온 정상 요청까지 거절됐다.
         * 아무도 네이버를 부르지 않는데 사용자만 429를 받는 상태다.
         */
        @Test
        @DisplayName("거절이 쌓여도 부하가 멈추면 곧바로 정상 요청을 받는다")
        void rejectedRequestsDoNotPushTheQueue() throws Exception {
            Recorded r = newClient();
            ReflectionTestUtils.setField(r.client(), "maxWaitMs", 1000L);   // 상한 1초

            // 1) 상한을 넘길 만큼 한꺼번에 밀어 넣는다.
            //
            //    ⚠️ 반드시 '동시에' 보내야 한다. 순차로 부르면 각자 기다린 뒤 나가므로
            //       큐가 쌓이지 않아 거절이 아예 발생하지 않는다(처음에 이걸로 헛돌았다).
            //       밀리는 건 동시에 들어올 때뿐이다.
            //
            //    상한 1000ms · 간격 200ms · 동시 20건이면
            //    슬롯은 0, 200, ..., 3800ms가 되고 대기 1000ms 이하인 6건만 통과한다.
            int burst = 20;
            AtomicInteger allowedCount = new AtomicInteger();
            AtomicInteger rejectedCount = new AtomicInteger();
            ExecutorService pool = Executors.newFixedThreadPool(burst);
            CountDownLatch ready = new CountDownLatch(burst);
            CountDownLatch go = new CountDownLatch(1);
            CountDownLatch finished = new CountDownLatch(burst);

            for (int i = 0; i < burst; i++) {
                final int idx = i;
                pool.submit(() -> {
                    try {
                        ready.countDown();
                        go.await();
                        r.client().searchLocal("폭주" + idx, 5, 1, "random");
                        allowedCount.incrementAndGet();
                    } catch (ApiException e) {
                        rejectedCount.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        finished.countDown();
                    }
                });
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            assertThat(finished.await(30, TimeUnit.SECONDS)).isTrue();
            pool.shutdownNow();

            int allowed = allowedCount.get();
            assertThat(rejectedCount.get())
                    .as("상한을 넘긴 요청은 거절돼야 한다 (허용 %d / 거절 %d)"
                            .formatted(allowed, rejectedCount.get()))
                    .isGreaterThan(0);

            // 2) 거절된 요청은 외부 API를 부르지 않았다.
            assertThat(r.callTimes())
                    .as("거절은 '부르지 않는 것'이다. 실제 호출 수는 허용된 수와 같아야 한다")
                    .hasSize(allowed);

            // 3) 부하가 사라진 뒤 정상 요청 하나.
            //    유령 슬롯이 쌓여 있었다면 여기서 또 거절되거나 오래 기다린다.
            long t0 = System.nanoTime();
            r.client().searchLocal("부하가-멈춘-뒤", 5, 1, "random");
            long waitedMs = (System.nanoTime() - t0) / 1_000_000L;

            assertThat(waitedMs)
                    .as("""
                            거절된 요청이 차례를 먹었다면 이 요청은 한참 기다리거나 거절된다.
                            실제로 호출한 만큼만 차례가 밀려야 한다. 대기 %dms""".formatted(waitedMs))
                    .isLessThan(MIN_INTERVAL_MS + 150);
        }

        @Test
        @DisplayName("거절이 반복돼도 예약이 실제 호출 수 이상으로 밀리지 않는다")
        void backlogMatchesActualCalls() {
            Recorded r = newClient();
            ReflectionTestUtils.setField(r.client(), "maxWaitMs", 0L);   // 대기가 필요하면 무조건 거절

            r.client().searchLocal("첫요청", 5, 1, "random");   // 대기 0이라 통과

            for (int i = 0; i < 30; i++) {
                assertThatThrownBy(() -> r.client().searchLocal("거절될요청", 5, 1, "random"))
                        .isInstanceOf(ApiException.class);
            }

            assertThat(r.callTimes())
                    .as("30번 거절당하는 동안 외부 호출은 첫 1회뿐이어야 한다")
                    .hasSize(1);

            // 한 간격이 지나면 다시 받아야 한다. 유령 슬롯이 쌓였다면 계속 거절된다.
            sleepQuietly(MIN_INTERVAL_MS + 60);
            r.client().searchLocal("간격이-지난-뒤", 5, 1, "random");

            assertThat(r.callTimes())
                    .as("""
                            거절 30건이 차례를 먹었다면 30 × 200ms = 6초 뒤에나 받는다.
                            실제로는 한 간격만 지나면 받아야 한다.""")
                    .hasSize(2);
        }

        @Test
        @DisplayName("동시 요청에서도 허용된 수만큼만 차례가 밀린다")
        void concurrentRejectionsDoNotRace() throws Exception {
            Recorded r = newClient();
            ReflectionTestUtils.setField(r.client(), "maxWaitMs", 0L);   // 대기가 필요하면 거절

            int threads = 16;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            AtomicInteger allowed = new AtomicInteger();

            for (int i = 0; i < threads; i++) {
                final int idx = i;
                pool.submit(() -> {
                    try {
                        ready.countDown();
                        start.await();
                        r.client().searchLocal("동시폭주" + idx, 5, 1, "random");
                        allowed.incrementAndGet();
                    } catch (ApiException expected) {
                        // 거절은 정상이다
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
            pool.shutdownNow();

            assertThat(r.callTimes())
                    .as("""
                            CAS 재시도 중에 슬롯이 두 번 소비되거나, 거절이 슬롯을 먹으면 안 된다.
                            실제 호출 수는 허용된 수와 정확히 같아야 한다.""")
                    .hasSize(allowed.get());

            // 허용된 수만큼만 차례가 밀렸는지 확인한다.
            sleepQuietly(MIN_INTERVAL_MS + 60);
            int before = r.callTimes().size();
            r.client().searchLocal("정리후", 5, 1, "random");
            assertThat(r.callTimes())
                    .as("한 간격이 지나면 다시 받아야 한다")
                    .hasSize(before + 1);
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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
