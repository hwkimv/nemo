// src/main/java/com/nemo/backend/domain/map/util/NaverApiClient.java
package com.nemo.backend.domain.map.util;

import com.github.benmanes.caffeine.cache.Ticker;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
@RequiredArgsConstructor
public class NaverApiClient {

    // ───────────────────────────────────────────────────────────────
    // (1) Naver Local Search 설정
    //    - "포토부스", "인생네컷" 같은 키워드로 장소 검색할 때 사용
    // ───────────────────────────────────────────────────────────────
    // ⚠️ 2026년 네이버 검색 API가 NAVER API HUB로 이관됐다.
    //    구 경로(openapi.naver.com/v1/search/local.json)는 NCP에서 새로 발급한 키를 받지 않는다.
    //    실제로 확인: 구 경로 + X-Naver-Client-* → 401 "NID AUTH Result Invalid"
    //                 HUB 경로 + X-NCP-APIGW-*   → 200
    @Value("${naver.openapi.local.endpoint:https://naverapihub.apigw.ntruss.com/search/v1/local}")
    private String endpoint;

    @Value("${NAVER_LOCAL_CLIENT_ID}")
    private String clientId;

    @Value("${NAVER_LOCAL_CLIENT_SECRET}")
    private String clientSecret;

    // ───────────────────────────────────────────────────────────────
    // (2) Naver Reverse Geocoding 설정
    //    - 위도(lat), 경도(lng) → "강남구 역삼동" 같은 행정구역을 얻을 때 사용
    //    - NCP(Map) 쪽 키를 쓰므로 Local Search 키와 분리
    // ───────────────────────────────────────────────────────────────
    @Value("${naver.openapi.reverse.endpoint:https://maps.apigw.ntruss.com/map-reversegeocode/v2/gc}")
    private String reverseEndpoint;

    @Value("${NAVER_MAP_CLIENT_ID}")
    private String mapClientId;

    @Value("${NAVER_MAP_CLIENT_SECRET}")
    private String mapClientSecret;

    private final RestTemplate restTemplate;

    /** 캐시 지표를 Prometheus로 내보내기 위한 레지스트리 */
    private final MeterRegistry meterRegistry;

    // ───────────────────────────────────────────────────────────────
    // (A) 응답 캐시 — 용도별로 2개를 따로 둔다
    //
    //     예전에는 Local Search와 Reverse Geocoding이 ConcurrentHashMap 하나를
    //     TTL 120초로 같이 썼다. 두 데이터는 바뀌는 속도가 전혀 다르다.
    //       · 업체 검색 결과   : 폐업·신규 오픈으로 바뀔 수 있다        → 짧게
    //       · 좌표 → 행정구역 : 행정구역 개편이 아니면 안 바뀐다        → 길게
    //     하나의 TTL로는 한쪽에 맞추면 다른 쪽이 손해였다. 그래서 분리했다.
    //
    //     key는 예전과 같다: 완성된 요청 URI 문자열.
    //     같은 URI면 같은 응답이라는 전제는 그대로다. 바뀐 것은 "어디에 얼마나 담느냐"뿐이다.
    // ───────────────────────────────────────────────────────────────

    /**
     * Local Search 캐시 유효시간(초). 기본 5분.
     *
     * <p>구 환경변수 NAVER_CACHE_TTL_SECONDS 호환은 application.yml이 맡는다.
     * 여기 기본값은 yml이 없는 단위 테스트에서만 쓰인다.
     */
    @Value("${naver.cache.local-search.ttl-seconds:300}")
    private long localSearchCacheTtlSeconds;

    @Value("${naver.cache.local-search.maximum-size:1000}")
    private long localSearchCacheMaximumSize;

    /**
     * Reverse Geocoding 캐시 유효시간(초). 기본 30분.
     *
     * <p>Local Search보다 6배 길다. 같은 좌표의 주소는 훨씬 덜 바뀌기 때문이다.
     * 다만 "안 바뀐다"가 아니라 "덜 바뀐다"이므로 무기한 캐시는 쓰지 않는다.
     */
    @Value("${naver.cache.reverse-geocoding.ttl-seconds:1800}")
    private long reverseGeocodeCacheTtlSeconds;

    @Value("${naver.cache.reverse-geocoding.maximum-size:1000}")
    private long reverseGeocodeCacheMaximumSize;

