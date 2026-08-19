package com.nemo.backend.domain.map.service;

import com.nemo.backend.domain.map.dto.PhotoboothDto;
import com.nemo.backend.domain.map.dto.ViewportRequest;
import com.github.benmanes.caffeine.cache.Ticker;
import com.nemo.backend.domain.map.util.NaverApiClient;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 캐시를 두 개로 나눈 뒤에도 <b>지도 검색 결과가 그대로인지</b> 확인한다.
 *
 * <p>이번 작업은 "어디에 얼마나 담느냐"만 바꾼 캐시 정책 변경이다.
 * 이름·브랜드·좌표·주소·네이버 링크가 하나라도 달라지면 그건 정책 변경이 아니라 기능 변경이다.
 * 그래서 <b>캐시에서 꺼낸 응답으로 만든 결과</b>와 <b>외부에서 새로 받은 응답으로 만든 결과</b>를
 * 직접 비교한다.
 *
 * <p>{@code placeId}는 매번 새로 만드는 임의 UUID라 비교 대상에서 뺀다.
 * 이건 캐시와 무관한 기존 동작이다.
 */
@DisplayName("캐시 분리 후 지도 검색 결과 회귀")
class PhotoboothCacheRegressionTest {

    /** 강남역 부근 뷰포트 — 아래 스텁 좌표가 이 안에 들어온다 */
    private static final double NE_LAT = 37.5030, NE_LNG = 127.0450;
    private static final double SW_LAT = 37.4930, SW_LNG = 127.0350;

    private PhotoboothService service;
    private NaverApiClient client;
    private RestTemplate restTemplate;
    private AtomicInteger localCalls;
    private AtomicInteger reverseCalls;

    @BeforeEach
    void setUp() {
        localCalls = new AtomicInteger();
        reverseCalls = new AtomicInteger();
        MeterRegistry registry = new SimpleMeterRegistry();

        restTemplate = mock(RestTemplate.class);
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

        client = new NaverApiClient(restTemplate, 300L, 1000L, 1800L, 1000L, registry, Ticker.systemTicker());
        applyEndpoints(client);
        service = new PhotoboothService(client);
    }

    @Test
    @DisplayName("두 번째 조회는 전부 캐시에서 나오고 결과는 첫 번째와 완전히 같다")
    void cachedResultIsIdentical() {
        List<PhotoboothDto> first = service.getPhotoboothsInViewport(viewport());
        int callsAfterFirst = localCalls.get() + reverseCalls.get();

        List<PhotoboothDto> second = service.getPhotoboothsInViewport(viewport());

        assertThat(localCalls.get() + reverseCalls.get())
                .as("두 번째 조회에서는 외부 API가 한 번도 불리면 안 된다")
                .isEqualTo(callsAfterFirst);

        assertThat(comparable(second))
                .as("이름·브랜드·좌표·주소·네이버 링크가 그대로여야 한다")
                .isEqualTo(comparable(first));
    }

