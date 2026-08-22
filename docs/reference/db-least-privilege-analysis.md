---
title: DB 최소 권한 롤 분리 — 분석
status: Analysis only (변경 없음)
date: 2026-08-22
---

# DB 최소 권한 롤 분리 — 분석

> **이 문서는 분석입니다. 운영 DB 권한을 바꾸지 않았습니다.**
> 아래 SQL은 전부 **읽기 전용 조회**였습니다. 실행 계획이 아니라 **판단 재료**입니다.

---

## 1. 지금 무엇으로 붙고 있나 — 확인한 사실

운영 Spring Boot는 **`postgres` 롤로 직접 붙습니다.**

```
DB_USER = postgres.<project-ref>        # Supabase pooler 형식: <role>.<ref>
DB_URL  = aws-0-ap-northeast-2.pooler.supabase.com:5432/postgres
```

그 롤의 실제 속성입니다 (`pg_roles`).

| 롤 | superuser | createdb | createrole | **bypassrls** | login |
|---|---|---|---|---|---|
| **`postgres`** ← 앱이 쓰는 롤 | ✗ | **✓** | **✓** | **✓** | ✓ |
| `supabase_admin` | ✓ | ✓ | ✓ | ✓ | ✓ |
| `authenticator` | ✗ | ✗ | ✗ | ✗ | ✓ |
| `anon` / `authenticated` / `service_role` | ✗ | ✗ | ✗ | service_role만 ✓ | ✗ |

그리고 **`public` 스키마의 12개 테이블 전부를 `postgres`가 소유합니다.**

```sql
SELECT tablename, tableowner FROM pg_tables WHERE schemaname='public';
-- album, album_favorite, album_photos, album_share, flyway_schema_history,
-- friend, photo_tag, photos, refresh_tokens, storage_cleanup_task, timeline, users
-- → tableowner 가 전부 postgres
```

> **테이블이 11개가 아니라 12개입니다.** [CS 12](../case-studies/12-cloud-operation.md)의 "11개 테이블"은
> Flyway가 `flyway_schema_history`를 만들기 **전**에 센 값입니다. 그 문서의 권한 회수 대상으로는 11개가 맞습니다.

`anon`/`authenticated` 권한 회수는 **지금도 유효합니다.** 12개 전부 `false`입니다.
RLS는 12개 전부 꺼져 있고, 이건 [CS 12](../case-studies/12-cloud-operation.md)에서
"RLS 대신 권한 회수"를 고른 결정과 일치합니다.

---

## 2. 그래서 무엇이 문제인가

`anon` 쪽 구멍은 막았습니다. 남은 것은 **앱 자신이 가진 권한이 너무 크다**는 것입니다.

앱은 평소 `SELECT / INSERT / UPDATE / DELETE`만 합니다.
그런데 `postgres`는 **자기가 소유한 테이블에 무엇이든 할 수 있습니다.**

| 앱이 실제로 필요한 것 | `postgres`가 할 수 있는 것 |
|---|---|
| `SELECT` / `INSERT` / `UPDATE` / `DELETE` | 좌측 전부 + **`DROP TABLE` · `TRUNCATE` · `ALTER TABLE`** |
| — | **`CREATE ROLE`** (새 로그인 롤 생성) |
| — | **`BYPASSRLS`** (앞으로 RLS를 켜도 무시) |
| — | `CREATE DATABASE` |

**차이가 벌어지는 지점은 하나입니다.** 앱에 SQL 인젝션이 있거나
`DB_PASSWORD`가 새면, 지금은 **데이터 유출로 끝나지 않고 스키마를 통째로 날릴 수 있습니다.**
`nemo_app` 롤이었다면 같은 사고가 "데이터를 읽고 쓸 수 있음"에서 멈춥니다.

> **다만 과장하지 않겠습니다.** 이건 **"지금 뚫려 있다"가 아니라
> "뚫렸을 때의 피해 반경"** 이야기입니다. `DB_PASSWORD` 유출 경로도 재현한 적이 없습니다.
>
> **SQL 조립 지점을 실제로 훑었습니다.**
>
> | 확인 | 결과 |
> |---|---|
> | `nativeQuery = true` | **0건** |
> | `createNativeQuery` | 2건 — `AlbumListingRepositoryImpl:75, 94` |
> | `JdbcTemplate` 직접 실행 | 1건 — `DbHealthController:22` (`"SELECT 1"` 상수) |
>
> 이 중 문자열을 이어 붙이는 곳은 한 군데입니다.
>
> ```java
> String sql = "SELECT t.album_id, t.role " + BASE
>         + " ORDER BY t." + sortField.column() + " " + direction + ", t.album_id ASC";
> ```
>
> **사용자 입력이 여기 닿지 않습니다.** `direction`은 `boolean`에서 나온 `"ASC"/"DESC"`이고,
> `sortField.column()`은 enum 상수가 들고 있는 리터럴(`"created_at"` / `"sort_name"`)입니다.
> 임의 문자열은 `AlbumSortField.from()`이 **두 값 중 하나로 접어 버리고, 모르는 값은
> `CREATED_AT`으로 떨어뜨립니다.** `WHERE` 절 값은 전부 `setParameter`로 바인딩됩니다.
>
> 즉 **현재 알려진 인젝션 지점은 없습니다.** 다만 `ORDER BY` 컬럼은 파라미터로 바인딩할 수
> 없어 구조상 조립이 남습니다. **enum이 그 안전을 떠받치고 있으므로, 나중에 정렬 필드를
> 늘릴 때 이 경계를 깨지 않는 것이 중요합니다.**

---

## 3. 세 가지 안

