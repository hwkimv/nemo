package com.nemo.backend.domain.timeline.service;

import com.nemo.backend.domain.map.util.NaverApiClient;
import com.nemo.backend.domain.photo.entity.Photo;
import com.nemo.backend.domain.photo.repository.PhotoRepository;
import com.nemo.backend.domain.timeline.dto.TimelapseDayResponse;
import com.nemo.backend.domain.timeline.dto.TimelineDayResponse;
import com.nemo.backend.domain.user.entity.User;
import com.nemo.backend.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import software.amazon.awssdk.services.s3.S3Client;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-4 회귀 테스트.
 *
 * 예전 타임라인은 사용자의 사진을 전부 읽어온 뒤 Java에서 year/month를 비교해 버렸다.
 * 8월 화면 하나 보려고 몇 년치 사진을 전부 JVM에 올리는 구조였다.
 *
 * 이제 DB에서 기간을 자른다. 이 테스트는 "조회 방식을 바꿔도 결과가 같은가"를 고정한다.
 * 특히 조용히 틀리기 쉬운 경계를 확인한다: 12월→다음 해 1월, 월말, 윤년, 촬영일이 없는 사진.
 */
@SpringBootTest
@ActiveProfiles("dev")
@DisplayName("타임라인 기간 필터를 DB로 옮긴 뒤의 결과 동등성")
class TimelinePeriodQueryTest {

    @MockitoBean
    private S3Client s3Client;
    @MockitoBean
    private NaverApiClient naverApiClient;

    @Autowired
    private TimelineService timelineService;
    @Autowired
    private PhotoRepository photoRepository;
    @Autowired
    private UserRepository userRepository;