    /** 테스트에서 가짜 시계를 끼워 TTL 만료를 기다리지 않고 재현하기 위한 자리 */
    private Ticker ticker = Ticker.systemTicker();

    private NaverResponseCache localSearchCache;
    private NaverResponseCache reverseGeocodeCache;

    /**
     * @Value 주입이 끝난 뒤 캐시를 만든다.
     *
     * 생성자에서 만들 수 없다. 그 시점에는 TTL·maximumSize가 아직 0이다.
     */
    @PostConstruct
    public void initCaches() {
        localSearchCache = new NaverResponseCache(
                "local-search", localSearchCacheTtlSeconds, localSearchCacheMaximumSize, ticker);
        reverseGeocodeCache = new NaverResponseCache(
                "reverse-geocoding", reverseGeocodeCacheTtlSeconds, reverseGeocodeCacheMaximumSize, ticker);

        // Prometheus로 내보내 Grafana에서 hit ratio·eviction·size를 본다.
        localSearchCache.bindTo(meterRegistry);
        reverseGeocodeCache.bindTo(meterRegistry);

        log.info("[NAVER][CACHE] {} | {}", localSearchCache.describe(), reverseGeocodeCache.describe());
    }

    // ───────────────────────────────────────────────────────────────

    // ───────────────────────────────────────────────────────────────
    // (B) 아주 단순한 레이트 리미터: 외부 호출 사이 최소 간격 200ms 확보(초당 최대 5회)
    // ───────────────────────────────────────────────────────────────
    private static final long MIN_INTERVAL_MS = 200;
    private final AtomicLong lastCallAt = new AtomicLong(0);
    // ───────────────────────────────────────────────────────────────

    /**
     * 지역검색(Local Search) 1회 호출
     *
     * @param query   검색어 (예: "포토부스", "인생네컷")
     * @param display 한 번에 가져올 개수 (문서 기준 1~5)
     * @param start   시작 위치(1~1000) — 페이지네이션
     * @param sort    정렬("random"(정확도, 기본) / "comment"(리뷰 많은 순))
     * @return        네이버 JSON을 Map으로 그대로 반환(가공은 Service에서)
     */
    public Map<String, Object> searchLocal(String query, int display, int start, String sort) {

        // 0) 입력값 안전장치 (문서 범위에 맞게)
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query는 비어있을 수 없어요.");
        }
        int safeDisplay = clamp(display, 1, 5);         // 문서 이미지 기준: 최대 5
        int safeStart   = clamp(start,   1, 1000);
        String safeSort = ("comment".equalsIgnoreCase(sort)) ? "comment" : "random";

        // 1) 요청 URL (한글은 UTF-8 인코딩 필수)
        URI uri = UriComponentsBuilder.fromHttpUrl(endpoint)
                .queryParam("query", query)
                .queryParam("display", safeDisplay)
                .queryParam("start", safeStart)
                .queryParam("sort", safeSort)
                .encode(StandardCharsets.UTF_8)
                .build()
                .toUri();

        String cacheKey = uri.toString();

        // 2) 캐시 확인 (Local Search 전용 캐시, 기본 5분)
        Map<String, Object> cached = localSearchCache.get(cacheKey);
        if (cached != null) {
            log.debug("[NAVER][CACHE-HIT][LOCAL] {}", cacheKey);
            return cached;
        }

        // 3) 헤더 (네이버 개발자 센터 방식)
        HttpHeaders headers = new HttpHeaders();
        // API HUB는 NCP API Gateway 인증을 쓴다. 구 X-Naver-Client-* 헤더는 401이 된다.
        headers.set("X-NCP-APIGW-API-KEY-ID", clientId);
        headers.set("X-NCP-APIGW-API-KEY", clientSecret);

        // 4) 레이트 리밋: 외부로 너무 자주 나가지 않도록 최소 간격 보장
        enforceMinInterval();

        // 5) 429(Too Many Requests) 대비: 최대 3회 재시도 (백오프 + Retry-After 존중)
        int maxAttempts = 3;                // 최초 + 재시도 2회
        long baseBackoffMs = 500;           // 0.5s → 1.0s → (최대) 2.0s
        HttpEntity<Void> httpEntity = new HttpEntity<>(headers);

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                ResponseEntity<Map> res = restTemplate.exchange(uri, HttpMethod.GET, httpEntity, Map.class);
                Map<String, Object> body = res.getBody();

