package com.nemo.backend.domain.map.service;

import com.nemo.backend.domain.map.dto.ViewportRequest;
import com.nemo.backend.domain.map.util.NaverApiClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 지도 뷰포트 조회가 같은 검색어를 두 번 부르지 않는지 고정한다.
 *
 * <h3>왜 생겼던 문제인가</h3>
 * KEYWORDS[0]이 "포토부스"인데, 그 목록을 다 돌린 뒤 보조 키워드로 "지역명 + 포토부스"를
 * 한 번 더 넣고 있었다. 키워드 하나당 최대 4페이지를 부르므로 <b>그 중복만으로 외부 호출 4회</b>가
 * 낭비된다.
 *
 * 캐시가 켜져 있으면 두 번째 키워드는 전부 hit이라 아무도 눈치채지 못한다.
 * 캐시를 끄고 호출 수를 세어보고 나서야 드러났다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("지도 검색 키워드 중복 제거")
class PhotoboothKeywordDedupTest {

    private static final int PAGE_SIZE = 5;

    /** searchLocal에 들어온 검색어를 기록하는 가짜 클라이언트 */
    private NaverApiClient recordingClient(List<String> seen, AtomicInteger calls) {
        NaverApiClient client = mock(NaverApiClient.class);
        when(client.reverseGeocodeToRegion(anyDouble(), anyDouble()))
                .thenReturn(Optional.of("강남구 역삼동"));
        when(client.searchLocal(anyString(), anyInt(), anyInt(), anyString()))
                .thenAnswer(inv -> {
                    seen.add(inv.getArgument(0));
                    calls.incrementAndGet();
                    // 페이지가 꽉 차야 다음 페이지를 부른다 (최대 페이지까지 도달시키기 위함)
                    List<Map<String, Object>> items = new ArrayList<>();
                    for (int i = 0; i < PAGE_SIZE; i++) {
                        items.add(Map.of("title", "부스" + i, "address", "서울 강남구",
                                "mapx", "1270400000", "mapy", "374980000"));
                    }
                    return Map.of("items", items);
                });
        return client;
    }

    private PhotoboothService serviceWith(NaverApiClient client) {
        PhotoboothService service = new PhotoboothService(client);
        return service;
    }

    @Test
    @DisplayName("같은 검색어를 두 번 만들지 않는다 — 키워드 9개, 외부 호출 37회")
    void viewportDoesNotRepeatTheSameKeyword() {
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger calls = new AtomicInteger();
        PhotoboothService service = serviceWith(recordingClient(seen, calls));

        ViewportRequest req = new ViewportRequest();
        ReflectionTestUtils.setField(req, "neLat", 37.5030);
        ReflectionTestUtils.setField(req, "neLng", 127.0450);
        ReflectionTestUtils.setField(req, "swLat", 37.4930);
        ReflectionTestUtils.setField(req, "swLng", 127.0350);

        service.getPhotoboothsInViewport(req);

        Set<String> unique = new LinkedHashSet<>(seen);
        assertThat(unique)
                .as("""
                        같은 검색어가 두 번 만들어졌다.
                        KEYWORDS[0]이 "포토부스"인데 보조 키워드로 "지역명 + 포토부스"를 또 넣으면
                        키워드 하나당 최대 %d페이지씩 외부 API를 중복 호출한다.
                        실제 검색어: %s""".formatted(4, unique))
                .hasSize(9);

        assertThat(seen)
                .as("중복이 없으면 페이지 호출도 9 x 4 = 36회여야 한다")
                .hasSize(36);
    }
}
