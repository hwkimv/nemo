package com.nemo.backend.domain.photo.service;

import com.nemo.backend.domain.map.util.NaverApiClient;
import com.nemo.backend.domain.photo.entity.Photo;
import com.nemo.backend.domain.photo.repository.PhotoRepository;
import com.nemo.backend.domain.storage.entity.StorageCleanupTask;
import com.nemo.backend.domain.storage.repository.StorageCleanupTaskRepository;
import com.nemo.backend.domain.user.entity.User;
import com.nemo.backend.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import software.amazon.awssdk.services.s3.S3Client;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

/**
 * <h2>S3와 DB 사이의 정합성이 실제로 깨지는지 재현한다.</h2>
 *
 * <p>DB 트랜잭션은 DB 안에서만 유효하다. S3는 트랜잭션 밖이라 롤백되지 않는다.
 * 코드를 보고 "위험해 보인다"고 말하는 것과 실제로 깨진 상태를 눈으로 보는 것은 다르다.
 * 이 테스트는 <b>깨진 상태를 증명</b>하기 위한 것이다.
 *
 * <h3>이 테스트가 붙잡고 있는 순서 (PhotoServiceImpl)</h3>
 * <pre>
 * 업로드 uploadHybrid()            @Transactional 안
 *   ├─ checkPhotoLimitOrThrow()    미리 거절
 *   ├─ storage.store(image)        ← S3 PUT (트랜잭션 밖의 부작용)
 *   ├─ reserveQuotaOrThrow()       사용자 행 잠그고 재확인
 *   └─ photoRepository.save()      INSERT
 *
 * 삭제 delete()                    @Transactional 안
 *   ├─ storage.delete(imageKey)    ← S3 DELETE 먼저
 *   └─ photo.setDeleted(true)      DB는 그 다음
 * </pre>
 *
 * <p>S3 자리에는 {@link FakePhotoStorage}를 넣는다. 실제 S3나 LocalStack으로는
 * 원하는 지점에 정확히 실패를 주입할 수 없다. 확인하려는 것은 AWS SDK의 동작이 아니라
 * <b>두 저장소를 건드리는 순서</b>다.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import(FakePhotoStorage.Config.class)
@TestPropertySource(properties = {
        "spring.main.allow-bean-definition-overriding=true",
        // 배경 워커가 돌면 테스트가 기대한 상태를 먼저 바꿔 결과가 흔들린다.
        // 워커 로직은 StorageCleanupRecoveryTest에서 직접 불러 검증한다.
        "app.storage.cleanup.enabled=false"
})
@DisplayName("S3 ↔ DB 정합성")
class PhotoStorageConsistencyTest {

    /** 실제 S3 연결을 막는다. 저장소 역할은 FakePhotoStorage가 한다. */
    @MockitoBean
    private S3Client s3Client;

    /** 지도 API 키가 없어도 컨텍스트가 뜨게 한다. 이 테스트와는 무관한 빈이다. */
    @MockitoBean
    private NaverApiClient naverApiClient;

    @Autowired
    private PhotoService photoService;

    /** Case B에서 "S3는 지워졌는데 DB가 실패하는" 순간을 만들기 위해 spy로 둔다. */
    @MockitoSpyBean
    private PhotoRepository photoRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private FakePhotoStorage storage;

    @Autowired
    private StorageCleanupTaskRepository taskRepository;

    private Long userId;

    @BeforeEach
    void setUp() {
        storage.reset();
        reset(photoRepository);
        taskRepository.deleteAll();

        User u = new User();
        u.setEmail("consistency-" + UUID.randomUUID() + "@nemo.test");
        u.setPassword("{noop}irrelevant");
        u.setNickname("정합성");
        u.setProvider("local");
        userId = userRepository.save(u).getId();
    }

    private MockMultipartFile image() {
        return new MockMultipartFile("image", "photo.png", "image/png", FakePhotoStorage.PNG_BYTES);
    }

    private Long uploadOnePhoto() {
        return photoService.uploadHybrid(
                userId, null, image(), "인생네컷", null, null, null, null, null).getId();
    }

    // ───────────────────────── Case A ─────────────────────────

    @Nested
    @DisplayName("Case A — S3 업로드 성공 후 DB 저장 실패")
    class UploadThenDbFails {

        @Test
        @DisplayName("S3에 올라간 파일이 그대로 남는다 (고아 객체)")
        void s3ObjectIsOrphanedWhenDbSaveFails() {
            // INSERT에서 터뜨린다. DB 커넥션 끊김·제약 위반·디스크 부족에서 실제로 나는 실패다.
            //
            // 처음에는 저장 한도를 0으로 두고 reserveQuotaOrThrow()에서 터뜨리려 했는데
            // 그 경로로는 재현되지 않았다. uploadHybrid()의 맨 앞
            // checkPhotoLimitOrThrow()가 S3 업로드보다 먼저 막기 때문이다.
            // 즉 "한도 초과"는 대개 파일을 올리기 전에 걸러진다.
            // 위험한 건 그 뒤, 이미 S3에 올려놓고 DB를 건드리는 구간이다.
            doThrow(new RuntimeException("DB 장애")).when(photoRepository).save(any(Photo.class));

            assertThatThrownBy(() -> photoService.uploadHybrid(
                    userId, null, image(), "인생네컷", null, null, null, null, null))
                    .isInstanceOf(RuntimeException.class);

            reset(photoRepository);

            assertThat(photoRepository.countByUserIdAndDeletedIsFalse(userId))
                    .as("DB에는 사진이 없다 — 트랜잭션이 롤백됐다")
                    .isZero();

            // ── 개선 전에는 여기서 S3 파일이 그대로 남았다(고아 객체).
            //    DB 트랜잭션은 S3를 롤백하지 못하고, 보상 처리가 없었기 때문이다.
            assertThat(storage.deletedKeys())
                    .as("이제는 보상 삭제가 그 자리에서 일어난다")
                    .hasSize(1);

            assertThat(storage.existingKeys())
                    .as("S3에도 남아 있지 않다 — 고아 객체가 생기지 않는다")
                    .isEmpty();

            assertThat(taskRepository.count())
                    .as("즉시 보상이 성공했으므로 재시도 작업을 만들 필요가 없다")
                    .isZero();
        }
    }

    // ───────────────────────── Case B ─────────────────────────

    @Nested
    @DisplayName("Case B — S3 삭제 성공 후 DB 트랜잭션 실패")
    class S3DeletedThenDbRollsBack {

        @Test
        @DisplayName("DB가 실패하면 S3도 손대지 않는다 — 파일이 사라지지 않는다")
        void fileSurvivesWhenDbFails() {
            Long photoId = uploadOnePhoto();
            String key = storage.storedKeys().get(0);
            assertThat(storage.exists(key)).isTrue();

            // DB 삭제 지점에서 실패시킨다.
            doThrow(new RuntimeException("DB 장애")).when(photoRepository).save(any(Photo.class));

            assertThatThrownBy(() -> photoService.delete(userId, photoId))
                    .isInstanceOf(RuntimeException.class);

            // ── 개선 전에는 S3를 먼저 지웠기 때문에, 여기서 파일이 이미 사라진 상태였다.
            //    DB에는 사진이 살아 있다고 적혀 있고 열면 파일이 없는 '깨진 사진'이 됐다.
            //    되돌릴 방법이 없다는 것이 이 순서의 진짜 문제였다.
            assertThat(storage.exists(key))
                    .as("""
                            이제 DB가 먼저다. DB가 실패하면 S3는 아직 손대지 않은 상태다.
                            되돌릴 것이 없다.""")
                    .isTrue();
            assertThat(storage.deletedKeys())
                    .as("S3 삭제는 시도조차 되지 않았다")
                    .isEmpty();

            reset(photoRepository);
            Photo photo = photoRepository.findById(photoId).orElseThrow();
            assertThat(photo.getDeleted())
                    .as("DB도 롤백됐다. 사진은 그대로 살아 있고 파일도 있다 — 일관된 상태다")
                    .isFalse();
        }
    }

    // ───────────────────────── 정상 흐름 회귀 ─────────────────────────

    @Nested
    @DisplayName("정상 흐름은 그대로다")
    class HappyPath {

        @Test
        @DisplayName("업로드가 성공하면 DB와 S3가 모두 있고 정리 작업은 생기지 않는다")
        void normalUploadLeavesNoTask() {
            Long photoId = uploadOnePhoto();

            assertThat(photoId).isNotNull();
            assertThat(storage.existingKeys())
                    .as("파일이 저장소에 있다")
                    .hasSize(1);
            assertThat(photoRepository.findByIdAndDeletedIsFalse(photoId))
                    .as("DB에도 있다")
                    .isPresent();
            assertThat(taskRepository.count())
                    .as("""
                            정상 흐름에서는 정리 작업이 만들어지지 않는다.
                            S3가 멀쩡하면 이 테이블은 계속 비어 있다.""")
                    .isZero();
        }

        @Test
        @DisplayName("삭제가 성공하면 DB·S3 둘 다 정리되고 작업도 완료로 끝난다")
        void normalDeleteCompletes() {
            Long photoId = uploadOnePhoto();
            String key = storage.storedKeys().get(0);

            photoService.delete(userId, photoId);

            assertThat(photoRepository.findByIdAndDeletedIsFalse(photoId))
                    .as("DB에서 삭제 처리됐다")
                    .isEmpty();
            assertThat(storage.exists(key))
                    .as("파일도 그 자리에서 지워졌다 — 워커를 기다리지 않는다")
                    .isFalse();

            List<StorageCleanupTask> tasks = taskRepository.findAll();
            assertThat(tasks).hasSize(1);
            assertThat(tasks.get(0).getStatus())
                    .as("""
                            정리 작업은 남지만 COMPLETED 상태다.
                            삭제 흐름에서는 '지워야 할 키'를 반드시 DB에 먼저 적는다.
                            그래야 S3 삭제가 실패해도 무엇을 지울지 잊지 않는다.""")
                    .isEqualTo(StorageCleanupTask.Status.COMPLETED);
        }

        @Test
        @DisplayName("타인의 사진은 삭제할 수 없다 (권한 검사가 그대로 동작)")
        void cannotDeleteOthersPhoto() {
            Long photoId = uploadOnePhoto();

            User other = new User();
            other.setEmail("other-" + UUID.randomUUID() + "@nemo.test");
            other.setPassword("{noop}x");
            other.setNickname("남");
            other.setProvider("local");
            Long otherId = userRepository.save(other).getId();

            assertThatThrownBy(() -> photoService.delete(otherId, photoId))
                    .isInstanceOf(RuntimeException.class);

            assertThat(storage.deletedKeys())
                    .as("권한 검사에서 막혔으므로 S3는 손대지 않는다")
                    .isEmpty();
            assertThat(taskRepository.count())
                    .as("정리 작업도 만들어지지 않는다")
                    .isZero();
        }
    }

    // ───────────────────────── Case C ─────────────────────────

    @Nested
    @DisplayName("Case C — S3 삭제 실패")
    class S3DeleteFails {

        @Test
        @DisplayName("DB는 삭제되고, 지울 키는 재시도 작업으로 남는다")
        void deleteTaskSurvivesS3Failure() {
            Long photoId = uploadOnePhoto();
            String key = storage.storedKeys().get(0);

            storage.failDelete(new IllegalStateException("S3 장애"));

            // 예외가 밖으로 나오지 않는다. 사용자에게는 "삭제 성공"으로 보인다.
            photoService.delete(userId, photoId);

            Photo photo = photoRepository.findById(photoId).orElseThrow();
            assertThat(photo.getDeleted())
                    .as("DB는 삭제 처리됐다")
                    .isTrue();

            assertThat(storage.exists(key))
                    .as("S3 파일은 아직 남아 있다. S3가 죽어 있으니 당연하다")
                    .isTrue();

            // ── 개선 전에는 예외를 삼키고 끝냈다. 이 키를 어디에도 적지 않았으므로
            //    나중에 지우려 해도 무엇을 지워야 하는지 알 수 없었다.
            List<StorageCleanupTask> tasks = taskRepository.findAll();
            assertThat(tasks)
                    .as("이제는 지울 키가 DB에 남는다. 워커가 이어서 재시도한다")
                    .hasSize(1);
            assertThat(tasks.get(0).getObjectKey()).isEqualTo(key);
            assertThat(tasks.get(0).getStatus())
                    .as("한 번 실패했으니 다시 대기 상태로 돌아가 있다")
                    .isEqualTo(StorageCleanupTask.Status.PENDING);
            assertThat(tasks.get(0).getLastError())
                    .as("왜 못 지웠는지 남긴다. 이게 없으면 FAILED가 돼도 손쓸 수 없다")
                    .contains("S3 장애");
        }
    }
}
