package com.nemo.backend.domain.album.service;

import com.nemo.backend.domain.album.dto.AlbumSummaryResponse;
import com.nemo.backend.domain.album.entity.Album;
import com.nemo.backend.domain.album.repository.AlbumRepository;
import com.nemo.backend.domain.map.util.NaverApiClient;
import com.nemo.backend.domain.photo.entity.Photo;
import com.nemo.backend.domain.photo.repository.PhotoRepository;
import com.nemo.backend.domain.user.entity.User;
import com.nemo.backend.domain.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.s3.S3Client;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-3 회귀 테스트: 앨범 목록의 N+1을 다시 만들지 못하게 막는다.
 *
 * 2026-08-05 baseline에서 앨범 100개 조회에 SQL 202개가 나갔다.
 *   1 (앨범 목록)
 * + 100 (앨범마다 LAZY photos 컬렉션 접근)
 * + 100 (앨범마다 공유여부 exists 조회)
 * + 1 (공유받은 앨범 목록)
 * = 202
 *
 * 핵심 기준은 "총 몇 개냐"가 아니라 <b>"앨범이 늘어도 개수가 늘지 않느냐"</b>다.
 * 그래서 앨범 3개일 때와 30개일 때의 쿼리 수가 같은지를 검증한다.
 * 이 테스트가 실패한다면 목록 경로에 다시 앨범 단위 반복 조회가 들어온 것이다.
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@DisplayName("앨범 목록 쿼리 수가 앨범 개수에 비례하지 않는지")
class AlbumListQueryCountTest {

    @MockitoBean
    private S3Client s3Client;
    @MockitoBean
    private NaverApiClient naverApiClient;

    @Autowired
    private AlbumService albumService;
    @Autowired
    private AlbumRepository albumRepository;
    @Autowired
    private PhotoRepository photoRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private EntityManagerFactory entityManagerFactory;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private EntityManager em;

    private static final int PHOTOS_PER_ALBUM = 5;

    @Test
    @DisplayName("앨범 3개와 30개의 쿼리 수가 같다 (N+1 없음)")
    void queryCountDoesNotGrowWithAlbumCount() {
        User small = seedUser(3);
        User large = seedUser(30);

        long queriesForThreeAlbums = countQueries(small.getId());
        long queriesForThirtyAlbums = countQueries(large.getId());

        // 통계가 꺼져 있으면 둘 다 0이 되어 테스트가 공허하게 통과한다. 먼저 그것부터 막는다.
        assertThat(queriesForThreeAlbums)
                .as("Hibernate 통계가 켜져 있어야 의미 있는 검증이 된다")
                .isPositive();

        assertThat(queriesForThirtyAlbums)
                .as("앨범이 10배가 되어도 쿼리 수는 같아야 한다 (3개: %d, 30개: %d)",
                        queriesForThreeAlbums, queriesForThirtyAlbums)
                .isEqualTo(queriesForThreeAlbums);

        // 상한도 함께 고정한다. 목록 조회는 소수의 집계 쿼리로 끝나야 한다.
        assertThat(queriesForThirtyAlbums)
                .as("목록 조회 쿼리 수 상한")
                .isLessThanOrEqualTo(6);
    }

    @Test
    @DisplayName("2026-08-05 baseline과 같은 조건(앨범 100개)에서 SQL 202개 → 4개")
    void matchesBaselineScenario() {
        // baseline evidence: 앨범 100개 조회 = SQL 202개 (1 + 100 + 100 + 1)
        User user = seedUser(100);

        long queries = countQueries(user.getId());

        assertThat(queries)
                .as("앨범 100개 목록 조회 SQL 수 (baseline 202개)")
                .isEqualTo(4);
    }

    @Test
    @DisplayName("N+1을 없앤 뒤에도 장수·커버·정렬 결과는 그대로다")
    void listContentIsUnchanged() {
        User user = seedUser(3);

        List<AlbumSummaryResponse> albums = albumService.getAlbums(user.getId(), "ALL", false);

        assertThat(albums).hasSize(3);
        assertThat(albums).allSatisfy(a -> {
            assertThat(a.getPhotoCount())
                    .as("삭제된 사진은 장수에서 빠져야 한다")
                    .isEqualTo(PHOTOS_PER_ALBUM);
            assertThat(a.getCoverPhotoUrl())
                    .as("사진이 있으면 커버가 자동으로 채워져야 한다")
                    .isNotBlank();
            assertThat(a.getRole()).isEqualTo("OWNER");
            assertThat(a.isShared()).isFalse();
        });

        // 기본 정렬은 최신순(createdAt desc)
        assertThat(albums)
                .isSortedAccordingTo((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()));
    }

    @Test
    @DisplayName("살아있는 사진이 없는 앨범은 커버가 없고 장수가 0이다")
    void albumWithOnlyDeletedPhotosHasNoCover() {
        User user = createUser();
        transactionTemplate.executeWithoutResult(status -> {
            Album album = new Album();
            album.setName("삭제된 사진만 있는 앨범");
            album.setUser(em.getReference(User.class, user.getId()));
            album.setCoverPhotoUrl("https://example.test/stale-cover.jpg"); // 이미 죽은 커버
            album.addPhoto(persistPhoto(user, true), 0);
            albumRepository.save(album);
        });

        List<AlbumSummaryResponse> albums = albumService.getAlbums(user.getId(), "ALL", false);

        assertThat(albums).hasSize(1);
        assertThat(albums.get(0).getPhotoCount()).isZero();
        assertThat(albums.get(0).getCoverPhotoUrl())
                .as("살아있는 사진이 없으면 오래된 커버 URL을 그대로 보여주면 안 된다")
                .isNull();
    }

    // ---------- helpers ----------

    private long countQueries(Long userId) {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        albumService.getAlbums(userId, "ALL", false);

        return statistics.getPrepareStatementCount();
    }

    private User createUser() {
        User u = new User();
        u.setEmail("album-list-" + UUID.randomUUID() + "@nemo.test");
        u.setPassword("{noop}irrelevant");
        u.setNickname("album-list");
        u.setProvider("local");
        return userRepository.save(u);
    }

    /** 앨범 albumCount개, 각 앨범에 살아있는 사진 5장 + 삭제된 사진 1장 */
    private User seedUser(int albumCount) {
        User user = createUser();
        transactionTemplate.executeWithoutResult(status -> {
            for (int i = 0; i < albumCount; i++) {
                Album album = new Album();
                album.setName("앨범 " + i);
                album.setUser(em.getReference(User.class, user.getId()));

                List<Photo> photos = new ArrayList<>();
                for (int j = 0; j < PHOTOS_PER_ALBUM; j++) {
                    photos.add(persistPhoto(user, false));
                }
                photos.add(persistPhoto(user, true)); // 장수에 포함되면 안 되는 삭제 사진
                for (int sequence = 0; sequence < photos.size(); sequence++) {
                    album.addPhoto(photos.get(sequence), sequence);
                }

                albumRepository.save(album);
            }
        });
        // 1차 캐시가 아니라 DB에서 다시 읽도록
        entityManagerFactory.unwrap(SessionFactory.class).getCache().evictAllRegions();
        return user;
    }

    @Transactional
    Photo persistPhoto(User owner, boolean deleted) {
        Photo p = new Photo();
        p.setUserId(owner.getId());
        p.setImageUrl("https://example.test/" + UUID.randomUUID() + ".jpg");
        p.setThumbnailUrl("https://example.test/" + UUID.randomUUID() + "-thumb.jpg");
        p.setCreatedAt(LocalDateTime.now());
        p.setDeleted(deleted);
        return photoRepository.save(p);
    }
}
