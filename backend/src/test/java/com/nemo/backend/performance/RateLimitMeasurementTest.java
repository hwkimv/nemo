package com.nemo.backend.performance;

import com.nemo.backend.domain.map.util.NaverApiClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 동시성별 외부 API 호출률을 재서 표로 출력한다.
 *
 * <p>Before/After를 <b>같은 코드로</b> 재기 위한 도구다.
 * 수정 전후로 이 태스크를 각각 돌려 같은 지표를 비교한다.
 *
 * <pre>
 * ./gradlew rateLimitMeasurement
 * </pre>
 *
 * <p>일반 {@code test}에서는 제외한다. 수십 초가 걸리고 시간에 민감해
 * CI에서 돌리면 불안정하다. 정확성 검증은 {@code NaverApiRateLimitTest}가 맡는다.
 */
@Tag("rate-limit-measurement")
@DisplayName("레이트 리미터 동시성별 측정")
class RateLimitMeasurementTest {

    /** 코드에 박혀 있는 최소 간격. 초당 5회를 의도한 값이다. */
    private static final long MIN_INTERVAL_MS = 200;
    private static final double TARGET_RPS = 1000.0 / MIN_INTERVAL_MS;

    /** 스레드 하나가 보내는 외부 호출 수. 뷰포트 1회가 만드는 호출 수와 맞췄다. */
    private static final int CALLS_PER_THREAD = 10;

    private static final int[] CONCURRENCY = {1, 4, 8, 16};

    @Test
    @DisplayName("동시성 1 / 4 / 8 / 16")
    void measure() throws Exception {
        System.out.println();
        System.out.println("RATE_LIMIT_MEASUREMENT_HEADER "
                + "concurrency total_calls duration_ms observed_rps target_rps "
                + "avg_gap_ms min_gap_ms p95_latency_ms max_latency_ms failures");

        for (int threads : CONCURRENCY) {
            Result r = run(threads);
            System.out.printf(
                    "RATE_LIMIT_MEASUREMENT concurrency=%d total_calls=%d duration_ms=%d "
                            + "observed_rps=%.1f target_rps=%.1f avg_gap_ms=%.1f min_gap_ms=%d "
                            + "p95_latency_ms=%d max_latency_ms=%d failures=%d%n",
                    threads, r.totalCalls, r.durationMs, r.observedRps, TARGET_RPS,
                    r.avgGapMs, r.minGapMs, r.p95LatencyMs, r.maxLatencyMs, r.failures);
        }
        System.out.println();
    }

    private record Result(int totalCalls, long durationMs, double observedRps,
                          double avgGapMs, long minGapMs,
                          long p95LatencyMs, long maxLatencyMs, int failures) {
    }

    private Result run(int threads) throws Exception {
        List<Long> callTimes = Collections.synchronizedList(new ArrayList<>());
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger failures = new AtomicInteger();

        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenAnswer(inv -> {
                    callTimes.add(System.nanoTime() / 1_000_000);
                    return ResponseEntity.ok(Map.of("items", List.of()));
                });

        // 캐시를 끈다. 켜면 hit이 되어 외부로 나가지 않고, 그러면 호출률을 잴 수 없다.
        NaverApiClient client = new NaverApiClient(
                restTemplate, 0L, 1000L, 0L, 1000L, new SimpleMeterRegistry());
        ReflectionTestUtils.setField(client, "endpoint", "https://naver.test/search/v1/local");
        ReflectionTestUtils.setField(client, "clientId", "id");
        ReflectionTestUtils.setField(client, "clientSecret", "secret");
        ReflectionTestUtils.setField(client, "reverseEndpoint", "https://naver.test/gc");
        ReflectionTestUtils.setField(client, "mapClientId", "id");
        ReflectionTestUtils.setField(client, "mapClientSecret", "secret");
        ReflectionTestUtils.setField(client, "minIntervalMs", MIN_INTERVAL_MS);
        // 대기 상한에 걸려 거절되면 호출 수가 줄어 Before/After 비교가 깨진다.
        // 여기서 재려는 것은 거절 정책이 아니라 호출률이다.
        ReflectionTestUtils.setField(client, "maxWaitMs", 600_000L);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            final int tid = t;
            pool.submit(() -> {
                try {
                    ready.countDown();
                    start.await();
                    long t0 = System.nanoTime();
                    for (int i = 0; i < CALLS_PER_THREAD; i++) {
                        try {
                            client.searchLocal("t" + tid + "-q" + i, 5, 1, "random");
                        } catch (Exception e) {
                            failures.incrementAndGet();
                        }
                    }
                    latencies.add((System.nanoTime() - t0) / 1_000_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await(30, TimeUnit.SECONDS);
        long wallStart = System.nanoTime();
        start.countDown();
        done.await(10, TimeUnit.MINUTES);
        long durationMs = (System.nanoTime() - wallStart) / 1_000_000;
        pool.shutdownNow();

        List<Long> sorted = new ArrayList<>(callTimes);
        Collections.sort(sorted);

        List<Long> gaps = new ArrayList<>();
        for (int i = 1; i < sorted.size(); i++) {
            gaps.add(sorted.get(i) - sorted.get(i - 1));
        }

        long spanMs = sorted.isEmpty() ? 0 : sorted.get(sorted.size() - 1) - sorted.get(0);
        // 첫 호출은 대기 없이 나가므로 간격 개수(n-1) 기준으로 센다.
        double observedRps = spanMs > 0 ? (sorted.size() - 1) * 1000.0 / spanMs : 0;

        double avgGap = gaps.stream().mapToLong(Long::longValue).average().orElse(0);
        long minGap = gaps.stream().mapToLong(Long::longValue).min().orElse(0);

        List<Long> lat = new ArrayList<>(latencies);
        Collections.sort(lat);
        long p95 = lat.isEmpty() ? 0 : lat.get(Math.min(lat.size() - 1, (int) Math.ceil(lat.size() * 0.95) - 1));
        long max = lat.isEmpty() ? 0 : lat.get(lat.size() - 1);

        return new Result(sorted.size(), durationMs, observedRps, avgGap, minGap, p95, max, failures.get());
    }
}
