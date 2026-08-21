-- ============================================================
-- storage_cleanup_task — S3에서 지워야 할 파일을 적어 두는 테이블
-- ============================================================
-- 근거: docs/case-studies/10-storage-consistency.md
--
-- 왜 필요한가:
--   DB 트랜잭션은 S3를 롤백하지 못한다. 그래서 두 저장소 사이가 어긋나는 순간이 생긴다.
--     · 업로드: S3에 올렸는데 DB INSERT 실패 → 아무도 모르는 파일이 남는다
--     · 삭제  : 사진은 지웠는데 S3 삭제 실패 → 지울 키를 아는 코드가 사라진다
--   지워야 할 키를 여기에 적어 두고 워커가 재시도한다.
--   메모리 큐나 @Async로는 안 된다 — 서버가 죽으면 작업도 함께 사라진다.
--
-- ⚠️ 아직 Flyway/Liquibase 같은 마이그레이션 도구가 없다.
--    dev/local은 ddl-auto=update가 이 테이블을 자동으로 만들지만,
--    prod는 ddl-auto=validate이므로 배포 전에 이 파일을 먼저 적용해야 한다.
--    적용하지 않으면 애플리케이션이 기동하지 않는다(엔티티와 스키마 불일치).
--
-- 적용 방법:
--   psql -U <user> -d <db> -f tools/storage/sql/storage-cleanup-task.sql
-- ============================================================

CREATE TABLE IF NOT EXISTS storage_cleanup_task (
    id              BIGSERIAL     PRIMARY KEY,

    -- 지워야 할 S3 객체 키 (예: albums/2026-08-20/xxx.webp)
    object_key      VARCHAR(512)  NOT NULL,

    -- PENDING / PROCESSING / COMPLETED / FAILED
    --   PENDING     처리 대기. next_attempt_at이 지나야 집어간다(backoff).
    --   PROCESSING  워커가 집어간 상태. 오래 머물면 죽은 워커의 흔적으로 보고 회수한다.
    --   COMPLETED   S3에서 사라진 것이 확인됨.
    --   FAILED      최대 재시도를 넘김. 자동 처리를 포기하고 사람이 본다.
    status          VARCHAR(20)   NOT NULL,

    -- UPLOAD_ROLLBACK / PHOTO_DELETED
    -- 왜 이 작업이 생겼는지. 장애를 되짚을 때만 쓰고 처리 로직은 보지 않는다.
    reason          VARCHAR(30)   NOT NULL,

    -- 워커가 시도한 횟수. 즉시 보상 삭제의 실패는 포함하지 않는다.
    retry_count     INTEGER       NOT NULL DEFAULT 0,

    -- 이 시각 전에는 다시 시도하지 않는다. 지수 backoff가 여기 반영된다.
    next_attempt_at TIMESTAMP     NOT NULL,

    -- 마지막 실패 원인. 남기지 않으면 FAILED가 되어도 손쓸 수 없다.
    last_error      VARCHAR(500),

    created_at      TIMESTAMP     NOT NULL,
    updated_at      TIMESTAMP     NOT NULL
);

-- 워커가 "지금 처리할 것"을 찾는 조건 그대로다.
--   WHERE status = 'PENDING' AND next_attempt_at <= now ORDER BY next_attempt_at, id
CREATE INDEX IF NOT EXISTS idx_cleanup_status_next_attempt
    ON storage_cleanup_task (status, next_attempt_at);


-- ============================================================
-- 운영 점검용 조회
-- ============================================================

-- 상태별 현황. permanently failed가 0이 아니면 사람이 봐야 한다.
--   SELECT status, COUNT(*) FROM storage_cleanup_task GROUP BY status;

-- 자동 복구를 포기한 작업 — 무엇을, 왜 못 지웠는지
--   SELECT id, object_key, retry_count, last_error, updated_at
--   FROM storage_cleanup_task
--   WHERE status = 'FAILED'
--   ORDER BY updated_at DESC;

-- 오래 밀려 있는 작업 (S3 장애가 길어지고 있다는 신호)
--   SELECT id, object_key, retry_count, next_attempt_at, last_error
--   FROM storage_cleanup_task
--   WHERE status = 'PENDING' AND created_at < now() - INTERVAL '1 hour'
--   ORDER BY created_at;

-- 완료된 작업 정리 (테이블이 무한히 커지지 않도록 주기적으로)
--   DELETE FROM storage_cleanup_task
--   WHERE status = 'COMPLETED' AND updated_at < now() - INTERVAL '30 days';
