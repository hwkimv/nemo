package com.nemo.backend.domain.map.util;

import com.github.benmanes.caffeine.cache.Ticker;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 지도 API 로컬 캐시의 동작을 코드로 고정한다.
 *
 * <h3>구조 (NaverApiClient)</h3>
 * 캐시가 <b>용도별로 2개</b>다. 예전에는 하나였다.
 * <pre>
 *   local-search       key = Local Search 요청 URI       TTL 기본 300초 (5분)
 *   reverse-geocoding  key = Reverse Geocode 요청 URI    TTL 기본 1800초 (30분)
 * </pre>
 * 둘 다 Caffeine이고 {@code expireAfterWrite} + {@code maximumSize} + {@code recordStats}를 쓴다.
 * TTL이 0이면 <b>그 캐시만</b> 꺼진다.
 *
 * <h3>왜 나눴나</h3>
 * 업체 검색 결과는 폐업·신규 오픈으로 바뀔 수 있고, 좌표→행정구역은 거의 안 바뀐다.
 * 하나의 TTL로는 한쪽에 맞추면 다른 쪽이 손해였다.
 *
 * <h3>시간을 다루는 방법</h3>
 * TTL 만료를 실제로 5분 기다릴 수는 없다. Caffeine에 <b>가짜 시계</b>({@link FakeTicker})를 끼워
 * 시간을 앞으로 밀어서 만료를 재현한다. {@code Thread.sleep}이 없으므로 테스트가 흔들리지 않는다.
 *
 * 외부 호출 횟수는 가짜 RestTemplate이 직접 센다. 실제 네이버 API는 부르지 않는다.
 */
@DisplayName("지도 API 로컬 캐시 (Local Search / Reverse Geocoding 분리)")
class NaverApiCacheTest {

    private static final long DEFAULT_LOCAL_TTL = 300;      // 5분
    private static final long DEFAULT_REVERSE_TTL = 1800;   // 30분
    private static final long DEFAULT_MAX_SIZE = 1000;

    private NaverApiClient client;
    private MeterRegistry meterRegistry;
    private FakeTicker ticker;

    /** Local Search 호출 수 */
    private AtomicInteger localCalls;
    /** Reverse Geocoding 호출 수 */
    private AtomicInteger reverseCalls;

    @BeforeEach
    void setUp() {
        newClient(DEFAULT_LOCAL_TTL, DEFAULT_MAX_SIZE, DEFAULT_REVERSE_TTL, DEFAULT_MAX_SIZE);
    }

    // ─────────────────────────── Local Search ───────────────────────────

    @Nested
    @DisplayName("Local Search 캐시")
    class LocalSearch {

        @Test
        @DisplayName("같은 URI를 다시 조회하면 hit이다 — 10번 요청, 외부 호출 1번")
        void sameUriHits() {
            for (int i = 0; i < 10; i++) {
                client.searchLocal("강남구 인생네컷", 5, 1, "random");
            }

            assertThat(localCalls.get())
                    .as("첫 요청만 miss고 나머지 9번은 캐시에서 나와야 한다")
                    .isEqualTo(1);

            CacheStats stats = client.localSearchCache().stats();
            assertThat(stats.missCount()).isEqualTo(1);
            assertThat(stats.hitCount()).isEqualTo(9);
            assertThat(client.localSearchCache().size()).isEqualTo(1);
        }

