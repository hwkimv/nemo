package com.nemo.backend.domain.album.service;

import com.nemo.backend.domain.album.dto.AlbumSummaryResponse;
import com.nemo.backend.domain.album.entity.Album;
import com.nemo.backend.domain.album.entity.AlbumFavorite;
import com.nemo.backend.domain.album.entity.AlbumShare;
import com.nemo.backend.domain.album.repository.AlbumFavoriteRepository;
import com.nemo.backend.domain.album.repository.AlbumRepository;
import com.nemo.backend.domain.album.repository.AlbumShareRepository;
import com.nemo.backend.domain.map.util.NaverApiClient;
import com.nemo.backend.domain.photo.entity.Photo;
import com.nemo.backend.domain.photo.repository.PhotoRepository;
import com.nemo.backend.domain.user.entity.User;
import com.nemo.backend.domain.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.s3.S3Client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 앨범 목록 DB 페이지네이션 검증.
 *
 * 예전에는 Controller가 전체 목록을 만든 뒤 메모리에서 정렬하고 subList()로 잘랐다.
 * 이제 DB에서 정렬·페이징을 끝낸다.
 *
 * 조회 방식을 바꿨으므로 이 테스트의 목적은 "결과가 이전과 같은가"다.
 * 특히 소유 앨범과 공유받은 앨범을 UNION으로 합쳐 정렬하는 부분이 조용히 틀리기 쉽다.
 */
@SpringBootTest
@ActiveProfiles("dev")
@DisplayName("앨범 목록 DB 페이지네이션")
class AlbumPaginationTest {

    @MockitoBean
    private S3Client s3Client;
    @MockitoBean
    private NaverApiClient naverApiClient;

    @Autowired
    private AlbumService albumService;
    @Autowired
    private AlbumRepository albumRepository;
    @Autowired
    private AlbumShareRepository albumShareRepository;
    @Autowired
    private AlbumFavoriteRepository albumFavoriteRepository;
    @Autowired
    private PhotoRepository photoRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private TransactionTemplate tx;
    @Autowired
    private EntityManager em;

    private User me;
    private User friend;

    /** 내 앨범 5개 + 공유받은 앨범 3개 */
    private static final int OWNED = 5;
    private static final int SHARED = 3;

    @BeforeEach
    void setUp() {
        me = createUser("me");
        friend = createUser("friend");

        tx.executeWithoutResult(status -> {
            for (int i = 0; i < OWNED; i++) {
                createAlbum(me, "내앨범-" + (char) ('A' + i));
            }
            for (int i = 0; i < SHARED; i++) {
                Album album = createAlbum(friend, "공유앨범-" + (char) ('X' + i));
                AlbumShare share = new AlbumShare();
                share.setAlbum(album);
                share.setUser(em.getReference(User.class, me.getId()));
                share.setRole(AlbumShare.Role.EDITOR);
                share.setStatus(AlbumShare.Status.ACCEPTED);
                share.setActive(true);
                albumShareRepository.save(share);
            }
        });
    }

    private User createUser(String prefix) {
        User u = new User();
        u.setEmail(prefix + "-" + UUID.randomUUID() + "@nemo.test");
        u.setPassword("{noop}irrelevant");
        u.setNickname(prefix);
        u.setProvider("local");
        return userRepository.save(u);
    }

    private Album createAlbum(User owner, String name) {
        Album album = new Album();
        album.setName(name);
        album.setUser(em.getReference(User.class, owner.getId()));

        Photo p = new Photo();
        p.setUserId(owner.getId());
        p.setImageUrl("https://example.test/" + UUID.randomUUID() + ".jpg");
        p.setDeleted(false);
        album.addPhoto(photoRepository.save(p), 0);

        return albumRepository.save(album);
    }

    private List<AlbumSummaryResponse> page(String ownership, boolean favoriteOnly,
                                            String sort, boolean asc, int page, int size) {
        return albumService.getAlbumPage(me.getId(), ownership, favoriteOnly, sort, asc, page, size)
                .content();
    }

    private long total(String ownership, boolean favoriteOnly) {
        return albumService.getAlbumPage(me.getId(), ownership, favoriteOnly, "createdAt", false, 0, 1)
                .totalElements();
    }