    @Test
    @DisplayName("결과 내용이 스텁 응답과 일치한다 — 이름/브랜드/좌표/주소/링크")
    void resultFieldsAreUnchanged() {
        List<PhotoboothDto> result = service.getPhotoboothsInViewport(viewport());

        assertThat(result).isNotEmpty();

        PhotoboothDto dto = result.stream()
                .filter(p -> p.getName().equals("역삼 인생네컷"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("기대한 항목이 결과에 없다: " + comparable(result)));

        assertThat(dto.getBrand()).isEqualTo("인생네컷");
        assertThat(dto.getLatitude()).isEqualTo(37.4980);      // mapy 374980000 / 1e7
        assertThat(dto.getLongitude()).isEqualTo(127.0400);    // mapx 1270400000 / 1e7
        assertThat(dto.getRoadAddress()).isEqualTo("서울특별시 강남구 테헤란로 1");
        assertThat(dto.getNaverPlaceUrl()).isEqualTo("https://naver.test/place/1");
        assertThat(dto.getName())
                .as("title의 <b> 태그는 제거된 상태여야 한다")
                .doesNotContain("<");
    }

    @Test
    @DisplayName("Local Search 캐시만 꺼도 결과는 같다 — Reverse Geocoding은 계속 hit")
    void resultUnchangedWhenLocalCacheOff() {
        List<PhotoboothDto> withCache = service.getPhotoboothsInViewport(viewport());

        // Local Search 캐시만 끄고 클라이언트를 다시 만든다
        client = new NaverApiClient(restTemplate, 0L, 1000L, 1800L, 1000L,
                new SimpleMeterRegistry(), Ticker.systemTicker());
        applyEndpoints(client);
        service = new PhotoboothService(client);
        localCalls.set(0);
        reverseCalls.set(0);

        List<PhotoboothDto> withoutCache = service.getPhotoboothsInViewport(viewport());
        service.getPhotoboothsInViewport(viewport());

        assertThat(comparable(withoutCache))
                .as("캐시는 성능 장치다. 켜고 끄는 것으로 결과가 달라지면 안 된다")
                .isEqualTo(comparable(withCache));

        assertThat(client.localSearchCache().isEnabled()).isFalse();
        assertThat(client.reverseGeocodeCache().isEnabled())
                .as("한쪽을 꺼도 다른 쪽은 살아 있어야 한다")
                .isTrue();
        assertThat(reverseCalls.get())
                .as("Reverse Geocoding은 두 번 조회해도 외부 호출 1번")
                .isEqualTo(1);
    }

    /** 엔드포인트·키는 @Value 필드라 생성자로 넘길 수 없다. 테스트에서만 직접 채운다. */
    private static void applyEndpoints(NaverApiClient client) {
        ReflectionTestUtils.setField(client, "endpoint", "https://naver.test/search/v1/local");
        ReflectionTestUtils.setField(client, "clientId", "test-id");
        ReflectionTestUtils.setField(client, "clientSecret", "test-secret");
        ReflectionTestUtils.setField(client, "reverseEndpoint", "https://naver.test/map-reversegeocode/v2/gc");
        ReflectionTestUtils.setField(client, "mapClientId", "test-map-id");
        ReflectionTestUtils.setField(client, "mapClientSecret", "test-map-secret");
    }

    /** placeId(임의 UUID)를 뺀 비교용 표현 */
    private static List<String> comparable(List<PhotoboothDto> list) {
        List<String> out = new ArrayList<>();
        for (PhotoboothDto p : list) {
            out.add("%s|%s|%.7f|%.7f|%s|%s".formatted(
                    p.getName(), p.getBrand(), p.getLatitude(), p.getLongitude(),
                    p.getRoadAddress(), p.getNaverPlaceUrl()));
        }
        return out;
    }

    private static ViewportRequest viewport() {
        ViewportRequest req = new ViewportRequest();
        req.setNeLat(NE_LAT);
        req.setNeLng(NE_LNG);
        req.setSwLat(SW_LAT);
        req.setSwLng(SW_LNG);
        return req;
    }

    private static Map<String, Object> localBody() {
        return Map.of("items", List.of(
                Map.of("title", "<b>역삼</b> 인생네컷",
                        "link", "https://naver.test/place/1",
                        "roadAddress", "서울특별시 강남구 테헤란로 1",
                        "mapx", "1270400000",
                        "mapy", "374980000"),
                Map.of("title", "역삼 포토이즘",
                        "link", "https://naver.test/place/2",
                        "roadAddress", "서울특별시 강남구 테헤란로 2",
                        "mapx", "1270410000",
                        "mapy", "374990000")));
    }

    private static Map<String, Object> reverseBody() {
        return Map.of("results", List.of(Map.of(
                "region", Map.of(
                        "area2", Map.of("name", "강남구"),
                        "area3", Map.of("name", "역삼동")))));
    }
}
