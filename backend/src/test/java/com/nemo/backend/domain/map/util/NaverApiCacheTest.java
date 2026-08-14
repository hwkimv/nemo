package com.nemo.backend.domain.map.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 지도 API 로컬 캐시의 동작을 코드로 고정한다.
 *
 * 캐시 구조 (NaverApiClient)
 *   - key   : 완성된 요청 URI 문자열 (query·display·start·sort가 모두 들어간다)
 *   - value : 응답 body + 저장 시각
 *   - TTL   : 기본 120초. naver.cache.ttl-seconds로 조절하며 0이면 비활성
 *   - Local Search와 Reverse Geocode가 같은 Map을 공유한다
 *
 * 여기서는 외부 호출 횟수를 직접 세어 hit/miss를 확인한다.
 * 실제 네이버 API는 호출하지 않는다.
 */
@DisplayName("지도 API 로컬 캐시")
class NaverApiCacheTest {

    private NaverApiClient client;
    private AtomicInteger externalCalls;

    /** 외부 호출 1회당 카운터를 올리는 가짜 RestTemplate */
    private void newClient(long ttlSeconds) {
        externalCalls = new AtomicInteger();
        RestTemplate restTemplate = mock(RestTemplate.class);

        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenAnswer(invocation -> {
                    externalCalls.incrementAndGet();
                    return ResponseEntity.ok(Map.of(
                            "items", List.of(Map.of(
                                    "title", "테스트 포토부스",
                                    "address", "서울시 강남구",
                                    "mapx", "1270000000",
                                    "mapy", "375000000"))));
                });

        client = new NaverApiClient(restTemplate);
        ReflectionTestUtils.setField(client, "endpoint", "https://naver.test/v1/search/local.json");
        ReflectionTestUtils.setField(client, "clientId", "test-id");
        ReflectionTestUtils.setField(client, "clientSecret", "test-secret");
        ReflectionTestUtils.setField(client, "cacheTtlSeconds", ttlSeconds);
    }

    @BeforeEach
    void setUp() {
        newClient(120);
    }

    @Test
    @DisplayName("같은 검색을 반복하면 외부 호출은 1번뿐이다 (miss 1회 → 이후 전부 hit)")
    void repeatedSearchHitsCache() {
        for (int i = 0; i < 10; i++) {
            client.searchLocal("강남구 인생네컷", 5, 1, "random");
        }

        assertThat(externalCalls.get())
                .as("10번 요청했지만 외부 API는 1번만 불려야 한다")
                .isEqualTo(1);
        assertThat(client.cacheSize()).isEqualTo(1);
    }

    @Test
    @DisplayName("캐시를 끄면(TTL 0) 요청마다 외부 API를 부른다")
    void cacheDisabledCallsEveryTime() {
        newClient(0);

        for (int i = 0; i < 10; i++) {
            client.searchLocal("강남구 인생네컷", 5, 1, "random");
        }

        assertThat(externalCalls.get())
                .as("캐시가 꺼져 있으면 10번 모두 외부 호출")
                .isEqualTo(10);
        assertThat(client.cacheSize())
                .as("캐시가 꺼져 있으면 저장도 하지 않는다 (메모리를 쓰지 않음)")
                .isZero();
    }

    @Test
    @DisplayName("캐시 키는 URI 전체다 — 파라미터가 하나라도 다르면 다른 항목이다")
    void cacheKeyIncludesEveryParameter() {
        client.searchLocal("강남구 인생네컷", 5, 1, "random");   // miss
        client.searchLocal("강남구 인생네컷", 5, 6, "random");   // start 다름 → miss
        client.searchLocal("강남구 인생네컷", 5, 1, "comment");  // sort 다름 → miss
        client.searchLocal("강남구 포토이즘", 5, 1, "random");   // query 다름 → miss
        client.searchLocal("강남구 인생네컷", 5, 1, "random");   // 첫 번째와 동일 → hit

        assertThat(externalCalls.get())
                .as("서로 다른 조합 4개만 외부 호출되고, 5번째는 캐시에서 나온다")
                .isEqualTo(4);
        assertThat(client.cacheSize()).isEqualTo(4);
    }

    @Test
    @DisplayName("TTL이 지나면 다시 외부 API를 부른다")
    void expiredEntryIsRefetched() {
        newClient(1); // TTL 1초

        client.searchLocal("강남구 인생네컷", 5, 1, "random");
        assertThat(externalCalls.get()).isEqualTo(1);

        // 저장 시각을 2초 전으로 돌려 만료를 만든다 (테스트에서 실제로 기다리지 않기 위해)
        expireAllEntries(2000);

        client.searchLocal("강남구 인생네컷", 5, 1, "random");

        assertThat(externalCalls.get())
                .as("TTL이 지난 뒤에는 캐시를 쓰지 않고 다시 조회한다")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("만료된 항목은 조회할 때 정리된다 — 다시 읽지 않으면 남아 있다")
    void expiredEntryIsRemovedOnlyWhenRead() {
        newClient(1);

        client.searchLocal("A 인생네컷", 5, 1, "random");
        client.searchLocal("B 인생네컷", 5, 1, "random");
        assertThat(client.cacheSize()).isEqualTo(2);

        expireAllEntries(2000);

        // A만 다시 조회 → A는 만료 처리되며 새 값으로 교체, B는 손대지 않음
        client.searchLocal("A 인생네컷", 5, 1, "random");

        assertThat(client.cacheSize())
                .as("만료 정리는 읽을 때만 일어난다. 다시 읽히지 않는 B는 그대로 남는다")
                .isEqualTo(2);
    }

    /** 캐시에 든 모든 항목의 저장 시각을 과거로 밀어 만료시킨다. */
    @SuppressWarnings("unchecked")
    private void expireAllEntries(long millisAgo) {
        Map<String, Object> cache =
                (Map<String, Object>) ReflectionTestUtils.getField(client, "cache");
        assertThat(cache).isNotNull();
        cache.replaceAll((key, entry) -> {
            Map<String, Object> body = (Map<String, Object>) ReflectionTestUtils.getField(entry, "body");
            long savedAt = (long) ReflectionTestUtils.getField(entry, "savedAtMs");
            return newEntry(body, savedAt - millisAgo);
        });
    }

    /** private record CacheEntry를 리플렉션으로 만든다. */
    private Object newEntry(Map<String, Object> body, long savedAtMs) {
        try {
            Class<?> entryClass = Class.forName(
                    "com.nemo.backend.domain.map.util.NaverApiClient$CacheEntry");
            var ctor = entryClass.getDeclaredConstructor(Map.class, long.class);
            ctor.setAccessible(true);
            return ctor.newInstance(body, savedAtMs);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