    private User user;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setEmail("timeline-" + UUID.randomUUID() + "@nemo.test");
        user.setPassword("{noop}irrelevant");
        user.setNickname("timeline");
        user.setProvider("local");
        user = userRepository.save(user);
    }

    /** takenAt이 있는 사진 */
    private Photo photoTakenAt(LocalDateTime takenAt) {
        return persist(takenAt, LocalDateTime.of(2000, 1, 1, 0, 0), false);
    }

    /** takenAt이 없어 createdAt으로 날짜를 정하는 사진 */
    private Photo photoWithoutTakenAt(LocalDateTime createdAt) {
        return persist(null, createdAt, false);
    }

    private Photo persist(LocalDateTime takenAt, LocalDateTime createdAt, boolean deleted) {
        Photo p = new Photo();
        p.setUserId(user.getId());
        p.setImageUrl("https://example.test/" + UUID.randomUUID() + ".jpg");
        p.setTakenAt(takenAt);
        p.setCreatedAt(createdAt);
        p.setDeleted(deleted);
        return photoRepository.save(p);
    }

    private int photoCountIn(List<TimelineDayResponse> days) {
        return days.stream().mapToInt(d -> d.photos().size()).sum();
    }

    @Test
    @DisplayName("12월 조회에 다음 해 1월 사진이 섞이지 않는다")
    void decemberDoesNotLeakIntoNextJanuary() {
        photoTakenAt(LocalDateTime.of(2025, 12, 31, 23, 59, 59));
        photoTakenAt(LocalDateTime.of(2026, 1, 1, 0, 0, 0));

        List<TimelineDayResponse> december = timelineService.getTimeline(user.getId(), 2025, 12);
        List<TimelineDayResponse> january = timelineService.getTimeline(user.getId(), 2026, 1);

        assertThat(photoCountIn(december)).isEqualTo(1);
        assertThat(december.get(0).date()).isEqualTo("2025-12-31");

        assertThat(photoCountIn(january)).isEqualTo(1);
        assertThat(january.get(0).date()).isEqualTo("2026-01-01");
    }

    @Test
    @DisplayName("월 시작 00:00 사진은 포함되고, 다음 달 00:00 사진은 제외된다")
    void periodIsHalfOpenInterval() {
        photoTakenAt(LocalDateTime.of(2026, 3, 1, 0, 0, 0));   // 경계 시작 → 포함
        photoTakenAt(LocalDateTime.of(2026, 3, 31, 23, 59, 59)); // 월말 → 포함
        photoTakenAt(LocalDateTime.of(2026, 4, 1, 0, 0, 0));   // 다음 달 시작 → 제외
        photoTakenAt(LocalDateTime.of(2026, 2, 28, 23, 59, 59)); // 이전 달 → 제외

        List<TimelineDayResponse> march = timelineService.getTimeline(user.getId(), 2026, 3);

        assertThat(photoCountIn(march)).isEqualTo(2);
        assertThat(march).extracting(TimelineDayResponse::date)
                .containsExactlyInAnyOrder("2026-03-01", "2026-03-31");
    }

    @Test
    @DisplayName("윤년 2월 29일 사진이 누락되지 않는다")
    void leapDayIsIncluded() {
        photoTakenAt(LocalDateTime.of(2028, 2, 29, 12, 0)); // 2028은 윤년

        List<TimelineDayResponse> february = timelineService.getTimeline(user.getId(), 2028, 2);

        assertThat(photoCountIn(february)).isEqualTo(1);
        assertThat(february.get(0).date()).isEqualTo("2028-02-29");
    }

    @Test
    @DisplayName("촬영일이 없는 사진은 업로드일 기준으로 잡힌다 (기존 resolveDate 의미 유지)")
    void photoWithoutTakenAtFallsBackToCreatedAt() {
        photoWithoutTakenAt(LocalDateTime.of(2026, 5, 10, 9, 0));

        List<TimelineDayResponse> may = timelineService.getTimeline(user.getId(), 2026, 5);
        List<TimelineDayResponse> june = timelineService.getTimeline(user.getId(), 2026, 6);

        assertThat(photoCountIn(may))
                .as("촬영일 없는 사진이 DB 기간 조회에서 조용히 사라지면 안 된다")
                .isEqualTo(1);
        assertThat(may.get(0).date()).isEqualTo("2026-05-10");
        assertThat(photoCountIn(june)).isZero();
    }

    @Test
    @DisplayName("삭제된 사진은 기간 조회에서도 제외된다")
    void deletedPhotosAreExcluded() {
        photoTakenAt(LocalDateTime.of(2026, 7, 15, 12, 0));
        persist(LocalDateTime.of(2026, 7, 16, 12, 0), LocalDateTime.of(2026, 7, 16, 12, 0), true);

        assertThat(photoCountIn(timelineService.getTimeline(user.getId(), 2026, 7))).isEqualTo(1);
    }

    @Test
    @DisplayName("연도만 지정하면 그 해 전체가 나온다")
    void yearOnlyReturnsWholeYear() {
        photoTakenAt(LocalDateTime.of(2026, 1, 5, 12, 0));
        photoTakenAt(LocalDateTime.of(2026, 12, 5, 12, 0));
        photoTakenAt(LocalDateTime.of(2027, 1, 5, 12, 0)); // 다음 해 → 제외

        assertThat(photoCountIn(timelineService.getTimeline(user.getId(), 2026, null))).isEqualTo(2);
    }

    @Test
    @DisplayName("연·월을 모두 생략하면 전체가 나온다")
    void noFilterReturnsEverything() {
        photoTakenAt(LocalDateTime.of(2024, 3, 3, 12, 0));
        photoTakenAt(LocalDateTime.of(2026, 8, 8, 12, 0));

        assertThat(photoCountIn(timelineService.getTimeline(user.getId(), null, null))).isEqualTo(2);
    }

    @Test
    @DisplayName("타임랩스도 그 달 사진만 세고, 달의 모든 날짜를 빠짐없이 돌려준다")
    void timelapseCountsOnlyThatMonth() {
        photoTakenAt(LocalDateTime.of(2026, 2, 10, 12, 0));
        photoTakenAt(LocalDateTime.of(2026, 2, 10, 15, 0));
        photoTakenAt(LocalDateTime.of(2026, 3, 1, 0, 0)); // 다음 달 → 제외

        List<TimelapseDayResponse> february = timelineService.getTimelapse(user.getId(), 2026, 2);

        assertThat(february).hasSize(28); // 2026년 2월은 28일
        assertThat(february.stream().mapToInt(TimelapseDayResponse::photoCount).sum()).isEqualTo(2);
        assertThat(february).filteredOn(TimelapseDayResponse::hasPhoto)
                .singleElement()
                .satisfies(day -> {
                    assertThat(day.date()).isEqualTo("2026-02-10");
                    assertThat(day.photoCount()).isEqualTo(2);
                    assertThat(day.thumbnailUrl()).isNotBlank();
                });
    }
}
