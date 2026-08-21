-- ============================================================
-- storage_cleanup_task — 운영 점검 쿼리
-- ============================================================
-- ⚠️ 테이블 생성 DDL은 여기 없다.
--    엔티티에서 자동 생성하는 tools/schema/sql/schema-postgres.sql 에 있다.
--
--    예전에는 이 파일에 CREATE TABLE 을 직접 적어 뒀는데,
--    엔티티가 바뀌면 두 곳이 어긋난다. 실제로 자동 생성본에는 있고
--    수동본에는 없던 것이 있었다 — status/reason 의 CHECK 제약이다.
--    스키마는 한 곳에서만 관리한다.
--
-- 이 테이블이 왜 있는가:
--   DB 트랜잭션은 S3를 롤백하지 못한다. 그래서 두 저장소 사이가 어긋나는 순간이 생긴다.
--     · 업로드: S3에 올렸는데 DB INSERT 실패 → 아무도 모르는 파일이 남는다
--     · 삭제  : 사진은 지웠는데 S3 삭제 실패 → 지울 키를 아는 코드가 사라진다
--   지워야 할 키를 여기에 적어 두고 워커가 재시도한다.
--
--   자세한 내용: docs/case-studies/10-storage-consistency.md
-- ============================================================


-- ── 상태별 현황 ─────────────────────────────────────────────
-- FAILED 가 0이 아니면 자동 복구를 포기한 파일이 있다는 뜻이다. 사람이 봐야 한다.
SELECT status, COUNT(*)
FROM storage_cleanup_task
GROUP BY status;


-- ── 자동 복구를 포기한 작업 ──────────────────────────────────
-- 무엇을, 왜 못 지웠는지
SELECT id, object_key, reason, retry_count, last_error, updated_at
FROM storage_cleanup_task
WHERE status = 'FAILED'
ORDER BY updated_at DESC;


-- ── 오래 밀려 있는 작업 ──────────────────────────────────────
-- S3 장애가 길어지고 있다는 신호
SELECT id, object_key, reason, retry_count, next_attempt_at, last_error
FROM storage_cleanup_task
WHERE status = 'PENDING'
  AND created_at < now() - INTERVAL '1 hour'
ORDER BY created_at;


-- ── 죽은 워커가 잡고 있는 작업 ───────────────────────────────
-- 워커가 회수하기 전에 직접 확인할 때
SELECT id, object_key, updated_at, now() - updated_at AS stuck_for
FROM storage_cleanup_task
WHERE status = 'PROCESSING'
  AND updated_at < now() - INTERVAL '10 minutes';


-- ── 완료된 작업 정리 ─────────────────────────────────────────
-- 이 테이블에는 보관 정책이 없다. COMPLETED 행이 계속 쌓인다.
-- 자동화하지 않았으므로 주기적으로 직접 실행한다.
-- DELETE FROM storage_cleanup_task
-- WHERE status = 'COMPLETED' AND updated_at < now() - INTERVAL '30 days';
