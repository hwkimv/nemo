package com.nemo.backend.domain.storage;

import com.nemo.backend.domain.map.util.NaverApiClient;
import com.nemo.backend.domain.photo.service.FakePhotoStorage;
import com.nemo.backend.domain.storage.entity.StorageCleanupTask;
import com.nemo.backend.domain.storage.repository.StorageCleanupTaskRepository;
import com.nemo.backend.domain.storage.service.StorageCleanupService;
import com.nemo.backend.domain.storage.service.StorageCleanupTaskStore;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.s3.S3Client;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <h2>보상 처리가 실패하거나 서버가 죽어도 최종적으로 복구되는지 검증한다.</h2>
 *
 * <p>즉시 보상 삭제만으로는 부족하다. <b>그 보상마저 실패</b>하거나 그 사이에 서버가 죽으면
 * 지워야 할 키가 통째로 사라진다. 그래서 키를 DB에 적어 두고 워커가 이어받는다.
 *
 * <p>이 테스트는 <b>워커의 계약</b>을 고정한다.
 * <ul>
 *   <li>같은 작업을 두 번 실행해도 안전한가 (멱등)</li>
 *   <li>이미 없는 객체를 지워도 최종 결과가 정상인가</li>
 *   <li>여러 워커가 같은 작업을 동시에 잡지 않는가</li>
 *   <li>서버가 작업 중 죽어도 다시 처리되는가</li>
 *   <li>무한 재시도하지 않는가 (최대 횟수 + backoff)</li>
 *   <li>마지막 실패 원인이 남는가</li>
 * </ul>
 *
 * <p>배경 워커는 꺼 둔다({@code app.storage.cleanup.enabled=false}).
 * 켜 두면 테스트가 기대한 상태를 워커가 먼저 바꿔 결과가 들쭉날쭉해진다.
 * 대신 {@link StorageCleanupService}를 직접 불러 워커가 하는 일을 그대로 실행한다.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import(FakePhotoStorage.Config.class)
@TestPropertySource(properties = {
        "spring.main.allow-bean-definition-overriding=true",
        "app.storage.cleanup.enabled=false",
        "app.storage.cleanup.max-retries=3",
        "app.storage.cleanup.retry-backoff-seconds=1"
})
@DisplayName("S3 정리 작업 복구")
class StorageCleanupRecoveryTest {

    @MockitoBean
    private S3Client s3Client;

    @MockitoBean
    private NaverApiClient naverApiClient;

    @Autowired
    private StorageCleanupService cleanupService;

    @Autowired
    private StorageCleanupTaskStore store;

    @Autowired
    private StorageCleanupTaskRepository taskRepository;

    @Autowired
    private FakePhotoStorage storage;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private MeterRegistry meterRegistry;

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    private static final String KEY = "albums/test/orphan.webp";

    @BeforeEach
    void setUp() {
        storage.reset();
        taskRepository.deleteAll();
    }

    /** 워커가 한 바퀴 도는 것과 같다. */
    private int runWorkerOnce() {
        return cleanupService.processPending(20);
    }

    /**
     * 대기 시간을 기다리지 않고 지금 처리 가능하게 만든다.
     *
     * <p>네이티브 UPDATE를 쓴다. 엔티티를 고쳐 저장하면 BaseEntity의 {@code @PreUpdate}가
     * {@code updatedAt}을 현재 시각으로 덮어써서, "오래 방치된 행"을 만들 수 없다.
     */
    private void makeDueNow(Long taskId) {
        transactionTemplate.executeWithoutResult(status ->
                entityManager.createNativeQuery(
                                "UPDATE storage_cleanup_task SET next_attempt_at = ?1 WHERE id = ?2")
                        .setParameter(1, LocalDateTime.now().minusSeconds(1))
                        .setParameter(2, taskId)
                        .executeUpdate());
    }

