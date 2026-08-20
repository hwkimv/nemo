// backend/src/main/java/com/nemo/backend/domain/storage/entity/StorageCleanupTask.java
package com.nemo.backend.domain.storage.entity;

import com.nemo.backend.global.entity.BaseEntity;
import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * "이 S3 객체를 지워야 한다"는 사실을 <b>DB에 적어 둔 것</b>.
 *
 * <h3>왜 필요한가</h3>
 * DB 트랜잭션은 DB 안에서만 유효하다. S3는 롤백되지 않는다.
 * 그래서 다음 두 순간에 정합성이 깨진다.
 * <ul>
 *   <li>업로드: S3에 올렸는데 DB INSERT가 실패 → 아무도 모르는 파일이 남는다</li>
 *   <li>삭제: DB에서 사진을 지웠는데 S3 삭제가 실패 → 지울 키를 아는 코드가 없어진다</li>
 * </ul>
 *
 * <p>즉시 보상 삭제를 시도하는 것만으로는 부족하다. <b>그 보상마저 실패</b>하거나
 * 그 사이에 서버가 죽으면 정보가 통째로 사라진다.
 * 메모리 큐나 {@code @Async}도 같은 이유로 답이 아니다 — 프로세스와 함께 사라진다.
 *
 * <p>그래서 지워야 할 키를 <b>사진 상태 변경과 같은 트랜잭션에서</b> 이 테이블에 적는다.
 * 사진 삭제가 커밋되면 정리 작업도 반드시 함께 커밋된다. 둘은 같이 살고 같이 죽는다.
 * 이후 실제 S3 삭제는 워커가 맡는다. 서버가 재시작돼도 행은 남아 있으므로 이어서 처리된다.
 *
 * <h3>상태</h3>
 * <pre>
 *   PENDING     처리 대기. nextAttemptAt이 지나야 집어간다(backoff).
 *   PROCESSING  워커가 집어간 상태. 오래 머물면 죽은 워커의 흔적으로 보고 회수한다.
 *   COMPLETED   S3에서 사라진 것이 확인됨.
 *   FAILED      최대 재시도를 넘김. 자동 처리를 포기하고 사람이 본다.
 * </pre>
 *
 * <p>설계 초안에는 {@code RETRY_WAIT}가 따로 있었지만 넣지 않았다.
 * "대기 중"은 {@code status=PENDING} + {@code nextAttemptAt > now}로 이미 표현된다.
 * 같은 뜻을 두 곳에 적으면 둘이 어긋날 수 있다.
 *
 * <p>{@code operation} 컬럼도 넣지 않았다. 지금 필요한 동작은 삭제 하나뿐이라
 * 값이 하나뿐인 컬럼이 된다. 업로드 재시도 같은 다른 동작이 실제로 생기면 그때 추가한다.
 */
@Entity
@Table(
        name = "storage_cleanup_task",
        indexes = {
                // 워커가 "지금 처리할 것"을 찾는 조건 그대로다.
                @Index(name = "idx_cleanup_status_next_attempt", columnList = "status, next_attempt_at")
        }
)
public class StorageCleanupTask extends BaseEntity {

    public enum Status {
        PENDING, PROCESSING, COMPLETED, FAILED
    }

    /** 왜 이 정리 작업이 생겼는지. 장애를 되짚을 때만 쓰고 처리 로직은 이 값을 보지 않는다. */
    public enum Reason {
        /** S3에 올렸는데 DB 저장이 실패했다 */
        UPLOAD_ROLLBACK,
        /** 사진이 삭제됐으니 파일도 지워야 한다 */
        PHOTO_DELETED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 지워야 할 S3 객체 키 (예: albums/2026-08-20/xxx.webp) */
    @Column(name = "object_key", nullable = false, length = 512)
    private String objectKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Reason reason;

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    /** 이 시각 전에는 다시 시도하지 않는다. backoff가 여기에 들어간다. */
    @Column(name = "next_attempt_at", nullable = false)
    private LocalDateTime nextAttemptAt = LocalDateTime.now();

    /** 마지막 실패 원인. 왜 못 지웠는지 남기지 않으면 FAILED가 되어도 손쓸 수 없다. */
    @Column(name = "last_error", length = 500)
    private String lastError;

    protected StorageCleanupTask() {
    }

    public StorageCleanupTask(String objectKey, Reason reason) {
        this.objectKey = objectKey;
        this.reason = reason;
    }

    // ─────────────────────── 상태 전이 ───────────────────────

    /**
     * 만들어질 때 이미 알고 있는 실패 원인을 적어 둔다.
     *
     * <p>{@code retryCount}는 <b>워커가 시도한 횟수</b>다.
     * 즉시 보상 삭제가 실패해서 이 행이 생긴 것은 워커의 시도가 아니므로 세지 않는다.
     * 여기서 횟수를 올려 버리면 워커가 쓸 수 있는 재시도가 한 번 줄어든다.
     */
    public void recordInitialError(String error) {
        this.lastError = truncate(error);
    }

    /** 워커가 집어간다. 여러 워커가 같은 작업을 잡지 않도록 하는 것은 조회 쪽 잠금이 맡는다. */
    public void markProcessing() {
        this.status = Status.PROCESSING;
    }

    public void markCompleted() {
        this.status = Status.COMPLETED;
        this.lastError = null;
    }

    /**
     * 실패했으니 나중에 다시 시도한다.
     *
     * @param backoff  다음 시도까지 기다릴 시간
     * @param error    실패 원인 (길면 잘라 넣는다)
     */
    public void markRetry(java.time.Duration backoff, String error) {
        this.status = Status.PENDING;
        this.retryCount++;
        this.nextAttemptAt = LocalDateTime.now().plus(backoff);
        this.lastError = truncate(error);
    }

    /** 더 시도하지 않는다. 무한 재시도는 장애를 숨기고 자원만 태운다. */
    public void markFailed(String error) {
        this.status = Status.FAILED;
        this.retryCount++;
        this.lastError = truncate(error);
    }

    /** 죽은 워커가 PROCESSING으로 잡아 둔 것을 되돌린다. */
    public void reclaim() {
        this.status = Status.PENDING;
        this.nextAttemptAt = LocalDateTime.now();
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() <= 500 ? s : s.substring(0, 500);
    }

    // ─────────────────────── getters ───────────────────────

    public Long getId() { return id; }
    public String getObjectKey() { return objectKey; }
    public Status getStatus() { return status; }
    public Reason getReason() { return reason; }
    public int getRetryCount() { return retryCount; }
    public LocalDateTime getNextAttemptAt() { return nextAttemptAt; }
    public String getLastError() { return lastError; }
}
