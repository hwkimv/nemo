// src/main/java/com/nemo/backend/domain/map/util/NaverResponseCache.java
package com.nemo.backend.domain.map.util;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 네이버 외부 API 응답 하나를 담는 로컬 캐시.
 *
 * <p>Local Search와 Reverse Geocoding은 <b>데이터가 바뀌는 속도가 다르다.</b>
 * 업체 검색 결과는 폐업·신규 오픈으로 바뀔 수 있지만, 좌표 → 행정구역 변환은 거의 바뀌지 않는다.
 * 그런데 예전에는 둘이 같은 Map에 같은 TTL(120초)로 들어갔다.
 * 그래서 한쪽 특성에 맞추면 다른 쪽이 손해를 보는 구조였다.
 * 이 클래스를 용도별로 하나씩 두어 TTL·크기·통계를 <b>따로</b> 관리한다.
 *
 * <h3>정책</h3>
 * <ul>
 *   <li><b>expireAfterWrite</b> — 저장한 시점부터 TTL을 센다.</li>
 *   <li><b>expireAfterAccess는 쓰지 않는다.</b> 읽을 때마다 수명이 연장되면
 *       자주 보는 지역의 <i>오래된</i> 외부 응답이 계속 살아남는다.
 *       "얼마나 자주 읽히는가"가 아니라 "얼마나 오래된 데이터인가"로 버려야 한다.</li>
 *   <li><b>maximumSize</b> — 항목(entry) 개수 상한. 바이트가 아니다.
 *       넘으면 Caffeine이 알아서 밀어낸다(eviction).
 *       예전 ConcurrentHashMap 방식에는 상한이 없어서 지도를 움직이는 만큼 무한히 늘었다.</li>
 *   <li><b>recordStats</b> — hit/miss/eviction을 센다. 정책이 의도대로 동작하는지 확인하는 근거다.</li>
 * </ul>
 *
 * <p>TTL이 0 이하이면 <b>이 캐시만</b> 꺼진다. 저장도 조회도 하지 않으므로 메모리를 쓰지 않는다.
 * 다른 캐시는 영향을 받지 않는다.
 */
public class NaverResponseCache {

    /** 로그·통계에서 어느 캐시인지 구분하는 이름 (예: "local-search") */
    private final String name;

    private final long ttlSeconds;
    private final long maximumSize;

    /** TTL이 0 이하이면 null이다. null인지로 "꺼짐"을 표현한다. */
    private final Cache<String, Map<String, Object>> delegate;

    public NaverResponseCache(String name, long ttlSeconds, long maximumSize) {
        this(name, ttlSeconds, maximumSize, Ticker.systemTicker());
    }

    /**
     * @param ticker 시간을 읽는 방법. 테스트에서 가짜 시계를 넣어 TTL 만료를
     *               실제로 기다리지 않고 재현하기 위해 열어 둔다.
     */
    public NaverResponseCache(String name, long ttlSeconds, long maximumSize, Ticker ticker) {
        // 잘못된 설정은 조용히 넘어가면 안 된다.
        // TTL을 음수로 주면 "끈 것"인지 "실수"인지 구분되지 않고,
        // maximumSize 0은 넣는 족족 버려지는 캐시가 되어 캐시가 있는데 항상 miss가 난다.
        if (ttlSeconds < 0) {
            throw new IllegalArgumentException(
                    "%s 캐시의 ttl-seconds는 0 이상이어야 합니다. (0이면 캐시 OFF)".formatted(name));
        }
        if (maximumSize <= 0) {
            throw new IllegalArgumentException(
                    "%s 캐시의 maximum-size는 1 이상이어야 합니다.".formatted(name));
        }

        this.name = name;
        this.ttlSeconds = ttlSeconds;
        this.maximumSize = maximumSize;
        this.delegate = (ttlSeconds == 0)
                ? null
                : Caffeine.newBuilder()
                        .expireAfterWrite(Duration.ofSeconds(ttlSeconds))
                        .maximumSize(maximumSize)
                        // 축출·만료를 호출한 스레드에서 바로 처리한다.
                        // 기본값(ForkJoinPool)이면 축출이 비동기라 통계를 읽는 시점에
                        // 아직 반영되지 않을 수 있다. 테스트와 측정이 흔들리지 않게 고정한다.
                        .executor(Runnable::run)
                        .ticker(Objects.requireNonNull(ticker, "ticker"))
                        .recordStats()
                        .build();
    }

