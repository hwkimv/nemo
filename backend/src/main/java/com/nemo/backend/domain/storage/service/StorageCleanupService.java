// backend/src/main/java/com/nemo/backend/domain/storage/service/StorageCleanupService.java
package com.nemo.backend.domain.storage.service;

import com.nemo.backend.domain.photo.service.PhotoStorage;
import com.nemo.backend.domain.storage.entity.StorageCleanupTask;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * S3에 남은 파일을 최종적으로 지워내는 책임.
 *
 * <h3>두 겹으로 막는다</h3>
 * <ol>
 *   <li><b>즉시 보상</b> — 문제가 생긴 그 자리에서 바로 S3 삭제를 시도한다.
 *       S3가 멀쩡한 대부분의 경우 여기서 끝나고 DB에는 아무것도 남지 않는다.</li>
 *   <li><b>영속 작업</b> — 즉시 보상이 실패하면 지워야 할 키를 DB에 적는다.
 *       워커가 backoff를 두고 다시 시도한다. 서버가 죽어도 행은 남는다.</li>
 * </ol>
 *
 * <p>1번만 있으면 "보상마저 실패"에서 정보가 통째로 사라진다.
 * 2번만 있으면 정상적인 경우에도 워커 주기만큼 파일이 남는다. 둘 다 필요하다.
 *
 * <p>{@code @Async}나 메모리 큐로는 1번의 한계를 못 넘는다. 프로세스와 함께 사라지기 때문이다.
 *
 * <h3>왜 메시지 큐가 아닌가</h3>
 * 지금 필요한 것은 "실패한 삭제를 잊지 않고 다시 하기"뿐이다. 소비자는 하나고,
 * 발생량은 S3가 정상일 때 0이다. 이미 운영 중인 PostgreSQL로 충분하다.
 * RabbitMQ를 넣으면 운영할 인프라가 하나 늘고, <b>큐에 넣는 것과 DB 커밋 사이에
 * 지금 없애려는 것과 똑같은 종류의 불일치</b>가 새로 생긴다.
 */
@Slf4j
@Service
public class StorageCleanupService {

    private final StorageCleanupTaskStore store;
    private final PhotoStorage storage;

    /** 최대 재시도 횟수. 무한 재시도는 장애를 숨기고 자원만 태운다. */
    private final int maxRetries;
    private final Duration retryBackoffBase;
    /** 이 시간 넘게 PROCESSING에 머물면 죽은 워커의 흔적으로 본다. */
    private final Duration staleProcessingTimeout;

    private final Counter enqueuedCounter;
    private final Counter compensatedInlineCounter;
    private final Counter completedCounter;
    private final Counter retriedCounter;
    private final Counter failedCounter;
    private final Counter reclaimedCounter;

    public StorageCleanupService(
            StorageCleanupTaskStore store,
            PhotoStorage storage,
            MeterRegistry meterRegistry,
            @Value("${app.storage.cleanup.max-retries:5}") int maxRetries,
            @Value("${app.storage.cleanup.retry-backoff-seconds:30}") long retryBackoffSeconds,
            @Value("${app.storage.cleanup.stale-processing-minutes:10}") long staleProcessingMinutes
    ) {
        this.store = store;
        this.storage = storage;
        this.maxRetries = maxRetries;
        this.retryBackoffBase = Duration.ofSeconds(retryBackoffSeconds);
        this.staleProcessingTimeout = Duration.ofMinutes(staleProcessingMinutes);

        this.compensatedInlineCounter = counter(meterRegistry, "compensated_inline",
                "즉시 보상 삭제로 그 자리에서 해결된 수");
        this.enqueuedCounter = counter(meterRegistry, "enqueued",
                "정리 작업이 DB에 기록된 수 (즉시 보상이 실패했거나 삭제 흐름에서 예약한 것)");
        this.completedCounter = counter(meterRegistry, "completed", "워커가 최종 성공시킨 수");
        this.retriedCounter = counter(meterRegistry, "retried", "실패해서 다시 대기로 돌아간 수");
        this.failedCounter = counter(meterRegistry, "failed",
                "최대 재시도를 넘겨 자동 처리를 포기한 수 — 0이 아니면 사람이 봐야 한다");
        this.reclaimedCounter = counter(meterRegistry, "reclaimed",
                "죽은 워커가 잡고 있던 것을 회수한 수");
    }

    private static Counter counter(MeterRegistry registry, String result, String description) {
        return Counter.builder("storage.cleanup.tasks")
                .description(description)
                .tag("result", result)
                .register(registry);
    }