    /** 이 행이 오래전부터 PROCESSING이었던 것처럼 만든다. */
    private void makeStale(Long taskId) {
        transactionTemplate.executeWithoutResult(status ->
                entityManager.createNativeQuery(
                                "UPDATE storage_cleanup_task SET updated_at = ?1 WHERE id = ?2")
                        .setParameter(1, LocalDateTime.now().minusHours(1))
                        .setParameter(2, taskId)
                        .executeUpdate());
    }

    private Long enqueue() {
        return store.enqueue(KEY, StorageCleanupTask.Reason.UPLOAD_ROLLBACK, "즉시 보상 실패");
    }

    // ─────────────────────── 재시도 ───────────────────────

    @Nested
    @DisplayName("재시도")
    class Retry {

        @Test
        @DisplayName("한 번 실패한 뒤 다음 시도에서 성공한다")
        void succeedsOnRetryAfterFailure() {
            storage.putForTest(KEY);
            Long taskId = enqueue();

            storage.failDeleteTimes(1);   // 첫 시도만 실패
            runWorkerOnce();

            StorageCleanupTask afterFirst = taskRepository.findById(taskId).orElseThrow();
            assertThat(afterFirst.getStatus())
                    .as("실패했으면 다시 대기 상태여야 한다. 여기서 끝나면 파일이 영원히 남는다")
                    .isEqualTo(StorageCleanupTask.Status.PENDING);
            assertThat(afterFirst.getRetryCount()).isEqualTo(1);
            assertThat(afterFirst.getLastError()).isNotBlank();
            assertThat(afterFirst.getNextAttemptAt())
                    .as("backoff — 곧바로 다시 두드리지 않는다")
                    .isAfter(LocalDateTime.now());

            makeDueNow(taskId);
            runWorkerOnce();

            assertThat(taskRepository.findById(taskId).orElseThrow().getStatus())
                    .isEqualTo(StorageCleanupTask.Status.COMPLETED);
            assertThat(storage.exists(KEY))
                    .as("파일이 실제로 사라졌다")
                    .isFalse();
        }

        @Test
        @DisplayName("backoff가 재시도마다 늘어난다 — 죽은 저장소를 쉼 없이 때리지 않는다")
        void backoffGrows() {
            storage.putForTest(KEY);
            Long taskId = enqueue();
            storage.failDelete(new IllegalStateException("S3 다운"));

            runWorkerOnce();
            Duration first = waitFor(taskId);

            makeDueNow(taskId);
            runWorkerOnce();
            Duration second = waitFor(taskId);

            assertThat(second)
                    .as("1회차 %s → 2회차 %s 로 늘어나야 한다".formatted(first, second))
                    .isGreaterThan(first);
        }

        private Duration waitFor(Long taskId) {
            StorageCleanupTask task = taskRepository.findById(taskId).orElseThrow();
            return Duration.between(LocalDateTime.now(), task.getNextAttemptAt());
        }

        @Test
        @DisplayName("최대 재시도를 넘기면 FAILED로 남고 더 시도하지 않는다")
        void stopsAfterMaxRetries() {
            storage.putForTest(KEY);
            Long taskId = enqueue();
            storage.failDelete(new IllegalStateException("S3 영구 장애"));

            // max-retries=3 이므로 3번째 시도에서 포기한다.
            for (int i = 0; i < 5; i++) {
                makeDueNow(taskId);
                runWorkerOnce();
            }

            StorageCleanupTask task = taskRepository.findById(taskId).orElseThrow();
            assertThat(task.getStatus())
                    .as("무한 재시도는 장애를 숨기고 자원만 태운다")
                    .isEqualTo(StorageCleanupTask.Status.FAILED);
            assertThat(task.getRetryCount()).isEqualTo(3);
            assertThat(task.getLastError())
                    .as("마지막 실패 원인이 남아야 사람이 손을 쓸 수 있다")
                    .contains("S3 영구 장애");

            int before = storage.deletedKeys().size();
            makeDueNow(taskId);
            runWorkerOnce();
            assertThat(storage.deletedKeys())
                    .as("FAILED가 된 뒤에는 다시 집어가지 않는다")
                    .hasSize(before);
        }
    }

