// backend/src/main/java/com/nemo/backend/domain/storage/service/StorageCleanupTaskStore.java
package com.nemo.backend.domain.storage.service;

import com.nemo.backend.domain.storage.entity.StorageCleanupTask;
import com.nemo.backend.domain.storage.repository.StorageCleanupTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 정리 작업 테이블에 대한 DB 조작만 모아 둔다.
 *
 * <h3>왜 별도 클래스인가</h3>
 * 같은 빈 안에서 {@code @Transactional} 메서드를 호출하면 <b>프록시를 타지 않아
 * 트랜잭션이 걸리지 않는다.</b> StorageCleanupService는 S3를 부르는 오케스트레이션이라
 * 트랜잭션 밖에 있어야 하고, DB 조작은 트랜잭션 안에 있어야 한다.
 * 두 성질이 한 클래스에 있으면 self-invocation 함정에 빠진다.
 * (RefreshTokenMaintenance와 같은 이유다)
 *
 * <p>덤으로 <b>트랜잭션이 S3 호출을 감싸지 않는다</b>는 것이 구조로 드러난다.
 * 느린 네트워크 호출을 잡은 채 DB 커넥션과 행 잠금을 쥐고 있으면 커넥션 풀이 마른다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StorageCleanupTaskStore {

    private final StorageCleanupTaskRepository taskRepository;

    /** 워커가 작업 하나를 처리한 결과. 지표를 올릴 때 어느 쪽인지 구분해야 한다. */
    public enum FailureOutcome {
        /** 다시 시도한다 */
        SCHEDULED_RETRY,
        /** 최대 재시도를 넘겨 포기했다 */
        GAVE_UP
    }

    /**
     * 지워야 할 키를 기록한다.
     *
     * <p><b>REQUIRES_NEW인 이유</b> — 호출하는 쪽은 대개 "방금 실패해서 롤백될 트랜잭션"이다.
     * 같은 트랜잭션에 적으면 "실패했으니 적어 뒀다"는 기록이 그 실패와 함께 사라진다.
     * 독립적으로 커밋돼야 의미가 있다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long enqueue(String objectKey, StorageCleanupTask.Reason reason, String error) {
        StorageCleanupTask task = new StorageCleanupTask(objectKey, reason);
        task.recordInitialError(error);
        return taskRepository.save(task).getId();
    }

    /**
     * 사진 상태 변경과 <b>같은 트랜잭션</b>에서 기록한다.
     *
     * <p>삭제 흐름에서 쓴다. 사진이 삭제 처리되는 것과 "그 파일을 지워야 한다"는 사실이
     * 반드시 함께 커밋돼야 한다. 하나만 커밋되면 다시 정합성이 깨진다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Long enqueueInCurrentTransaction(String objectKey, StorageCleanupTask.Reason reason) {
        return taskRepository.save(new StorageCleanupTask(objectKey, reason)).getId();
    }

    /**
     * 처리할 작업을 잠그고 PROCESSING으로 선점한다.
     *
     * <p>선점과 실제 처리를 나눈 이유: S3 호출은 느리다. 그 시간 동안 행 잠금을 쥐고 있으면
     * 다른 워커와 조회가 함께 막힌다. <b>잠금은 짧게 잡고 바로 놓는다.</b>
     */
    @Transactional
    public List<Long> claimBatch(int batchSize) {
        List<StorageCleanupTask> tasks =
                taskRepository.findClaimable(LocalDateTime.now(), PageRequest.of(0, batchSize));
        tasks.forEach(StorageCleanupTask::markProcessing);
        return tasks.stream().map(StorageCleanupTask::getId).toList();
    }

    /**
     * 특정 작업 하나를 선점한다.
     *
     * <p>삭제 흐름에서 커밋 직후 "지금 바로 지워보기"를 할 때 쓴다.
     * 워커가 같은 행을 먼저 집어갔다면 {@code false}를 돌려주고 물러난다.
     * 둘이 같이 지워도 결과는 같지만(멱등), 한쪽이 COMPLETED로 바꾼 행을
     * 다른 쪽이 되돌리는 일은 막아야 한다.
     */
    @Transactional
    public boolean claimOne(Long taskId) {
        return taskRepository.findByIdForUpdate(taskId)
                .filter(t -> t.getStatus() == StorageCleanupTask.Status.PENDING)
                .map(t -> {
                    t.markProcessing();
                    return true;
                })
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public String readObjectKey(Long taskId) {
        return taskRepository.findById(taskId)
                .map(StorageCleanupTask::getObjectKey)
                .orElse(null);
    }

    @Transactional
    public void markCompleted(Long taskId) {
        taskRepository.findById(taskId).ifPresent(task -> {
            task.markCompleted();
            log.info("[CLEANUP][워커] 삭제 완료 taskId={} key={} 시도={}회",
                    taskId, task.getObjectKey(), task.getRetryCount() + 1);
        });
    }

    /**
     * 실패를 기록한다. 재시도할지 포기할지는 여기서 정한다.
     *
     * @param backoffBase 기준 대기 시간. 실제 대기는 {@code base * 2^retryCount}로 늘어난다.
     */
    @Transactional
    public FailureOutcome markFailureOrRetry(Long taskId, int maxRetries, Duration backoffBase, String error) {
        StorageCleanupTask task = taskRepository.findById(taskId).orElse(null);
        if (task == null) return FailureOutcome.GAVE_UP;

        if (task.getRetryCount() + 1 >= maxRetries) {
            task.markFailed(error);
            // 자동 복구를 포기한 상태다. 조용히 넘기면 아무도 모른다.
            log.error("[CLEANUP][워커] 최대 재시도({}회) 초과 — 수동 확인 필요. taskId={} key={} err={}",
                    maxRetries, taskId, task.getObjectKey(), error);
            return FailureOutcome.GAVE_UP;
        }

        // 지수 backoff. 같은 간격으로 계속 두드리면 죽은 저장소를 쉼 없이 때린다.
        long multiplier = 1L << Math.min(task.getRetryCount(), 10); // 넘치지 않게 상한
        Duration backoff = backoffBase.multipliedBy(multiplier);

        task.markRetry(backoff, error);
        log.warn("[CLEANUP][워커] 삭제 실패 — {}초 뒤 재시도. taskId={} 시도={}회 err={}",
                backoff.toSeconds(), taskId, task.getRetryCount(), error);
        return FailureOutcome.SCHEDULED_RETRY;
    }

    /**
     * PROCESSING에 오래 머문 작업을 PENDING으로 되돌린다.
     *
     * <p>워커가 S3를 부르는 도중 서버가 내려가면 행은 PROCESSING에 멈춘 채 남는다.
     * 이 회수가 없으면 그 파일은 영원히 지워지지 않는다.
     * "서버가 죽어도 다시 처리 가능"은 여기서 성립한다.
     */
    @Transactional
    public int reclaimStaleProcessing(Duration staleAfter) {
        List<StorageCleanupTask> stale =
                taskRepository.findStaleProcessing(LocalDateTime.now().minus(staleAfter));
        for (StorageCleanupTask task : stale) {
            task.reclaim();
            log.warn("[CLEANUP] PROCESSING에 멈춰 있던 작업을 회수한다. taskId={} key={}",
                    task.getId(), task.getObjectKey());
        }
        return stale.size();
    }

    @Transactional(readOnly = true)
    public long countByStatus(StorageCleanupTask.Status status) {
        return taskRepository.countByStatus(status);
    }
}
