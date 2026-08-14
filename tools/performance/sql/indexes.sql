-- ============================================================
-- photos 조회 인덱스
-- ============================================================
-- 근거: docs/evidence/2026-08-14-after-and-index.md
--
-- 왜 JPA @Table(indexes = ...) 로 선언하지 않았는가:
-- 아래 두 인덱스는 부분 인덱스(WHERE deleted = false), 정렬 방향(DESC),
-- 표현식(COALESCE)을 쓴다. JPA의 인덱스 DDL은 이 셋 중 어느 것도 표현하지 못한다.
-- 그래서 SQL로 따로 관리한다.
--
-- 적용 방법:
--   psql -U <user> -d <db> -f tools/performance/sql/indexes.sql
-- prod의 ddl-auto=validate는 테이블·컬럼만 검사하고 인덱스는 보지 않으므로
-- 이 인덱스를 따로 적용해도 기동을 막지 않는다.
--
-- ⚠️ 아직 Flyway/Liquibase 같은 마이그레이션 도구가 없다. 지금은 수동 절차이며,
--    도구 도입(P2-4)이 끝나면 이 파일을 정식 마이그레이션으로 옮겨야 한다.
--
-- CONCURRENTLY: 운영 중 테이블 잠금을 피한다. 트랜잭션 안에서는 쓸 수 없으므로
-- 이 파일은 BEGIN/COMMIT으로 감싸지 않는다.
-- ============================================================

-- ① 사진 목록 화면: WHERE user_id = ? AND deleted = false ORDER BY taken_at DESC LIMIT n
--    인덱스가 정렬된 순서를 그대로 제공하므로 LIMIT에서 즉시 멈춘다. 정렬 단계가 사라진다.
--    측정(사진 20만 장): Parallel Seq Scan 14.469ms → Index Scan 0.077ms
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_photos_user_taken
    ON photos (user_id, taken_at DESC)
    WHERE deleted = false;

-- ② 타임라인 기간 조회: COALESCE(taken_at, created_at) 범위 검색
--    "촬영일이 없으면 업로드일" 규칙을 그대로 인덱스로 만든 표현식 인덱스다.
--    일반 taken_at 인덱스는 이 조건에 쓰이지 않는다.
--    측정(사진 20만 장): Parallel Seq Scan 15.534ms → Index Scan 4.535ms
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_photos_user_effective_date
    ON photos (user_id, (COALESCE(taken_at, created_at)))
    WHERE deleted = false;

-- 비용: 사진 20만 장 기준 각각 약 6.2MB (테이블 30MB의 약 20%).
--       쓰기(업로드·삭제) 시 인덱스 갱신 비용이 추가된다.
