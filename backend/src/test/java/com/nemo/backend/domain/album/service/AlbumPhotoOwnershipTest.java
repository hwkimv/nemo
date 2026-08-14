package com.nemo.backend.domain.album.service;

import com.nemo.backend.domain.album.dto.AlbumDetailResponse;
import com.nemo.backend.domain.album.dto.CreateAlbumRequest;
import com.nemo.backend.domain.map.util.NaverApiClient;
import com.nemo.backend.domain.photo.entity.Photo;
import com.nemo.backend.domain.photo.repository.PhotoRepository;
import com.nemo.backend.domain.user.entity.User;
import com.nemo.backend.domain.user.repository.UserRepository;
import com.nemo.backend.global.exception.ApiException;
import com.nemo.backend.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import software.amazon.awssdk.services.s3.S3Client;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P0-2 회귀 테스트.
 *
 * 예전 AlbumService는 요청에 담긴 photoIdList를 photoRepository.findAllById()로 그냥 조회했다.
 * 삭제 여부만 걸렀을 뿐, "이 사진이 요청자의 것인가"는 아무도 묻지 않았다.
 * 그래서 사용자 A가 사용자 B의 photoId만 알면 자기 앨범에 넣고 사진 URL까지 볼 수 있었다.
 *
 * 앨범 관리 권한(canManagePhotos)과 사진 사용 권한은 다른 질문이라는 것을 테스트 이름으로 드러낸다.
 */
@SpringBootTest
@ActiveProfiles("dev")
@DisplayName("앨범에 넣는 사진의 소유권 검증")
class AlbumPhotoOwnershipTest {

    @MockitoBean
    private S3Client s3Client;
    @MockitoBean
    private NaverApiClient naverApiClient;

    @Autowired
    private AlbumService albumService;
    @Autowired
    private PhotoRepository photoRepository;
    @Autowired
    private UserRepository userRepository;

    private User userA;
    private User userB;
    private Photo photoOfA;
    private Photo photoOfB;
    private Photo deletedPhotoOfA;

    @BeforeEach
    void setUp() {
        userA = createUser("owner-a");
        userB = createUser("owner-b");
        photoOfA = createPhoto(userA, false);
        photoOfB = createPhoto(userB, false);
        deletedPhotoOfA = createPhoto(userA, true);
    }

    private User createUser(String prefix) {
        User u = new User();
        u.setEmail(prefix + "-" + UUID.randomUUID() + "@nemo.test");
        u.setPassword("{noop}irrelevant");
        u.setNickname(prefix);
        u.setProvider("local");
        return userRepository.save(u);
    }

    private Photo createPhoto(User owner, boolean deleted) {
        Photo p = new Photo();
        p.setUserId(owner.getId());
        p.setImageUrl("https://example.test/" + UUID.randomUUID() + ".jpg");
        p.setThumbnailUrl("https://example.test/" + UUID.randomUUID() + "-thumb.jpg");
        p.setDeleted(deleted);
        return photoRepository.save(p);
    }

    // ---------- 앨범 생성 경로 ----------

    @Test
    @DisplayName("OWNER는 자기 사진으로 앨범을 만들 수 있다")
    void ownerCanCreateAlbumWithOwnPhotos() {
        AlbumDetailResponse album = albumService.createAlbum(userA.getId(), CreateAlbumRequest.builder()
                .title("내 앨범")
                .photoIdList(List.of(photoOfA.getId()))
                .build());

        assertThat(album.getAlbumId()).isNotNull();
    }

    @Test
    @DisplayName("photoIdList에 남의 사진이 있으면 앨범 생성이 실패한다")
    void createAlbumRejectsOtherUsersPhotoInList() {
        assertThatThrownBy(() -> albumService.createAlbum(userA.getId(), CreateAlbumRequest.builder()
                .title("남의 사진 훔치기")
                .photoIdList(List.of(photoOfB.getId()))
                .build()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.PHOTO_NOT_USABLE);
    }

    @Test
    @DisplayName("coverPhotoId 경로로도 남의 사진을 쓸 수 없다")
    void createAlbumRejectsOtherUsersCoverPhoto() {
        // photoIdList를 막아도 cover 경로가 열려 있으면 남의 사진 URL이 그대로 노출된다.
        assertThatThrownBy(() -> albumService.createAlbum(userA.getId(), CreateAlbumRequest.builder()
                .title("커버만 남의 것")
                .coverPhotoId(photoOfB.getId())
                .build()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.PHOTO_NOT_USABLE);
    }

    @Test
    @DisplayName("내 사진과 남의 사진을 섞어 보내면 내 사진만 조용히 들어가지 않고 전체가 실패한다")
    void createAlbumFailsEntirelyOnMixedRequest() {
        assertThatThrownBy(() -> albumService.createAlbum(userA.getId(), CreateAlbumRequest.builder()
                .title("섞어 보내기")
                .photoIdList(List.of(photoOfA.getId(), photoOfB.getId()))
                .build()))
                .isInstanceOf(ApiException.class);

        // 부분 성공이 없어야 한다: 앨범 자체가 만들어지지 않는다.
        assertThat(albumService.getAlbums(userA.getId(), "ALL", false))
                .as("실패한 요청이 앨범을 남기면 안 된다")
                .noneSatisfy(a -> assertThat(a.getTitle()).isEqualTo("섞어 보내기"));
    }

    @Test
    @DisplayName("삭제된 사진과 존재하지 않는 ID도 같은 오류로 실패한다")
    void deletedAndUnknownPhotoIdsAlsoFail() {
        assertThatThrownBy(() -> albumService.createAlbum(userA.getId(), CreateAlbumRequest.builder()
                .title("삭제된 사진")
                .photoIdList(List.of(deletedPhotoOfA.getId()))
                .build()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.PHOTO_NOT_USABLE);

        assertThatThrownBy(() -> albumService.createAlbum(userA.getId(), CreateAlbumRequest.builder()
                .title("없는 사진")
                .photoIdList(List.of(999_999_999L))
                .build()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                // 존재하지 않음 / 삭제됨 / 남의 것을 구분해 응답하면 ID 탐색 통로가 된다.
                .isEqualTo(ErrorCode.PHOTO_NOT_USABLE);
    }

    // ---------- 기존 앨범에 추가하는 경로 ----------

    @Test
    @DisplayName("내 앨범이라도 남의 사진은 추가할 수 없다")
    void addPhotosRejectsOtherUsersPhoto() {
        AlbumDetailResponse album = albumService.createAlbum(userA.getId(), CreateAlbumRequest.builder()
                .title("추가 테스트")
                .build());

        assertThatThrownBy(() -> albumService.addPhotos(userA.getId(), album.getAlbumId(), List.of(photoOfB.getId())))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.PHOTO_NOT_USABLE);
    }

    @Test
    @DisplayName("내 사진 추가는 정상 동작하고, 같은 ID를 두 번 보내도 중복 추가되지 않는다")
    void addPhotosAcceptsOwnPhotosAndDeduplicates() {
        AlbumDetailResponse album = albumService.createAlbum(userA.getId(), CreateAlbumRequest.builder()
                .title("정상 추가")
                .build());

        int added = albumService.addPhotos(userA.getId(), album.getAlbumId(),
                List.of(photoOfA.getId(), photoOfA.getId()));

        assertThat(added).isEqualTo(1);
    }
}
