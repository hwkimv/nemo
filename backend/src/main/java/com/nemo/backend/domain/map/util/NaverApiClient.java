// src/main/java/com/nemo/backend/domain/map/util/NaverApiClient.java
package com.nemo.backend.domain.map.util;

import com.github.benmanes.caffeine.cache.Ticker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import com.nemo.backend.global.exception.ApiException;
import com.nemo.backend.global.exception.ErrorCode;
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

    // ⚠️ 빈 기본값을 준다. 키가 없어도 애플리케이션은 기동해야 한다.
    //
    // 예전에는 기본값이 없어서, 지도 키 4개 중 하나만 빠져도
    // 컨텍스트 생성이 실패해 앨범·타임라인·인증까지 전부 뜨지 않았다.
    // 지도 하나 때문에 서비스 전체가 죽는 구조였다.
    //
    // S3PhotoStorage 에서 이미 같은 판단을 내렸다(CS 05) —
    // 스토리지 장애의 영향 범위를 "파일을 다루는 요청"으로 좁히려고
    // 기동은 계속하고 실제 호출 시점에 실패시킨다.
    // 지도도 같은 기준을 따른다. 키가 없으면 지도 API 만 401 로 실패한다.
    @Value("${NAVER_LOCAL_CLIENT_ID:}")
    private String clientId;

    @Value("${NAVER_LOCAL_CLIENT_SECRET:}")
    private String clientSecret;

    // ───────────────────────────────────────────────────────────────
    // (2) Naver Reverse Geocoding 설정
    //    - 위도(lat), 경도(lng) → "강남구 역삼동" 같은 행정구역을 얻을 때 사용
    //    - NCP(Map) 쪽 키를 쓰므로 Local Search 키와 분리
    // ───────────────────────────────────────────────────────────────
    @Value("${naver.openapi.reverse.endpoint:https://maps.apigw.ntruss.com/map-reversegeocode/v2/gc}")
    private String reverseEndpoint;

    @Value("${NAVER_MAP_CLIENT_ID:}")
    private String mapClientId;

    @Value("${NAVER_MAP_CLIENT_SECRET:}")
    private String mapClientSecret;

    private final RestTemplate restTemplate;

    // ───────────────────────────────────────────────────────────────
    // (A) 응답 캐시 — 용도별로 2개를 따로 둔다
    //
    //     예전에는 Local Search와 Reverse Geocoding이 캐시 하나를 TTL 120초로 같이 썼다.
    //     두 데이터는 바뀌는 속도가 전혀 다르다.
    //       · 업체 검색 결과   : 폐업·신규 오픈으로 바뀔 수 있다        → 짧게
    //       · 좌표 → 행정구역 : 행정구역 개편이 아니면 안 바뀐다        → 길게
    //     하나의 TTL로는 한쪽에 맞추면 다른 쪽이 손해였고, 통계가 하나로 합쳐져
    //     어느 쪽이 외부 호출 비용을 쓰는지 구분조차 되지 않았다.
    //
    //     key는 예전과 같다: 완성된 요청 URI 문자열.
    //     같은 URI면 같은 응답이라는 전제는 그대로다. 바뀐 것은 "어디에 얼마나 담느냐"뿐이다.
    // ───────────────────────────────────────────────────────────────

    private final NaverResponseCache localSearchCache;
    private final NaverResponseCache reverseGeocodeCache;

    // ───────────────────────────────────────────────────────────────
    // 실제로 네이버로 나간 호출 수
    //
    // 캐시 miss와 외부 호출은 보통 1:1이지만 같은 값이 아니다.
    //   · 429 재시도가 붙으면 miss 1건에 호출이 여러 번 나간다
    //   · 캐시를 끄면 miss 지표 자체가 없어서 호출 수를 알 수 없다
    // 캐시 효과는 "적중률"이 아니라 "밖으로 몇 번 나갔는가"로 확인해야 한다.
    // 그래서 호출하는 자리에서 직접 센다.
    //
    // 태그는 api 하나(값 2개)뿐이다. 검색어나 좌표는 태그로 쓰지 않는다 — 지표가 폭발한다.
    // ───────────────────────────────────────────────────────────────
    private final Counter localSearchCalls;
    private final Counter reverseGeocodeCalls;

    // ───────────────────────────────────────────────────────────────
    // 레이트 리미터 관측
    //
    // 호출 수(naver.api.calls)만으로는 "제한이 걸렸는지"를 알 수 없다.
    // 리미터 앞에서 얼마나 기다렸는지, 기다리다 포기한 게 몇 건인지를 따로 봐야
    // 상한(min-interval, max-wait)이 지금 트래픽에 맞는지 판단할 수 있다.
    // ───────────────────────────────────────────────────────────────
    private final Timer rateLimitWaitTimer;
    private final Counter rateLimitRejections;

    /**
     * @param localSearchTtlSeconds     업체 검색 결과 캐시 유효시간. 기본 5분.
     *                                  0이면 이 캐시만 꺼진다.
     * @param reverseGeocodeTtlSeconds  좌표 → 행정구역 캐시 유효시간. 기본 30분.
     *                                  Local Search보다 6배 긴 이유는 주소가 훨씬 덜 바뀌기 때문이다.
     *                                  다만 "안 바뀐다"가 아니라 "덜 바뀐다"이므로 무기한은 쓰지 않는다.
     * @param meterRegistry             캐시 지표를 Prometheus로 내보내기 위한 레지스트리
     */
    @Autowired
    public NaverApiClient(
            RestTemplate restTemplate,
            @Value("${naver.cache.local-search.ttl-seconds:300}") long localSearchTtlSeconds,
            @Value("${naver.cache.local-search.maximum-size:1000}") long localSearchMaximumSize,
            @Value("${naver.cache.reverse-geocoding.ttl-seconds:1800}") long reverseGeocodeTtlSeconds,
            @Value("${naver.cache.reverse-geocoding.maximum-size:1000}") long reverseGeocodeMaximumSize,
            MeterRegistry meterRegistry
    ) {
        this(restTemplate, localSearchTtlSeconds, localSearchMaximumSize,
                reverseGeocodeTtlSeconds, reverseGeocodeMaximumSize,
                meterRegistry, Ticker.systemTicker());
    }

    /**
     * 시계를 직접 넘기는 생성자.
     *
     * 테스트에서 가짜 시계를 끼워 TTL 만료를 실제로 기다리지 않고 재현하는 데 쓴다.
     * 5분·30분을 진짜로 기다릴 수는 없다.
     */
    public NaverApiClient(
            RestTemplate restTemplate,
            long localSearchTtlSeconds,
            long localSearchMaximumSize,
            long reverseGeocodeTtlSeconds,
            long reverseGeocodeMaximumSize,
            MeterRegistry meterRegistry,
            Ticker ticker
    ) {
        this.restTemplate = restTemplate;
        this.localSearchCache = new NaverResponseCache(
                "local-search", localSearchTtlSeconds, localSearchMaximumSize, ticker);
        this.reverseGeocodeCache = new NaverResponseCache(
                "reverse-geocoding", reverseGeocodeTtlSeconds, reverseGeocodeMaximumSize, ticker);

        // Prometheus로 내보내 Grafana에서 적중률·축출·크기를 본다.
        localSearchCache.bindTo(meterRegistry);
        reverseGeocodeCache.bindTo(meterRegistry);

        this.localSearchCalls = Counter.builder("naver.api.calls")
                .description("네이버 외부 API로 실제로 나간 호출 수")
                .tag("api", "local-search")
                .register(meterRegistry);
        this.reverseGeocodeCalls = Counter.builder("naver.api.calls")
                .description("네이버 외부 API로 실제로 나간 호출 수")
                .tag("api", "reverse-geocoding")
                .register(meterRegistry);

        this.rateLimitWaitTimer = Timer.builder("naver.api.rate.limit.wait")
                .description("레이트 리미터 앞에서 기다린 시간")
                .register(meterRegistry);
        this.rateLimitRejections = Counter.builder("naver.api.rate.limit.rejections")
                .description("대기 상한을 넘겨 거절한 요청 수 — 계속 오르면 상한이나 호출 수를 손봐야 한다")
                .register(meterRegistry);

        log.info("[NAVER][CACHE] {} | {}", localSearchCache.describe(), reverseGeocodeCache.describe());

        // 키가 없어도 기동은 시키되, 조용히 넘어가지는 않는다.
        // 로그를 안 남기면 "지도가 왜 안 되지"를 한참 뒤에야 알게 된다.
        warnIfCredentialsMissing();
    }

    // ───────────────────────────────────────────────────────────────
    // (B) 레이트 리미터: 외부 호출 사이 최소 간격 200ms 확보(초당 최대 5회)
    //
    // ⚠️ 예전 구현은 동시 요청에서 전혀 동작하지 않았다.
    //
    //     long last = lastCallAt.get();                 // ① 읽고
    //     if (elapsed < MIN_INTERVAL_MS) sleep(...);    // ② 자고
    //     lastCallAt.set(System.currentTimeMillis());   // ③ 쓴다
    //
    // AtomicLong의 get()과 set()은 각각 원자적이지만, ①②③ 전체는 하나의 원자적 연산이 아니다.
    // 스레드 여러 개가 ①에서 같은 값을 읽으면 같은 대기 시간을 계산하고, 같이 자고, 같이 깨어
    // 거의 동시에 외부 API를 부른다. 그 다음 ③을 순서 없이 덮어쓴다.
    // 실측: 동시 16에서 호출률 74.0회/초 (의도 5.0회/초), 최소 호출 간격 0ms.
    //
    // 지금은 compareAndSet 루프로 "내 차례"를 원자적으로 예약한다.
    // 각 스레드가 서로 다른 시각을 받아 가므로 겹치지 않는다.
    //
    // 처음에는 updateAndGet 을 썼다. 그런데 그건 "무조건 갱신"이라
    // 거절할 요청까지 슬롯을 예약해 버렸다(유령 슬롯). PR #16 리뷰에서 잡혔다.
    // 지금은 거절 조건을 예약 '전에' 판정하고, 거절이면 CAS 를 아예 하지 않는다.
    // 아래 enforceMinInterval() 참고.
    //
    // 보장 범위: 장기 평균 호출률 ≈ 목표 rps 다.
    // "어떤 연속 1초 구간에서도 ≤ 5" 인 strict sliding window 는 보장하지 않는다.
    // NaverApiRateLimitTest.GuaranteeScope 가 그 성질을 기록한다.
    // ───────────────────────────────────────────────────────────────

    /** 외부 호출 사이 최소 간격. 200ms = 초당 5회. */
    @Value("${naver.rate-limit.min-interval-ms:200}")
    private long minIntervalMs = 200;

    /**
     * 리미터 앞에서 기다릴 수 있는 최대 시간.
     *
     * <p>동시 요청이 몰리면 뒤에 선 스레드일수록 오래 기다린다.
     * 동시 16이면 마지막 스레드는 이론상 16 × 200ms를 기다린다.
     * 그 사이 클라이언트는 이미 떠났을 수 있고, 서버는 스레드만 붙잡고 있게 된다.
     * 상한을 넘길 만큼 밀렸으면 <b>기다리지 말고 바로 실패</b>시킨다.
     */
    @Value("${naver.rate-limit.max-wait-ms:10000}")
    private long maxWaitMs = 10_000;

    /**
     * 다음 호출이 나갈 수 있는 가장 이른 시각(nanoTime 기준).
     *
     * <p>"마지막 호출 시각"이 아니라 <b>"다음 차례"</b>를 담는다.
     * 예약 시점에 값이 미래로 밀리므로, 뒤따라오는 스레드는 자동으로 그 뒤에 줄을 선다.
     *
     * <p><b>currentTimeMillis가 아니라 nanoTime을 쓴다.</b>
     * 벽시계는 NTP 보정으로 <b>뒤로 점프할 수 있다.</b> 그러면 이미 예약된 차례가
     * 갑자기 먼 미래가 되어 모든 요청이 길게 멈춘다. nanoTime은 단조 증가라 그런 일이 없다.
     * 여기서 필요한 것은 "지금 몇 시인가"가 아니라 "얼마나 지났는가"다.
     *
     * <p>0은 "아직 아무도 호출하지 않음"을 뜻할 수 없다(nanoTime은 음수일 수도 있다).
     * 그래서 첫 예약 여부를 따로 두지 않고, 기동 시각을 초기값으로 넣는다.
     */
    private final AtomicLong nextSlotAtNanos = new AtomicLong(System.nanoTime());
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

        // 4) 429(Too Many Requests) 대비: 최대 3회 재시도 (백오프 + Retry-After 존중)
        int maxAttempts = 3;                // 최초 + 재시도 2회
        long baseBackoffMs = 500;           // 0.5s → 1.0s → (최대) 2.0s
        HttpEntity<Void> httpEntity = new HttpEntity<>(headers);

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            // ⚠️ 리미터를 루프 '안'에서 부른다.
            //    예전에는 루프 밖에 한 번만 있어서, 재시도 2회는 차례를 예약하지 않고 나갔다.
            //    즉 한 번의 searchLocal이 슬롯 1개만 잡고 외부 호출은 최대 3번 할 수 있었다.
            //    429를 받고 다시 부르는 상황은 이미 네이버가 "너무 많다"고 말한 상태다.
            //    그때야말로 간격을 지켜야 한다.
            enforceMinInterval();
            try {
                localSearchCalls.increment();   // 재시도도 실제로 나간 호출이므로 시도마다 센다
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
            reverseGeocodeCalls.increment();
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

    /**
     * 지도 API 키가 비어 있으면 크게 경고한다.
     *
     * <p>키가 없어도 애플리케이션은 뜬다(CS 05 의 판단과 같다).
     * 다만 그 상태로 조용히 돌면 지도만 401 로 실패하는 이유를 한참 뒤에 알게 된다.
     * 기동 시점에 한 번 남겨 둔다.
     */
    private void warnIfCredentialsMissing() {
        boolean localMissing = isBlank(clientId) || isBlank(clientSecret);
        boolean mapMissing = isBlank(mapClientId) || isBlank(mapClientSecret);
        if (!localMissing && !mapMissing) return;

        log.warn("[NAVER] 지도 API 키가 없습니다 — 지도 기능만 실패합니다. "
                        + "지역검색 키={}, 지도(역지오코딩) 키={}. "
                        + "나머지 기능(앨범·타임라인·인증)은 정상 동작합니다.",
                localMissing ? "없음" : "있음",
                mapMissing ? "없음" : "있음");
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * 외부 호출 차례가 올 때까지 기다린다.
     *
     * <h3>동작</h3>
     * <pre>
     * 1. updateAndGet으로 "내 차례"를 원자적으로 예약한다.
     *    여러 스레드가 동시에 들어와도 각자 다른 시각을 받는다.
     *      스레드 A → 1000ms   (지금 비어 있으니 바로)
     *      스레드 B → 1200ms   (A 뒤에 줄 섬)
     *      스레드 C → 1400ms
     * 2. 예약한 시각까지 잔다. <b>잠그지 않은 채로</b> 잔다.
     *    synchronized였다면 자는 동안 다른 스레드가 예약조차 못 한다.
     * </pre>
     *
     * <p>{@code updateAndGet}은 실패하면 다시 시도하는 CAS 루프다.
     * 읽기·계산·쓰기가 한 덩어리로 원자적이므로 예전처럼 값이 겹치지 않는다.
     *
     * @throws ApiException 예상 대기가 상한을 넘거나, 기다리는 중에 인터럽트되면
     */
    private void enforceMinInterval() {
        final long now = System.nanoTime();
        final long intervalNanos = minIntervalMs * 1_000_000L;
        final long maxWaitNanos = maxWaitMs * 1_000_000L;

        // ───────────────────────────────────────────────────────────────
        // 차례를 예약한다. updateAndGet 대신 compareAndSet 루프를 직접 쓴다.
        //
        // updateAndGet은 "무조건 갱신"이라 조건을 끼워 넣을 수 없다.
        // 그래서 예전 구현은 <b>거절할 요청도 일단 슬롯을 예약</b>했다.
        // 외부 API를 부르지도 않으면서 차례만 미래로 밀어 버린 것이다(유령 슬롯).
        //
        // 실측(상한 1s, 간격 200ms, 요청 20건):
        //   실제 호출 6회 → 예약은 1,200ms까지만 밀려야 정상
        //   그런데 3,981ms까지 밀렸다. 유령 슬롯 2,781ms(= 거절 14건 × 200ms).
        //   그 결과 부하가 멈춘 뒤 들어온 정상 요청까지 거절됐다.
        //   아무도 네이버를 부르지 않는데 사용자만 429를 받는 상태다.
        //
        // 지금은 상한을 넘으면 <b>CAS를 하지 않고</b> 빠져나간다.
        // 슬롯은 실제로 호출할 요청만 소비한다.
        // ───────────────────────────────────────────────────────────────
        long waitNanos;
        while (true) {
            long prev = nextSlotAtNanos.get();
            // Math.max로 과거를 끌어올린다. 이게 없으면 한동안 호출이 없었을 때
            // 밀린 차례가 한꺼번에 몰려 나간다(버스트).
            long mySlot = Math.max(now, prev);
            waitNanos = mySlot - now;

            if (waitNanos > maxWaitNanos) {
                // 예약하지 않고 거절한다. 이 요청은 차례를 소비하지 않는다.
                log.warn("[NAVER][RATE-LIMIT] 대기 {}ms > 상한 {}ms — 슬롯을 쓰지 않고 거절한다",
                        waitNanos / 1_000_000L, maxWaitMs);
                rateLimitRejections.increment();
                throw new ApiException(ErrorCode.RATE_LIMITED,
                        "지도 검색 요청이 몰려 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.");
            }

            if (nextSlotAtNanos.compareAndSet(prev, mySlot + intervalNanos)) {
                break;   // 내 차례를 확보했다
            }
            // CAS 실패 = 그 사이 다른 스레드가 먼저 예약했다. 다시 읽고 재시도한다.
            // updateAndGet이 내부에서 하는 일과 같다. 조건 검사만 우리가 넣었을 뿐이다.
            Thread.onSpinWait();
        }

        long waitMs = waitNanos / 1_000_000L;
        if (waitMs <= 0) return;   // 내 차례가 이미 지났다. 바로 나간다.

        rateLimitWaitTimer.record(waitMs, java.util.concurrent.TimeUnit.MILLISECONDS);

        try {
            Thread.sleep(waitMs);
        } catch (InterruptedException e) {
            // 인터럽트를 삼키면 호출자가 취소 요청을 알 수 없다.
            // 플래그를 되살리고 이 요청은 실패시킨다.
            Thread.currentThread().interrupt();
            throw new ApiException(ErrorCode.RATE_LIMITED, "지도 검색이 취소되었습니다.");
        }
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