| 기준 | **A. 현행 (`postgres` 단일)** | **B. runtime / migration 분리** | **C. runtime만 분리** |
|---|---|---|---|
| 런타임 롤 | `postgres` | `nemo_app` (DML만) | `nemo_app` (DML만) |
| 마이그레이션 롤 | `postgres` | `nemo_migration` (DDL) | `postgres` 그대로 |
| 관리할 비밀값 | 1개 | **2개** | 1개 |
| 앱이 `DROP TABLE` 가능 | **가능** | 불가 | 불가 |
| Flyway 동작 | 그대로 | **설정 필요** (아래) | 그대로 |
| 소유권 이전 필요 | — | 필요 | 필요 |
| 운영 복잡도 | 최소 | **상** | 중 |
| 되돌리기 | — | 어려움 | 보통 |
| 실제 얻는 것 | — | 사고 반경 축소 + DDL 분리 | **사고 반경 축소** |

### B의 숨은 비용 — Flyway와 소유권

Flyway는 **런타임 앱과 같은 `DataSource`를 씁니다.** 마이그레이션 롤을 분리하려면
Spring에 두 번째 `DataSource`를 만들고 `spring.flyway.url/user/password`를 따로 줘야 합니다.
그러면 **비밀값이 하나 더 늘고**, 그 값은 지금 구조상 `nemo.env`에 평문으로 들어갑니다.
[CS 12](../case-studies/12-cloud-operation.md)에서 "자격증명은 필요한 곳에만 둔다"고 판단한 것과
방향이 어긋납니다 — DDL 권한이 있는 비밀값을 **런타임 서버에 상주**시키게 됩니다.

더 큰 문제는 소유권입니다. `nemo_app`이 DDL을 못 하게 하려면 테이블 소유자가
`nemo_app`이 **아니어야** 합니다. 그런데 지금은 12개 전부 `postgres` 소유이고,
Flyway가 만든 것도 `postgres` 소유입니다. 옮기려면 `ALTER TABLE ... OWNER TO`를
전 테이블에 돌려야 하고, **Supabase 관리 롤과의 상호작용을 확인하지 못했습니다.**

### C가 B보다 나은 이유

DDL 권한을 **런타임 서버에서 없애는 것**이 목적의 대부분입니다.
마이그레이션을 누가 실행하느냐는 그다음 문제입니다.

지금 배포 구조에서 Flyway는 **앱 기동 시** 돕니다
(`spring.flyway.enabled: true`, `baseline-on-migrate`). 즉 마이그레이션 롤을
분리하면 **앱이 스스로 마이그레이션할 수 없게 되고**, 배포 순서가 바뀝니다 —
"마이그레이션 먼저, 앱 나중"이라는 단계가 새로 생깁니다.
그건 [배포 스크립트](../../infra/deploy/README.md)를 다시 설계해야 한다는 뜻입니다.

**그 복잡도를 지금 지불할 근거가 없습니다.** 인스턴스 1대, 개발자 1명,
마이그레이션 1개(`V1`)입니다.

---

## 4. 추천

> **C안을 권장합니다. 단, 지금 실행하지 않습니다.**

**왜 지금 안 하는가**

1. **되돌리기가 비쌉니다.** 소유권 이전을 잘못하면 Flyway가 `V1` 이후를 못 얹고,
   그 상태에서 앱이 안 뜹니다. [CS 12](../case-studies/12-cloud-operation.md)에서 스키마/Flyway 충돌로
   **컨테이너 재시작 루프**를 이미 한 번 겪었습니다.
2. **재현 환경이 없습니다.** Supabase 관리 롤이 소유권 이전에 어떻게 반응하는지
   확인하지 못했습니다. **운영 DB에서 처음 시도하는 것은 안 됩니다.**
3. **얻는 것이 "피해 반경 축소"이고, 지금 그 사고가 일어날 경로를 찾지 못했습니다.**
   급하지 않습니다.

**언제 하는가 — 조건**

- Supabase 브랜치나 별도 프로젝트에서 **전 과정을 한 번 완주**했을 때
- 또는 `DB_PASSWORD`가 서버 밖(팀원 PC, CI 등)으로 나갈 일이 생겼을 때
- 또는 SQL을 문자열로 조립하는 코드가 실제로 들어왔을 때

**할 때의 순서 (검증 안 됨 — 초안입니다)**

```sql
-- ⚠️ 실행하지 않았습니다. 브랜치에서 먼저 완주해야 합니다.
CREATE ROLE nemo_app LOGIN PASSWORD '<...>';
GRANT USAGE ON SCHEMA public TO nemo_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO nemo_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO nemo_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO nemo_app;
-- 소유권은 postgres 에 그대로 둔다 → nemo_app 은 DROP/ALTER 를 할 수 없다
-- Flyway 는 계속 postgres 로 돌린다 (C안)
```

**확인해야 할 것 (하지 않았습니다)**

- `ALTER DEFAULT PRIVILEGES`가 **Flyway가 앞으로 만들 테이블**에도 걸리는가
  (Flyway는 `postgres`로 도는데, default privileges는 **부여한 롤이 만든 객체**에만 적용됩니다.
  `FOR ROLE postgres`를 명시해야 할 가능성이 큽니다)
- 시퀀스·함수 권한이 빠지지 않았는가
- pooler 사용자명 형식(`nemo_app.<ref>`)이 커스텀 롤에서도 동작하는가

---

## 5. 이번에 실제로 한 일

**없습니다.** 읽기 전용 조회만 했습니다.

```sql
SELECT rolname, rolsuper, rolcreatedb, rolcreaterole, rolbypassrls, rolcanlogin FROM pg_roles ...;
SELECT tablename, tableowner, has_table_privilege(...), rowsecurity FROM pg_tables ...;
SELECT version, description, success FROM flyway_schema_history;
```

권한 변경은 **승인 후** 별도 작업입니다.