    // ────────────────────────── 1) 즉시 보상 ──────────────────────────

    /**
     * 지금 바로 지워보고, 안 되면 DB에 적어 둔다.
     *
     * <p>이 메서드는 <b>예외를 던지지 않는다.</b> 정리에 실패했다고 사용자 요청까지
     * 실패시킬 이유가 없다. 대신 반드시 DB에 흔적을 남긴다.
     * 로그만 남기고 끝내면 그 파일은 영원히 지워지지 않는다.
     */
    public void deleteNowOrScheduleRetry(String objectKey, StorageCleanupTask.Reason reason) {
        if (objectKey == null || objectKey.isBlank()) return;

        try {
            storage.delete(objectKey);
            compensatedInlineCounter.increment();
            log.info("[CLEANUP][즉시] 삭제 완료 key={} reason={}", objectKey, reason);
        } catch (Exception e) {
            log.warn("[CLEANUP][즉시] 삭제 실패 → 재시도 대기열에 기록 key={} reason={} err={}",
                    objectKey, reason, e.toString());
            store.enqueue(objectKey, reason, e.toString());
            enqueuedCounter.increment();
        }
    }

    /**
     * 호출자의 트랜잭션에 정리 작업을 함께 적는다.
     *
     * <p>삭제 흐름에서 쓴다. "사진을 삭제 처리했다"와 "그 파일을 지워야 한다"가
     * 반드시 같이 커밋돼야 한다. 하나만 커밋되면 다시 정합성이 깨진다.
     */
    public Long scheduleInCurrentTransaction(String objectKey, StorageCleanupTask.Reason reason) {
        if (objectKey == null || objectKey.isBlank()) return null;
        Long id = store.enqueueInCurrentTransaction(objectKey, reason);
        enqueuedCounter.increment();
        return id;
    }

    /**
     * 이미 기록해 둔 작업을 <b>지금 바로</b> 처리해 본다.
     *
     * <p>삭제 흐름에서 커밋 직후에 부른다. 성공하면 사용자가 삭제한 파일이 곧바로 사라지고,
     * 실패해도 작업 행이 남아 있어 워커가 이어받는다.
     * 워커가 이미 집어갔으면 조용히 물러난다.
     */
    public void runNow(Long taskId) {
        if (taskId == null) return;
        if (!store.claimOne(taskId)) return;
        runClaimed(taskId);
    }

    // ────────────────────────── 2) 워커가 부르는 처리 ──────────────────────────

    /**
     * 처리 가능한 작업을 최대 {@code batchSize}건 가져와 하나씩 처리한다.
     *
     * @return 이번에 처리한 건수
     */
    public int processPending(int batchSize) {
        List<Long> ids = store.claimBatch(batchSize);
        for (Long id : ids) {
            // 작업 하나가 실패해도 나머지는 계속 처리해야 한다. 건별로 독립 트랜잭션을 쓴다.
            runClaimed(id);
        }
        return ids.size();
    }

    /**
     * 이미 선점(PROCESSING)한 작업 하나를 처리한다.
     *
     * <p>S3 삭제는 <b>트랜잭션 밖에서</b> 부른다. DB 조작은 전부 store를 통해 짧게 끊는다.
     */
    public void runClaimed(Long taskId) {
        String objectKey = store.readObjectKey(taskId);
        if (objectKey == null) return;

        try {
            // 이미 없는 객체를 지워도 S3는 성공으로 응답한다(멱등).
            // 그래서 같은 작업을 두 번 실행해도 최종 결과가 달라지지 않는다.
            storage.delete(objectKey);
            store.markCompleted(taskId);
            completedCounter.increment();
        } catch (Exception e) {
            StorageCleanupTaskStore.FailureOutcome outcome =
                    store.markFailureOrRetry(taskId, maxRetries, retryBackoffBase, e.toString());
            if (outcome == StorageCleanupTaskStore.FailureOutcome.GAVE_UP) {
                failedCounter.increment();
            } else {
                retriedCounter.increment();
            }
        }
    }

    // ────────────────────────── 3) 죽은 워커 회수 ──────────────────────────

    public int reclaimStaleProcessing() {
        int reclaimed = store.reclaimStaleProcessing(staleProcessingTimeout);
        for (int i = 0; i < reclaimed; i++) {
            reclaimedCounter.increment();
        }
        return reclaimed;
    }

    public long countByStatus(StorageCleanupTask.Status status) {
        return store.countByStatus(status);
    }
}