        @Test
        @DisplayName("TTL 300초가 지나면 다시 miss다 — 외부 호출 총 2번")
        void expiresAfterTtl() {
            client.searchLocal("강남구 인생네컷", 5, 1, "random");
            assertThat(localCalls.get()).isEqualTo(1);

            // 299초: 아직 살아 있다
            ticker.advance(Duration.ofSeconds(299));
            client.searchLocal("강남구 인생네컷", 5, 1, "random");
            assertThat(localCalls.get())
                    .as("TTL 이내면 외부 호출이 늘지 않는다")
                    .isEqualTo(1);

            // 301초: 만료
            ticker.advance(Duration.ofSeconds(2));
            client.searchLocal("강남구 인생네컷", 5, 1, "random");
            assertThat(localCalls.get())
                    .as("expireAfterWrite 300초가 지나면 다시 외부로 나간다")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("읽어도 수명이 늘지 않는다 (expireAfterAccess를 쓰지 않는 이유)")
        void readingDoesNotExtendLifetime() {
            client.searchLocal("강남구 인생네컷", 5, 1, "random");

            // 100초마다 계속 읽는다. expireAfterAccess였다면 영원히 살아남는다.
            for (int i = 0; i < 3; i++) {
                ticker.advance(Duration.ofSeconds(100));
                client.searchLocal("강남구 인생네컷", 5, 1, "random");
            }

            assertThat(localCalls.get())
                    .as("""
                            300초를 넘긴 시점(총 300초)에서 다시 조회되어야 한다.
                            자주 읽힌다고 오래된 외부 응답을 계속 들고 있으면 안 된다.""")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("TTL=0이면 캐시가 꺼진다 — 매번 외부 호출, 저장도 하지 않음")
        void ttlZeroDisables() {
            newClient(0, DEFAULT_MAX_SIZE, DEFAULT_REVERSE_TTL, DEFAULT_MAX_SIZE);

            for (int i = 0; i < 10; i++) {
                client.searchLocal("강남구 인생네컷", 5, 1, "random");
            }

            assertThat(localCalls.get()).isEqualTo(10);
            assertThat(client.localSearchCache().isEnabled()).isFalse();
            assertThat(client.localSearchCache().size())
                    .as("꺼진 캐시는 메모리를 쓰지 않는다")
                    .isZero();
        }

        @Test
        @DisplayName("maximumSize를 넘으면 밀려난다(eviction)")
        void evictsOverMaximumSize() {
            newClient(DEFAULT_LOCAL_TTL, 3, DEFAULT_REVERSE_TTL, DEFAULT_MAX_SIZE);

            for (int i = 0; i < 10; i++) {
                client.searchLocal("검색어" + i, 5, 1, "random");
            }

            assertThat(client.localSearchCache().size())
                    .as("상한 3을 넘게 담지 않는다")
                    .isLessThanOrEqualTo(3);
            assertThat(client.localSearchCache().stats().evictionCount())
                    .as("10개를 넣고 상한이 3이면 7개가 밀려나야 한다")
                    .isEqualTo(7);
        }

        @Test
        @DisplayName("캐시 키는 URI 전체다 — 파라미터가 하나라도 다르면 다른 항목이다")
        void keyIsWholeUri() {
            client.searchLocal("강남구 인생네컷", 5, 1, "random");   // miss
            client.searchLocal("강남구 인생네컷", 5, 6, "random");   // start 다름 → miss
            client.searchLocal("강남구 인생네컷", 5, 1, "comment");  // sort 다름 → miss
            client.searchLocal("강남구 포토이즘", 5, 1, "random");   // query 다름 → miss
            client.searchLocal("강남구 인생네컷", 5, 1, "random");   // 첫 번째와 동일 → hit

            assertThat(localCalls.get()).isEqualTo(4);
            assertThat(client.localSearchCache().size()).isEqualTo(4);
            assertThat(client.localSearchCache().stats().hitCount()).isEqualTo(1);
        }
    }

    // ────────────────────────── Reverse Geocoding ──────────────────────────

    @Nested
    @DisplayName("Reverse Geocoding 캐시")
    class ReverseGeocoding {

        @Test
        @DisplayName("같은 좌표를 다시 조회하면 hit이다 — 10번 요청, 외부 호출 1번")
        void sameUriHits() {
            for (int i = 0; i < 10; i++) {
                client.reverseGeocodeToRegion(37.4979, 127.0276);
            }

            assertThat(reverseCalls.get()).isEqualTo(1);

            CacheStats stats = client.reverseGeocodeCache().stats();
            assertThat(stats.missCount()).isEqualTo(1);
            assertThat(stats.hitCount()).isEqualTo(9);
            assertThat(client.reverseGeocodeCache().size()).isEqualTo(1);
        }

        @Test
        @DisplayName("캐시에서 꺼낸 응답도 같은 지역명을 준다 (가공 결과가 바뀌지 않는다)")
        void cachedValueYieldsSameRegion() {
            String first = client.reverseGeocodeToRegion(37.4979, 127.0276).orElseThrow();
            String cached = client.reverseGeocodeToRegion(37.4979, 127.0276).orElseThrow();

            assertThat(cached)
                    .as("캐시는 원본 응답을 담는다. 지역명 추출 결과는 동일해야 한다")
                    .isEqualTo(first)
                    .isEqualTo("강남구 역삼동");
        }

        @Test
        @DisplayName("TTL 1800초가 지나면 다시 miss다 — 외부 호출 총 2번")
        void expiresAfterTtl() {
            client.reverseGeocodeToRegion(37.4979, 127.0276);
            assertThat(reverseCalls.get()).isEqualTo(1);

            // 1799초: 아직 살아 있다. Local Search라면 이미 만료됐을 시간이다.
            ticker.advance(Duration.ofSeconds(1799));
            client.reverseGeocodeToRegion(37.4979, 127.0276);
            assertThat(reverseCalls.get())
                    .as("30분 TTL이므로 5분이 훨씬 지나도 살아 있어야 한다")
                    .isEqualTo(1);

            ticker.advance(Duration.ofSeconds(2));
            client.reverseGeocodeToRegion(37.4979, 127.0276);
            assertThat(reverseCalls.get()).isEqualTo(2);
        }

        @Test
        @DisplayName("TTL=0이면 캐시가 꺼진다 — 매번 외부 호출, 저장도 하지 않음")
        void ttlZeroDisables() {
            newClient(DEFAULT_LOCAL_TTL, DEFAULT_MAX_SIZE, 0, DEFAULT_MAX_SIZE);

            for (int i = 0; i < 10; i++) {
                client.reverseGeocodeToRegion(37.4979, 127.0276);
            }

            assertThat(reverseCalls.get()).isEqualTo(10);
            assertThat(client.reverseGeocodeCache().isEnabled()).isFalse();
            assertThat(client.reverseGeocodeCache().size()).isZero();
        }

        @Test
        @DisplayName("maximumSize를 넘으면 밀려난다(eviction)")
        void evictsOverMaximumSize() {
            newClient(DEFAULT_LOCAL_TTL, DEFAULT_MAX_SIZE, DEFAULT_REVERSE_TTL, 3);

            // 좌표가 곧 캐시 키다. 지도를 움직이면 좌표가 계속 달라져 항목이 늘어난다.
            for (int i = 0; i < 10; i++) {
                client.reverseGeocodeToRegion(37.4979 + i * 0.001, 127.0276);
            }

            assertThat(client.reverseGeocodeCache().size()).isLessThanOrEqualTo(3);
            assertThat(client.reverseGeocodeCache().stats().evictionCount()).isEqualTo(7);
        }
    }

    // ─────────────────────────── 캐시 독립성 ───────────────────────────

    @Nested
    @DisplayName("두 캐시는 서로 독립이다")
    class Independence {

        @Test
        @DisplayName("Local Search를 꺼도 Reverse Geocoding은 정상 동작한다")
        void localOffReverseStillWorks() {
            newClient(0, DEFAULT_MAX_SIZE, DEFAULT_REVERSE_TTL, DEFAULT_MAX_SIZE);

            for (int i = 0; i < 5; i++) {
                client.searchLocal("강남구 인생네컷", 5, 1, "random");
                client.reverseGeocodeToRegion(37.4979, 127.0276);
            }

            assertThat(localCalls.get())
                    .as("Local Search 캐시가 꺼졌으므로 5번 다 나간다")
                    .isEqualTo(5);
            assertThat(reverseCalls.get())
                    .as("Reverse Geocoding은 영향을 받지 않는다")
                    .isEqualTo(1);
            assertThat(client.reverseGeocodeCache().stats().hitCount()).isEqualTo(4);
        }

        @Test
        @DisplayName("Reverse Geocoding을 꺼도 Local Search는 정상 동작한다")
        void reverseOffLocalStillWorks() {
            newClient(DEFAULT_LOCAL_TTL, DEFAULT_MAX_SIZE, 0, DEFAULT_MAX_SIZE);

            for (int i = 0; i < 5; i++) {
                client.searchLocal("강남구 인생네컷", 5, 1, "random");
                client.reverseGeocodeToRegion(37.4979, 127.0276);
            }

            assertThat(localCalls.get()).isEqualTo(1);
            assertThat(reverseCalls.get()).isEqualTo(5);
            assertThat(client.localSearchCache().stats().hitCount()).isEqualTo(4);
        }

        @Test
        @DisplayName("한쪽의 hit/miss가 다른 쪽 통계에 섞이지 않는다")
        void statsDoNotMix() {
            // Local: miss 1 + hit 2
            client.searchLocal("강남구 인생네컷", 5, 1, "random");
            client.searchLocal("강남구 인생네컷", 5, 1, "random");
            client.searchLocal("강남구 인생네컷", 5, 1, "random");

            // Reverse: miss 2 + hit 1
            client.reverseGeocodeToRegion(37.4979, 127.0276);
            client.reverseGeocodeToRegion(37.5000, 127.0300);
            client.reverseGeocodeToRegion(37.4979, 127.0276);

            CacheStats local = client.localSearchCache().stats();
            CacheStats reverse = client.reverseGeocodeCache().stats();

            assertThat(local.missCount()).isEqualTo(1);
            assertThat(local.hitCount()).isEqualTo(2);
            assertThat(reverse.missCount()).isEqualTo(2);
            assertThat(reverse.hitCount()).isEqualTo(1);

            assertThat(client.localSearchCache().size()).isEqualTo(1);
            assertThat(client.reverseGeocodeCache().size()).isEqualTo(2);
        }

        @Test
        @DisplayName("TTL이 다르므로 만료 시점도 다르다 — 5분 뒤 Local만 죽는다")
        void differentTtlsExpireIndependently() {
            client.searchLocal("강남구 인생네컷", 5, 1, "random");
            client.reverseGeocodeToRegion(37.4979, 127.0276);
            assertThat(localCalls.get()).isEqualTo(1);
            assertThat(reverseCalls.get()).isEqualTo(1);

            // 10분 경과: Local(5분)은 만료, Reverse(30분)는 생존
            ticker.advance(Duration.ofMinutes(10));

            client.searchLocal("강남구 인생네컷", 5, 1, "random");
            client.reverseGeocodeToRegion(37.4979, 127.0276);

            assertThat(localCalls.get())
                    .as("Local Search는 5분 TTL이라 만료됐다")
                    .isEqualTo(2);
            assertThat(reverseCalls.get())
                    .as("Reverse Geocoding은 30분 TTL이라 아직 살아 있다. 이게 분리의 이유다")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("clearCache()는 두 캐시를 모두 비운다")
        void clearEmptiesBoth() {
            client.searchLocal("강남구 인생네컷", 5, 1, "random");
            client.reverseGeocodeToRegion(37.4979, 127.0276);
            assertThat(client.cacheSize()).isEqualTo(2);

            client.clearCache();

            assertThat(client.localSearchCache().size()).isZero();
            assertThat(client.reverseGeocodeCache().size()).isZero();
            assertThat(client.cacheSize()).isZero();
        }
    }

    // ─────────────────────────── Micrometer 노출 ───────────────────────────

    @Nested
    @DisplayName("캐시 지표가 Micrometer로 나간다")
    class Metrics {

        @Test
        @DisplayName("두 캐시가 cache 태그로 구분되어 등록된다")
        void bothCachesAreRegistered() {
            client.searchLocal("강남구 인생네컷", 5, 1, "random");
            client.reverseGeocodeToRegion(37.4979, 127.0276);

            List<String> cacheNames = meterRegistry.find("cache.size").gauges().stream()
                    .map(g -> g.getId().getTag("cache"))
                    .sorted()
                    .toList();

            assertThat(cacheNames)
                    .as("Grafana에서 두 캐시를 따로 봐야 하므로 이름이 태그로 붙어야 한다")
                    .containsExactly("local-search", "reverse-geocoding");
        }

        @Test
        @DisplayName("hit / miss가 result 태그로 나뉘어 카운트된다")
        void hitAndMissAreExported() {
            client.searchLocal("강남구 인생네컷", 5, 1, "random");   // miss
            client.searchLocal("강남구 인생네컷", 5, 1, "random");   // hit
            client.searchLocal("강남구 인생네컷", 5, 1, "random");   // hit

            double hits = meterRegistry.get("cache.gets")
                    .tags("cache", "local-search", "result", "hit").functionCounter().count();
            double misses = meterRegistry.get("cache.gets")
                    .tags("cache", "local-search", "result", "miss").functionCounter().count();

            assertThat(hits).isEqualTo(2);
            assertThat(misses).isEqualTo(1);
        }

        @Test
        @DisplayName("eviction도 지표로 나간다")
        void evictionsAreExported() {
            newClient(DEFAULT_LOCAL_TTL, 3, DEFAULT_REVERSE_TTL, DEFAULT_MAX_SIZE);

            for (int i = 0; i < 10; i++) {
                client.searchLocal("검색어" + i, 5, 1, "random");
            }
            client.localSearchCache().size();   // Caffeine에 정리를 시켜 카운터를 확정한다

            double evictions = meterRegistry.get("cache.evictions")
                    .tag("cache", "local-search").functionCounter().count();

            assertThat(evictions)
                    .as("maximumSize를 넘겨 밀려난 수가 Grafana에 보여야 한다")
                    .isEqualTo(7);
        }

        @Test
        @DisplayName("꺼진 캐시는 지표를 등록하지 않는다")
        void disabledCacheIsNotRegistered() {
            newClient(0, DEFAULT_MAX_SIZE, DEFAULT_REVERSE_TTL, DEFAULT_MAX_SIZE);

            List<String> cacheNames = meterRegistry.find("cache.size").gauges().stream()
                    .map(g -> g.getId().getTag("cache"))
                    .toList();

            assertThat(cacheNames)
                    .as("시계열이 사라지는 것 자체가 '이 캐시는 꺼져 있다'는 신호다")
                    .containsExactly("reverse-geocoding");
        }
    }

    @Nested
    @DisplayName("잘못된 설정은 시작할 때 막는다")
    class InvalidConfiguration {

        @Test
        @DisplayName("TTL이 음수면 뜨지 않는다 — 0(OFF)과 실수를 구분해야 한다")
        void negativeTtlIsRejected() {
            assertThatThrownBy(() -> newClient(-1, DEFAULT_MAX_SIZE, DEFAULT_REVERSE_TTL, DEFAULT_MAX_SIZE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("local-search");
        }

        @Test
        @DisplayName("maximumSize가 0이면 뜨지 않는다 — 넣는 족족 버려져 항상 miss가 된다")
        void zeroMaximumSizeIsRejected() {
            assertThatThrownBy(() -> newClient(DEFAULT_LOCAL_TTL, DEFAULT_MAX_SIZE, DEFAULT_REVERSE_TTL, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("reverse-geocoding");
        }
    }

    // ─────────────────────────── 도우미 ───────────────────────────

    /**
     * 테스트용 클라이언트를 새로 만든다.
     *
     * 캐시 설정은 생성자로 넘긴다. 운영에서는 스프링이 @Value로 같은 자리를 채운다.
     */
    private void newClient(long localTtl, long localMax, long reverseTtl, long reverseMax) {
        localCalls = new AtomicInteger();
        reverseCalls = new AtomicInteger();
        meterRegistry = new SimpleMeterRegistry();
        ticker = new FakeTicker();

        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenAnswer(invocation -> {
                    URI uri = invocation.getArgument(0);
                    if (uri.toString().contains("reversegeocode")) {
                        reverseCalls.incrementAndGet();
                        return ResponseEntity.ok(reverseBody());
                    }
                    localCalls.incrementAndGet();
                    return ResponseEntity.ok(localBody());
                });

        client = new NaverApiClient(restTemplate,
                localTtl, localMax, reverseTtl, reverseMax, meterRegistry, ticker);
        // 엔드포인트·키는 @Value 필드라 생성자로 넘길 수 없다. 테스트에서만 직접 채운다.
        ReflectionTestUtils.setField(client, "endpoint", "https://naver.test/search/v1/local");
        ReflectionTestUtils.setField(client, "clientId", "test-id");
        ReflectionTestUtils.setField(client, "clientSecret", "test-secret");
        ReflectionTestUtils.setField(client, "reverseEndpoint", "https://naver.test/map-reversegeocode/v2/gc");
        ReflectionTestUtils.setField(client, "mapClientId", "test-map-id");
        ReflectionTestUtils.setField(client, "mapClientSecret", "test-map-secret");
    }

    private static Map<String, Object> localBody() {
        return Map.of("items", List.of(Map.of(
                "title", "테스트 포토부스",
                "address", "서울시 강남구",
                "mapx", "1270000000",
                "mapy", "375000000")));
    }

    private static Map<String, Object> reverseBody() {
        return Map.of("results", List.of(Map.of(
                "region", Map.of(
                        "area2", Map.of("name", "강남구"),
                        "area3", Map.of("name", "역삼동")))));
    }

    /**
     * 앞으로만 가는 가짜 시계.
     *
     * TTL 만료를 확인하려고 5분·30분을 실제로 기다릴 수는 없다.
     * Caffeine은 시간을 Ticker로 읽으므로, 여기에 이걸 끼우면 시간을 원하는 만큼 밀 수 있다.
     */
    static final class FakeTicker implements Ticker {
        private long nanos = 0;

        void advance(Duration duration) {
            nanos += duration.toNanos();
        }

        @Override
        public long read() {
            return nanos;
        }
    }
}
