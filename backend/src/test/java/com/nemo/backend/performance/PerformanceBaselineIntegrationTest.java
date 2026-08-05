package com.nemo.backend.performance;

import com.nemo.backend.domain.album.dto.AlbumSummaryResponse;
import com.nemo.backend.domain.album.service.AlbumService;
import com.nemo.backend.domain.map.util.NaverApiClient;
import com.nemo.backend.domain.photo.dto.PhotoResponseDto;
import com.nemo.backend.domain.photo.service.PhotoService;
import com.nemo.backend.domain.timeline.dto.TimelineDayResponse;
import com.nemo.backend.domain.timeline.service.TimelineService;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import software.amazon.awssdk.services.s3.S3Client;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("performance")
@ActiveProfiles("benchmark")
@SpringBootTest
class PerformanceBaselineIntegrationTest {

    private static final Long TARGET_USER_ID = 1L;

    @Autowired
    private AlbumService albumService;

    @Autowired
    private PhotoService photoService;

    @Autowired
    private TimelineService timelineService;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @MockitoBean
    private S3Client s3Client;

    @MockitoBean
    private NaverApiClient naverApiClient;

    @Test
    void recordsAlbumListBaseline() {
        Measurement<List<AlbumSummaryResponse>> result = measure(
                "albums",
                () -> albumService.getAlbums(TARGET_USER_ID, "OWNED", false)
        );

        assertThat(result.value()).hasSize(100);
        printResult("albums", result, result.value().size());
    }

    @Test
    void recordsPhotoListBaseline() {
        Measurement<Page<PhotoResponseDto>> result = measure(
                "photos",
                () -> photoService.list(
                        TARGET_USER_ID,
                        PageRequest.of(
                                0,
                                20,
                                Sort.by(Sort.Direction.DESC, "takenAt")
                        ),
                        null,
                        null,
                        null
                )
        );

        assertThat(result.value().getTotalElements()).isEqualTo(1000);
        assertThat(result.value().getContent()).hasSize(20);
        printResult("photos", result, result.value().getNumberOfElements());
    }

    @Test
    void recordsTimelineBaseline() {
        Measurement<List<TimelineDayResponse>> result = measure(
                "timeline",
                () -> timelineService.getTimeline(TARGET_USER_ID, 2025, 1)
        );

        assertThat(result.value()).isNotEmpty();
        printResult("timeline", result, result.value().size());
    }

    private <T> Measurement<T> measure(String api, Supplier<T> action) {
        Statistics statistics = entityManagerFactory
                .unwrap(SessionFactory.class)
                .getStatistics();
        statistics.clear();

        long started = System.nanoTime();
        T value = action.get();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        long queries = statistics.getPrepareStatementCount();

        assertThat(queries)
                .as("%s query count", api)
                .isPositive();
        return new Measurement<>(value, queries, elapsedMs);
    }

    private void printResult(String api, Measurement<?> result, long rows) {
        System.out.printf(
                "BASELINE api=%s queries=%d elapsed_ms=%d rows=%d%n",
                api,
                result.queries(),
                result.elapsedMs(),
                rows
        );
    }

    private record Measurement<T>(T value, long queries, long elapsedMs) {
    }
}
