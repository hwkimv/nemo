package com.nemo.backend.domain.storage;

import com.nemo.backend.domain.map.util.NaverApiClient;
import com.nemo.backend.domain.photo.entity.Photo;
import com.nemo.backend.domain.photo.repository.PhotoRepository;
import com.nemo.backend.domain.storage.service.StorageService;
import com.nemo.backend.domain.user.entity.User;
import com.nemo.backend.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.s3.S3Client;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.transaction.IllegalTransactionStateException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 사진 저장 한도(maxPhotoCount)가 동시 업로드에서 지켜지는지 검증한다.
 *
 * <h3>왜 이 흐름을 골랐나</h3>
 * 친구 요청 중복과 앨범 즐겨찾기는 DB에 unique 제약이 있어 마지막 방어선이 존재한다.
 * 그런데 <b>저장 한도는 "행 개수"에 대한 조건이라 unique 제약으로 막을 수 없다.</b>
 * 애플리케이션이 지키지 못하면 아무도 지켜주지 않는다.
 *
 * <h3>현재 코드의 순서</h3>
 * <pre>
 * PhotoServiceImpl.uploadHybrid()
 *   ├─ storageService.checkPhotoLimitOrThrow(userId)   // 미리 거절 (동시성은 못 막음)
 *   ├─ storage.store(image)                            // (느린 외부 저장)
 *   ├─ storageService.reserveQuotaOrThrow(userId)      // 사용자 행 잠그고 재확인 ← 틈을 닫는 곳
 *   └─ photoRepository.save(photo)                     // INSERT
 * </pre>
 *
 * 수정 전에는 ②가 없었다. 세는 시점과 넣는 시점이 떨어져 있어 그 사이에 다른 요청이
 * 끼어들면 여러 요청이 모두 "아직 여유 있음"을 보고 저장했다.
 * 실제로 한 자리 남은 상태에서 동시 8건을 보내니 <b>7건이 통과해 26장</b>이 됐다.
 *
 * 이 테스트는 그 순서를 그대로 재현한다. 업로드 전체(S3 포함)를 돌리지 않는 이유는
 * 깨지는 지점이 <b>세기와 넣기 사이</b>이지 파일 저장이 아니기 때문이다.
 */
@SpringBootTest
@ActiveProfiles("dev")
@DisplayName("사진 저장 한도 동시성")
class PhotoQuotaConcurrencyTest {

    @MockitoBean
    private S3Client s3Client;
    @MockitoBean
    private NaverApiClient naverApiClient;

    @Autowired
    private StorageService storageService;
    @Autowired
    private PhotoRepository photoRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private TransactionTemplate tx;

    private static final int MAX_PHOTOS = 20;
    private static final int CONCURRENT_UPLOADS = 8;

    private User user;

    @BeforeEach
    void setUp() {
        User u = new User();
        u.setEmail("quota-" + UUID.randomUUID() + "@nemo.test");
        u.setPassword("{noop}irrelevant");
        u.setNickname("quota");
        u.setProvider("local");
        u.setMaxPhotoCount(MAX_PHOTOS);
        user = userRepository.save(u);

        // 한도까지 딱 한 장 남긴다. 이 상태에서 동시에 여러 번 올린다.
        for (int i = 0; i < MAX_PHOTOS - 1; i++) {
            savePhoto();
        }
        assertThat(photoRepository.countByUserIdAndDeletedIsFalse(user.getId()))
                .isEqualTo(MAX_PHOTOS - 1);
    }

    private Photo savePhoto() {
        Photo p = new Photo();
        p.setUserId(user.getId());
        p.setImageUrl("https://example.test/" + UUID.randomUUID() + ".jpg");
        p.setDeleted(false);
        return photoRepository.save(p);
    }

    @Test
    @DisplayName("한 장 남은 상태에서 동시에 8번 올려도 한도를 넘지 않는다")
    void concurrentUploadsMustNotExceedQuota() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_UPLOADS);
        CountDownLatch startTogether = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(CONCURRENT_UPLOADS);

        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        for (int i = 0; i < CONCURRENT_UPLOADS; i++) {
            pool.submit(() -> {
                try {
                    startTogether.await(); // 최대한 같은 순간에 출발시킨다
                    // 실제 업로드 경로(PhotoServiceImpl.uploadHybrid)와 같은 순서
                    //   ① 느린 파일 저장 전에 미리 거절 (동시성은 못 막는다)
                    //   ② 저장 직전에 사용자 행을 잠그고 다시 확인  ← 틈을 닫는 지점
                    //   ③ INSERT
                    storageService.checkPhotoLimitOrThrow(user.getId());
                    tx.executeWithoutResult(status -> {
                        storageService.reserveQuotaOrThrow(user.getId());
                        savePhoto();
                    });
                    accepted.incrementAndGet();
                } catch (Exception e) {
                    rejected.incrementAndGet(); // 한도 초과로 거절된 것은 정상
                } finally {
                    done.countDown();
                }
            });
        }

        startTogether.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).as("모든 시도가 끝나야 한다").isTrue();
        pool.shutdownNow();

        int finalCount = photoRepository.countByUserIdAndDeletedIsFalse(user.getId());

        assertThat(finalCount)
                .as("""
                        저장 한도를 넘었다. 동시 요청 %d건 중 %d건이 통과했고 최종 %d장이 됐다.
                        한도는 %d장이다.
                        원인: 세는 시점(COUNT)과 넣는 시점(INSERT)이 떨어져 있어
                        여러 요청이 모두 "아직 여유 있음"을 보고 통과한다.
                        이 조건은 행 개수에 대한 것이라 DB unique 제약으로는 막을 수 없다."""
                        .formatted(CONCURRENT_UPLOADS, accepted.get(), finalCount, MAX_PHOTOS))
                .isLessThanOrEqualTo(MAX_PHOTOS);

        assertThat(accepted.get())
                .as("남은 자리는 한 장뿐이므로 한 건만 통과해야 한다")
                .isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(CONCURRENT_UPLOADS - 1);
    }

    @Test
    @DisplayName("트랜잭션 밖에서 부르면 거부한다 — 잠금이 조용히 무의미해지는 것을 막는다")
    void reserveQuotaRequiresCallerTransaction() {
        // 별도 트랜잭션에서 돌면 잠금이 즉시 풀려 INSERT 시점에는 아무 보호가 없다.
        // 그런 상태로 "락을 걸었다"고 착각하지 않도록 MANDATORY로 못을 박았다.
        assertThatThrownBy(() -> storageService.reserveQuotaOrThrow(user.getId()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }
}