    // ─────────────────────── 멱등성 ───────────────────────

    @Nested
    @DisplayName("멱등성")
    class Idempotency {

        @Test
        @DisplayName("같은 작업을 두 번 실행해도 결과가 같다")
        void runningTwiceIsSafe() {
            storage.putForTest(KEY);
            Long taskId = enqueue();

            runWorkerOnce();
            StorageCleanupTask.Status afterFirst =
                    taskRepository.findById(taskId).orElseThrow().getStatus();

            // 이미 COMPLETED인 작업을 다시 돌린다 — 워커가 겹쳐 돌거나 재시작된 상황
            cleanupService.runNow(taskId);
            cleanupService.runClaimed(taskId);

            StorageCleanupTask task = taskRepository.findById(taskId).orElseThrow();
            assertThat(afterFirst).isEqualTo(StorageCleanupTask.Status.COMPLETED);
            assertThat(task.getStatus())
                    .as("두 번 돌려도 COMPLETED 그대로다")
                    .isEqualTo(StorageCleanupTask.Status.COMPLETED);
            assertThat(task.getRetryCount())
                    .as("성공한 작업을 다시 돌려도 재시도 횟수가 오르지 않는다")
                    .isZero();
        }

        @Test
        @DisplayName("이미 없는 S3 객체를 지워도 정상 처리된다")
        void deletingMissingObjectSucceeds() {
            // 파일을 만들지 않는다. 워커가 재시작 전에 이미 지웠던 경우다.
            Long taskId = enqueue();
            assertThat(storage.exists(KEY)).isFalse();

            runWorkerOnce();

            assertThat(taskRepository.findById(taskId).orElseThrow().getStatus())
                    .as("""
                            S3의 DeleteObject는 없는 키를 지워도 성공으로 응답한다.
                            그래서 '이미 지워졌는지' 먼저 확인할 필요가 없다.
                            이 성질 덕분에 재시도가 안전하다.""")
                    .isEqualTo(StorageCleanupTask.Status.COMPLETED);
        }

        @Test
        @DisplayName("워커 두 개가 동시에 돌아도 같은 작업을 두 번 집지 않는다")
        void concurrentWorkersDoNotDoubleProcess() throws Exception {
            storage.putForTest(KEY);
            enqueue();

            int workers = 4;
            ExecutorService pool = Executors.newFixedThreadPool(workers);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(workers);
            AtomicInteger claimed = new AtomicInteger();

            for (int i = 0; i < workers; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        claimed.addAndGet(cleanupService.processPending(20));
                    } catch (Exception ignored) {
                        // 잠금 경합으로 밀린 워커는 아무것도 집지 못한다. 그게 정상이다.
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
            pool.shutdownNow();

            assertThat(claimed.get())
                    .as("""
                            여러 워커가 같은 행을 집으면 재시도 횟수가 헛되이 오르고,
                            한쪽이 COMPLETED로 바꾼 것을 다른 쪽이 되돌릴 수 있다.
                            행 잠금으로 한 번에 하나만 보게 한다.""")
                    .isEqualTo(1);
            assertThat(taskRepository.count()).isEqualTo(1);
        }
    }

    // ─────────────────────── 서버 재시작 ───────────────────────

    @Nested
    @DisplayName("서버가 죽어도 복구된다")
    class CrashRecovery {

        @Test
        @DisplayName("재시작 후에도 PENDING 작업이 그대로 처리된다")
        void pendingTaskSurvivesRestart() {
            storage.putForTest(KEY);
            Long taskId = enqueue();

            // 서버가 여기서 죽었다고 본다. 메모리 큐였다면 이 시점에 작업이 사라진다.
            // DB에 적혀 있으므로 다음 기동의 워커가 그대로 집어간다.
            assertThat(taskRepository.findById(taskId).orElseThrow().getStatus())
                    .isEqualTo(StorageCleanupTask.Status.PENDING);

            runWorkerOnce();

            assertThat(taskRepository.findById(taskId).orElseThrow().getStatus())
                    .isEqualTo(StorageCleanupTask.Status.COMPLETED);
            assertThat(storage.exists(KEY)).isFalse();
        }

