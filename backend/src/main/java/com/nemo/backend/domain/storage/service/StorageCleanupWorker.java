// backend/src/main/java/com/nemo/backend/domain/storage/service/StorageCleanupWorker.java
package com.nemo.backend.domain.storage.service;

import com.nemo.backend.domain.storage.entity.StorageCleanupTask;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 밀린 정리 작업을 주기적으로 처리한다.
 *
 * <p>즉시 보상이 실패했거나, 삭제 흐름에서 예약해 둔 작업이 여기서 실제로 지워진다.
 * 서버가 재시작돼도 작업은 DB에 남아 있으므로 다음 주기에 이어서 처리된다.
 * 이것이 {@code @Async}·메모리 큐와 결정적으로 다른 점이다.
 *
 * <p><b>테스트에서는 꺼 둔다.</b> 워커가 배경에서 돌면 테스트가 기대한 상태를
 * 워커가 먼저 바꿔 버려 결과가 들쭉날쭉해진다.
 * 정리 로직 자체는 {@link StorageCleanupService}를 직접 불러 검증한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.storage.cleanup.enabled", havingValue = "true", matchIfMissing = true)
public class StorageCleanupWorker {

    private final StorageCleanupService cleanupService;
    private final int batchSize;

    public StorageCleanupWorker(
            StorageCleanupService cleanupService,
            MeterRegistry meterRegistry,
            @Value("${app.storage.cleanup.batch-size:20}") int batchSize
    ) {
        this.cleanupService = cleanupService;
        this.batchSize = batchSize;

        // 카운터는 "얼마나 일어났는가"만 알려준다. "지금 얼마나 밀려 있는가"는 게이지여야 한다.
        // 이 값이 계속 오르면 S3가 죽었거나 워커가 못 따라가고 있다는 뜻이다.
        Gauge.builder("storage.cleanup.tasks.pending",
                        () -> cleanupService.countByStatus(StorageCleanupTask.Status.PENDING))
                .description("처리를 기다리는 정리 작업 수")
                .register(meterRegistry);

        // 자동 복구를 포기한 작업. 0이 아니면 사람이 봐야 한다.
        Gauge.builder("storage.cleanup.tasks.permanently_failed",
                        () -> cleanupService.countByStatus(StorageCleanupTask.Status.FAILED))
                .description("최대 재시도를 넘겨 남아 있는 정리 작업 수 — 수동 확인 필요")
                .register(meterRegistry);
    }

    /**
     * 밀린 작업을 처리한다.
     *
     * <p>{@code fixedDelay}를 쓴다. {@code fixedRate}는 앞 실행이 끝나기 전에 다음 실행을
     * 시작할 수 있어, S3가 느릴 때 실행이 겹쳐 쌓인다.
     */
    @Scheduled(
            fixedDelayString = "${app.storage.cleanup.interval-ms:60000}",
            initialDelayString = "${app.storage.cleanup.initial-delay-ms:10000}")
    public void processPending() {
        try {
            int processed = cleanupService.processPending(batchSize);
            if (processed > 0) {
                log.info("[CLEANUP][워커] {}건 처리", processed);
            }
        } catch (Exception e) {
            // 여기서 예외가 밖으로 나가면 스케줄러가 이 작업을 다시 잡지 않을 수 있다.
            log.error("[CLEANUP][워커] 배치 처리 중 예외", e);
        }
    }

    /**
     * 죽은 워커가 PROCESSING으로 잡아 둔 작업을 회수한다.
     *
     * <p>처리 주기보다 훨씬 드물게 돈다. 정상 처리 중인 작업을 성급하게 뺏으면
     * 같은 삭제를 두 번 부르게 된다(결과는 같지만 재시도 횟수가 헛되이 오른다).
     */
    @Scheduled(
            fixedDelayString = "${app.storage.cleanup.reclaim-interval-ms:300000}",
            initialDelayString = "${app.storage.cleanup.reclaim-initial-delay-ms:300000}")
    public void reclaimStale() {
        try {
            int reclaimed = cleanupService.reclaimStaleProcessing();
            if (reclaimed > 0) {
                log.warn("[CLEANUP][워커] 멈춰 있던 작업 {}건 회수", reclaimed);
            }
        } catch (Exception e) {
            log.error("[CLEANUP][워커] 회수 중 예외", e);
        }
    }
}
