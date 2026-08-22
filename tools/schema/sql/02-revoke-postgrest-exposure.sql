-- ============================================================
-- PostgREST 노출 차단
-- ============================================================
-- 적용: 2026-08-21, Supabase 프로젝트 nemo (uzdzvqmqfqvltchmlxwl)
-- 마이그레이션 이름: revoke_postgrest_exposure
--
-- ⚠️ 스키마를 적용한 직후 반드시 이것도 적용해야 한다.
--    schema-postgres.sql 만 돌리면 DB 전체가 인터넷에 열린 상태가 된다.
--
-- 무엇이 문제였나:
--   Supabase 는 public 스키마를 PostgREST 로 자동 노출한다.
--   테이블을 만든 직후 확인해 보니 11개 전부 이랬다.
--     anon_select = true, anon_insert = true, authenticated_select = true
--
--   anon 키는 프론트엔드에 박혀 나가는 공개 값이라 비밀이 아니다.
--   그대로 두면 anon 키를 아는 사람이 브라우저에서 이것만으로 가져간다.
--     GET  /rest/v1/users?select=*            비밀번호 해시·이메일
--     GET  /rest/v1/refresh_tokens?select=*   세션 토큰 전부
--     POST /rest/v1/photos                    임의 삽입
--
--   CS 03 에서 로그의 토큰을 지웠는데 DB 가 통째로 열려 있으면 의미가 없다.
--
-- 왜 RLS 가 아니라 권한 회수인가:
--   NEMO 는 Spring Boot 가 postgres 롤로 직접 붙는다.
--   Supabase Auth 도 클라이언트 SDK 도 쓰지 않는다.
--   즉 PostgREST 경로는 우리가 쓰지 않는 문이다.
--   쓰지 않는 문은 정책으로 지키는 것보다 닫는 편이 확실하다.
--   RLS 를 고르면 테이블 11개에 정책을 만들고 계속 유지해야 하는데,
--   그 정책이 보호하는 접근 경로 자체가 존재하지 않는다.
--
--   ⚠️ 나중에 Flutter 에서 Supabase 클라이언트를 직접 쓰게 되면
--      이 결정을 뒤집고 RLS 로 가야 한다.
-- ============================================================

REVOKE ALL ON ALL TABLES    IN SCHEMA public FROM anon, authenticated;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM anon, authenticated;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA public FROM anon, authenticated;
REVOKE USAGE ON SCHEMA public FROM anon, authenticated;

-- 앞으로 만들 객체에도 자동 적용한다.
-- 이게 없으면 다음 마이그레이션에서 만든 테이블이 다시 열린다.
ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE ALL ON TABLES    FROM anon, authenticated;
ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE ALL ON SEQUENCES FROM anon, authenticated;
ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE ALL ON FUNCTIONS FROM anon, authenticated;


-- ── 적용 후 검증 ─────────────────────────────────────────────
-- anon/authenticated 에게 남은 테이블 권한이 0건이어야 한다.
--
--   select grantee, table_name, privilege_type
--   from information_schema.role_table_grants
--   where table_schema='public' and grantee in ('anon','authenticated');
--
-- 앱 롤은 그대로 접근되는지도 같이 본다.
--
--   select c.relname,
--          has_table_privilege('anon',     'public.'||c.relname, 'SELECT') as anon_select,
--          has_table_privilege('postgres', 'public.'||c.relname, 'SELECT') as app_select
--   from pg_class c join pg_namespace n on n.oid=c.relnamespace
--   where n.nspname='public' and c.relkind='r';
--
-- 실측(2026-08-21): anon_select 전부 false, app_select 전부 true, 남은 권한 0건.
--
-- 참고: has_schema_privilege('anon','public','USAGE') 는 여전히 true 로 나온다.
--       USAGE 가 PUBLIC 의사 롤에 부여돼 있어서다.
--       테이블 권한이 없으면 스키마 USAGE 만으로는 아무것도 읽지 못하므로 그대로 뒀다.
--       PUBLIC 에서 회수하면 Supabase 내부 롤에 영향이 갈 수 있다.


-- ── 되돌리기 ─────────────────────────────────────────────────
-- GRANT USAGE ON SCHEMA public TO anon, authenticated;
-- GRANT SELECT ON ALL TABLES IN SCHEMA public TO anon, authenticated;   -- 필요한 만큼만