    public String name() {
        return name;
    }

    public boolean isEnabled() {
        return delegate != null;
    }

    public long ttlSeconds() {
        return ttlSeconds;
    }

    public long maximumSize() {
        return maximumSize;
    }

    /** 캐시에 있으면 응답 body, 없거나 캐시가 꺼져 있으면 null */
    public Map<String, Object> get(String key) {
        if (delegate == null) return null;
        return delegate.getIfPresent(key);
    }

    public void put(String key, Map<String, Object> body) {
        if (delegate == null) return;
        // 네이버가 body를 안 주는 경우가 있다. null을 넣으면 Caffeine이 거부하므로 빈 Map으로 바꾼다.
        delegate.put(key, Objects.requireNonNullElseGet(body, Map::of));
    }

    public void clear() {
        if (delegate == null) return;
        delegate.invalidateAll();
        delegate.cleanUp();
    }

    /**
     * 현재 들어 있는 항목 수.
     *
     * <p>Caffeine은 만료·축출을 백그라운드에서 처리해서 방금 만료된 항목이 잠깐 남아 있을 수 있다.
     * 크기를 <b>보려는 목적</b>이라면 그 전에 정리를 시켜야 값이 흔들리지 않는다.
     */
    public long size() {
        if (delegate == null) return 0;
        delegate.cleanUp();
        return delegate.estimatedSize();
    }

    /** hit/miss/eviction 통계. 캐시가 꺼져 있으면 전부 0인 통계를 준다. */
    public CacheStats stats() {
        if (delegate == null) return CacheStats.empty();
        return delegate.stats();
    }

    /**
     * 이 캐시를 Micrometer에 연결한다. Prometheus로 나가고 Grafana에서 보인다.
     *
     * <p>이걸 붙이기 전에는 {@code recordStats()}로 센 값을 테스트에서만 볼 수 있었다.
     * 즉 <b>"캐시를 넣었다"까지만 증명하고 "운영에서 실제로 맞는 정책인지"는 알 수 없었다.</b>
     * TTL 5분/30분은 데이터 특성으로 정한 초기값이라, 조정하려면 실제 hit ratio와
     * eviction을 봐야 한다. 그 근거를 만드는 것이 이 연결의 목적이다.
     *
     * <p>노출되는 지표 (모두 {@code cache="local-search"} / {@code cache="reverse-geocoding"} 태그로 구분):
     * <ul>
     *   <li>{@code cache_gets_total{result="hit"}} / {@code {result="miss"}} — hit ratio 계산용</li>
     *   <li>{@code cache_evictions_total} — maximumSize에 눌려 밀려난 수</li>
     *   <li>{@code cache_size} — 현재 항목 수</li>
     * </ul>
     *
     * <p>카디널리티는 캐시 2개뿐이라 안전하다. 캐시 키(요청 URI)는 <b>태그로 쓰지 않는다.</b>
     * URI를 태그로 붙이면 검색어마다 시계열이 생겨 지표가 폭발한다.
     *
     * <p>캐시가 꺼져 있으면(TTL=0) 바인딩할 대상이 없으므로 아무것도 등록하지 않는다.
     * Grafana에서 해당 시계열이 사라지는 것 자체가 "이 캐시는 꺼져 있다"는 신호가 된다.
     */
    public void bindTo(MeterRegistry registry) {
        if (delegate == null || registry == null) return;
        CaffeineCacheMetrics.monitor(registry, delegate, name, List.of(Tag.of("api", "naver")));
    }

    /** 사람이 읽는 한 줄 요약 — 측정 로그에 그대로 찍기 위한 것 */
    public String describe() {
        if (delegate == null) {
            return "%s: OFF (ttl=%ds)".formatted(name, ttlSeconds);
        }
        CacheStats s = stats();
        return "%s: ttl=%ds max=%d size=%d hit=%d miss=%d eviction=%d".formatted(
                name, ttlSeconds, maximumSize, size(),
                s.hitCount(), s.missCount(), s.evictionCount());
    }
}