                // 6) 성공: 캐시에 저장 후 반환
                localSearchCache.put(cacheKey, body);
                return body;

            } catch (HttpClientErrorException.TooManyRequests e) {
                // 429면 '잠깐 쉬었다 와'라는 뜻
                int finalAttempt = attempt;
                long waitMs = parseRetryAfterToMillis(e.getResponseHeaders()).orElseGet(
                        () -> (long) (baseBackoffMs * Math.pow(2, finalAttempt - 1)) // 500 → 1000 → 2000
                );
                log.warn("[NAVER][429][LOCAL] attempt {} / {} → {}ms 대기 후 재시도. uri={}",
                        attempt, maxAttempts, waitMs, cacheKey);

                sleepSilently(waitMs);

                if (attempt == maxAttempts) {
                    // 그래도 안 되면, UX 위해 '빈 결과'라도 내려주거나, 예외를 올려 컨트롤러에서 503으로 변환
                    throw e; // 전역 예외 핸들러에서 503(Service Unavailable) 매핑 권장
                }

            } catch (HttpClientErrorException e) {
                // 잘못된 파라미터 등 4xx — 재시도해도 소용없으니 바로 rethrow
                log.error("[NAVER][4xx][LOCAL] status={} body={} uri={}",
                        e.getStatusCode(), safe(e.getResponseBodyAsString()), cacheKey);
                throw e;

            } catch (Exception e) {
                // 네트워크 등 일시 오류 → 백오프로 짧게 재시도
                long waitMs = (long) (baseBackoffMs * Math.pow(2, attempt - 1));
                log.warn("[NAVER][EX][LOCAL] attempt {} / {} → {}ms 대기 후 재시도. uri={} ex={}",
                        attempt, maxAttempts, waitMs, cacheKey, e.toString());
                if (attempt == maxAttempts) throw new RuntimeException("Naver Local API 호출 실패", e);
                sleepSilently(waitMs);
            }
        }

        // 여긴 도달하지 않음
        throw new IllegalStateException("도달 불가");
    }

    // ───────────────────────────────────────────────────────────────
    // (NEW) Reverse Geocoding: 위도/경도 → 행정구역 이름
    //      - PhotoboothService 에서 뷰포트 중심좌표로 "강남구 역삼동" 같은 문자열 얻을 때 사용
    //      - 실패하면 Optional.empty() 반환 (서비스 단에서 fallback 처리)
    // ───────────────────────────────────────────────────────────────
    public Optional<String> reverseGeocodeToRegion(double lat, double lng) {
        // Naver Reverse Geocode 는 coords를 "경도,위도" 순서로 받음에 주의 (lng, lat)
        URI uri = UriComponentsBuilder.fromHttpUrl(reverseEndpoint)
                .queryParam("coords", lng + "," + lat)
                .queryParam("sourcecrs", "epsg:4326")   // WGS84
                .queryParam("orders", "legalcode")      // 법정동 기준
                .queryParam("output", "json")
                .build()
                .toUri();

        String cacheKey = uri.toString();

        // 1) 캐시 확인 (Reverse Geocoding 전용 캐시, 기본 30분)
        Map<String, Object> cached = reverseGeocodeCache.get(cacheKey);
        if (cached != null) {
            log.debug("[NAVER][CACHE-HIT][REVERSE] {}", cacheKey);
            return extractRegionNameFromReverseBody(cached);
        }

        // 2) 헤더 (NCP Map Geocode 방식)
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-NCP-APIGW-API-KEY-ID", mapClientId);
        headers.set("X-NCP-APIGW-API-KEY", mapClientSecret);

        HttpEntity<Void> httpEntity = new HttpEntity<>(headers);

        // Reverse 도 외부 API 이므로 레이트 리밋 같이 사용
        enforceMinInterval();

        try {
            ResponseEntity<Map> res = restTemplate.exchange(uri, HttpMethod.GET, httpEntity, Map.class);
            Map<String, Object> body = res.getBody();

            reverseGeocodeCache.put(cacheKey, body);
            return extractRegionNameFromReverseBody(body);

        } catch (Exception e) {
            log.warn("[NAVER][REVERSE][EX] lat={}, lng={} uri={} ex={}",
                    lat, lng, cacheKey, e.toString());
            return Optional.empty(); // 서비스 단에서 fallback(전국검색 등) 하도록
        }
    }

    /**
     * Reverse Geocode 응답 JSON에서 "강남구 역삼동" 형태의 문자열을 뽑아내는 헬퍼
     *
     * 응답 구조 예시(요약):
     * {
     *   "results": [
     *     {
     *       "region": {
     *         "area1": { "name": "서울특별시" },
     *         "area2": { "name": "강남구" },
     *         "area3": { "name": "역삼동" },
     *         ...
     *       },
     *       ...
     *     }
     *   ]
     * }
     */
    @SuppressWarnings("unchecked")
    private Optional<String> extractRegionNameFromReverseBody(Map<String, Object> body) {
        if (body == null) return Optional.empty();

        Object resultsObj = body.get("results");
        if (!(resultsObj instanceof java.util.List<?> results) || results.isEmpty()) {
            return Optional.empty();
        }

        Object first = results.get(0);
        if (!(first instanceof Map<?, ?> firstMap)) {
            return Optional.empty();
        }

        Object regionObj = firstMap.get("region");
        if (!(regionObj instanceof Map<?, ?> region)) {
            return Optional.empty();
        }

        Map<String, Object> area2 = (Map<String, Object>) region.get("area2"); // 구
        Map<String, Object> area3 = (Map<String, Object>) region.get("area3"); // 동

        String gu   = area2 != null ? (String) area2.get("name") : null;
        String dong = area3 != null ? (String) area3.get("name") : null;

        if (gu == null && dong == null) {
            return Optional.empty();
        }

        StringBuilder sb = new StringBuilder();
        if (gu != null && !gu.isBlank()) {
            sb.append(gu.trim());
        }
        if (dong != null && !dong.isBlank()) {
            if (!sb.isEmpty()) sb.append(" ");
            sb.append(dong.trim());
        }

        String regionName = sb.toString().trim();
        return regionName.isEmpty() ? Optional.empty() : Optional.of(regionName);
    }

    // ─────────────────────── helpers ─────────────────────────

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    // ─────────────────────── 캐시 관찰용 API ─────────────────────────
    //
    // 캐시가 의도대로 동작하는지 확인하는 통로다. 두 캐시를 따로 볼 수 있어야
    // "Local Search만 껐다", "Reverse Geocoding에서만 eviction이 났다"를 구분할 수 있다.

    /** Local Search 캐시 (hit/miss/eviction 통계 포함) */
    public NaverResponseCache localSearchCache() {
        return localSearchCache;
    }

    /** Reverse Geocoding 캐시 (hit/miss/eviction 통계 포함) */
    public NaverResponseCache reverseGeocodeCache() {
        return reverseGeocodeCache;
    }

    /** 테스트·측정에서 캐시 상태를 초기화할 때 사용 (두 캐시 모두) */
    public void clearCache() {
        localSearchCache.clear();
        reverseGeocodeCache.clear();
    }

    /**
     * 두 캐시에 들어 있는 항목 수의 합.
     *
     * 개별 캐시 크기는 {@link #localSearchCache()} / {@link #reverseGeocodeCache()}의
     * {@code size()}로 본다. 합계만 보면 어느 쪽이 커지는지 알 수 없다.
     */
    public int cacheSize() {
        return (int) (localSearchCache.size() + reverseGeocodeCache.size());
    }

    // 외부 호출 최소 간격 보장 (아주 단순한 방식)
    private void enforceMinInterval() {
        long now = System.currentTimeMillis();
        long last = lastCallAt.get();
        long elapsed = now - last;
        if (elapsed < MIN_INTERVAL_MS) {
            sleepSilently(MIN_INTERVAL_MS - elapsed);
        }
        lastCallAt.set(System.currentTimeMillis());
    }

    private static Optional<Long> parseRetryAfterToMillis(HttpHeaders headers) {
        if (headers == null) return Optional.empty();
        String raw = headers.getFirst("Retry-After");
        if (raw == null) return Optional.empty();
        try {
            // 네이버가 초(second)로 준다고 가정
            long seconds = Long.parseLong(raw.trim());
            return Optional.of(Duration.ofSeconds(seconds).toMillis());
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    private static void sleepSilently(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s.substring(0, Math.min(500, s.length()));
    }
}