    @Test
    @DisplayName("ALL은 소유 앨범과 공유받은 앨범을 모두 포함한다")
    void allIncludesOwnedAndShared() {
        assertThat(total("ALL", false)).isEqualTo(OWNED + SHARED);
        assertThat(total("OWNED", false)).isEqualTo(OWNED);
        assertThat(total("SHARED", false)).isEqualTo(SHARED);
    }

    @Test
    @DisplayName("역할(role)과 shared 플래그가 소유/공유에 맞게 채워진다")
    void rolesAreCorrect() {
        assertThat(page("OWNED", false, "createdAt", false, 0, 50))
                .allSatisfy(a -> {
                    assertThat(a.getRole()).isEqualTo("OWNER");
                    assertThat(a.isShared()).isFalse(); // 남과 공유 중이 아님
                });

        assertThat(page("SHARED", false, "createdAt", false, 0, 50))
                .allSatisfy(a -> {
                    assertThat(a.getRole()).isEqualTo("EDITOR");
                    assertThat(a.isShared()).isTrue();
                });
    }

    @Test
    @DisplayName("페이지를 이어붙이면 전체 목록과 정확히 같다 (중복·누락 없음)")
    void pagesConcatenateToFullList() {
        List<AlbumSummaryResponse> all = page("ALL", false, "createdAt", false, 0, 100);
        assertThat(all).hasSize(OWNED + SHARED);

        List<Long> paged = new ArrayList<>();
        int size = 3;
        for (int p = 0; p * size < all.size(); p++) {
            paged.addAll(page("ALL", false, "createdAt", false, p, size)
                    .stream().map(AlbumSummaryResponse::getAlbumId).toList());
        }

        assertThat(paged)
                .as("페이지를 이어붙인 결과가 전체 목록과 순서까지 같아야 한다")
                .containsExactlyElementsOf(all.stream().map(AlbumSummaryResponse::getAlbumId).toList());
        assertThat(paged).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("제목 정렬은 대소문자를 무시하며 asc/desc가 서로 뒤집힌 순서다")
    void titleSortIsCaseInsensitiveAndReversible() {
        List<String> asc = page("ALL", false, "title", true, 0, 100)
                .stream().map(AlbumSummaryResponse::getTitle).toList();
        List<String> desc = page("ALL", false, "title", false, 0, 100)
                .stream().map(AlbumSummaryResponse::getTitle).toList();

        assertThat(asc).isSortedAccordingTo(String.CASE_INSENSITIVE_ORDER);
        assertThat(desc).containsExactlyElementsOf(asc.reversed());
    }

    @Test
    @DisplayName("createdAt 정렬 기본값은 최신순이다")
    void createdAtSortDefaultsToNewestFirst() {
        List<AlbumSummaryResponse> newestFirst = page("ALL", false, "createdAt", false, 0, 100);

        assertThat(newestFirst)
                .isSortedAccordingTo(Comparator.comparing(AlbumSummaryResponse::getCreatedAt).reversed());
    }

    @Test
    @DisplayName("favoriteOnly는 즐겨찾기한 앨범만 남긴다")
    void favoriteOnlyFilters() {
        Long favoriteId = page("OWNED", false, "createdAt", false, 0, 1).get(0).getAlbumId();

        tx.executeWithoutResult(status -> {
            AlbumFavorite fav = new AlbumFavorite();
            fav.setAlbum(em.getReference(Album.class, favoriteId));
            fav.setUser(em.getReference(User.class, me.getId()));
            albumFavoriteRepository.save(fav);
        });

        assertThat(total("ALL", true)).isEqualTo(1);
        assertThat(page("ALL", true, "createdAt", false, 0, 50))
                .singleElement()
                .satisfies(a -> assertThat(a.getAlbumId()).isEqualTo(favoriteId));
    }

    @Test
    @DisplayName("범위를 벗어난 페이지는 빈 목록이고 총 개수는 그대로다")
    void outOfRangePageIsEmpty() {
        assertThat(page("ALL", false, "createdAt", false, 99, 10)).isEmpty();
        assertThat(total("ALL", false)).isEqualTo(OWNED + SHARED);
    }

    @Test
    @DisplayName("장수와 커버가 페이지 안에서도 정상 계산된다")
    void summaryFieldsAreFilled() {
        assertThat(page("ALL", false, "createdAt", false, 0, 50))
                .allSatisfy(a -> {
                    assertThat(a.getPhotoCount()).isEqualTo(1);
                    assertThat(a.getCoverPhotoUrl()).isNotBlank();
                    assertThat(a.getTitle()).isNotBlank();
                });
    }
}