        @Test
        @DisplayName("처리 중에 죽어 PROCESSING으로 멈춘 작업을 회수한다")
        void staleProcessingIsReclaimed() {
            storage.putForTest(KEY);
            Long taskId = enqueue();

            // 워커가 집어간 직후 서버가 죽은 상태를 만든다.
            store.claimOne(taskId);
            assertThat(taskRepository.findById(taskId).orElseThrow().getStatus())
                    .isEqualTo(StorageCleanupTask.Status.PROCESSING);
            makeStale(taskId);

            // 회수가 없으면 이 파일은 영원히 지워지지 않는다.
            assertThat(runWorkerOnce())
                    .as("PROCESSING은 대기 목록에 잡히지 않는다")
                    .isZero();

            assertThat(cleanupService.reclaimStaleProcessing()).isEqualTo(1);
            assertThat(taskRepository.findById(taskId).orElseThrow().getStatus())
                    .isEqualTo(StorageCleanupTask.Status.PENDING);

            runWorkerOnce();
            assertThat(taskRepository.findById(taskId).orElseThrow().getStatus())
                    .isEqualTo(StorageCleanupTask.Status.COMPLETED);
            assertThat(storage.exists(KEY)).isFalse();
        }
    }

    // ─────────────────────── 지표 ───────────────────────

    @Nested
    @DisplayName("지표")
    class Metrics {

        @Test
        @DisplayName("성공·재시도·포기가 각각 result 태그로 나뉜다")
        void countersAreTagged() {
            double completedBefore = counter("completed");
            double retriedBefore = counter("retried");

            storage.putForTest(KEY);
            Long taskId = enqueue();
            storage.failDeleteTimes(1);

            runWorkerOnce();
            assertThat(counter("retried")).isEqualTo(retriedBefore + 1);

            makeDueNow(taskId);
            runWorkerOnce();
            assertThat(counter("completed")).isEqualTo(completedBefore + 1);
        }

        @Test
        @DisplayName("밀린 작업 수를 게이지로 볼 수 있다")
        void pendingCountIsObservable() {
            store.enqueue("albums/test/a.webp", StorageCleanupTask.Reason.PHOTO_DELETED, null);
            store.enqueue("albums/test/b.webp", StorageCleanupTask.Reason.PHOTO_DELETED, null);

            assertThat(cleanupService.countByStatus(StorageCleanupTask.Status.PENDING))
                    .as("""
                            카운터는 '얼마나 일어났는가'만 알려준다.
                            '지금 얼마나 밀려 있는가'는 게이지여야 한다.
                            이 값이 계속 오르면 S3가 죽었거나 워커가 못 따라가고 있다는 뜻이다.""")
                    .isEqualTo(2);
        }

        private double counter(String result) {
            var c = meterRegistry.find("storage.cleanup.tasks").tag("result", result).counter();
            return c == null ? 0 : c.count();
        }
    }

    // ─────────────────────── 최종 상태 ───────────────────────

    @Test
    @DisplayName("여러 작업이 섞여 있어도 각각 독립적으로 끝난다")
    void tasksAreIndependent() {
        storage.putForTest("albums/test/ok.webp");
        Long ok = store.enqueue("albums/test/ok.webp", StorageCleanupTask.Reason.PHOTO_DELETED, null);
        Long missing = store.enqueue("albums/test/gone.webp", StorageCleanupTask.Reason.UPLOAD_ROLLBACK, null);

        runWorkerOnce();

        List<StorageCleanupTask> tasks = taskRepository.findAllById(List.of(ok, missing));
        assertThat(tasks)
                .allSatisfy(t -> assertThat(t.getStatus())
                        .isEqualTo(StorageCleanupTask.Status.COMPLETED));
    }
}
