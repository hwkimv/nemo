---
title: DB 최소 권한 롤 분리 — 분석
status: Analysis only (변경 없음)
date: 2026-08-23
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

## 3. 가능한 안 — Flyway 를 어디서 돌릴 것인가가 갈림길입니다

### 먼저 확인한 제약

```yaml
# application-prod.yml — Flyway 에 별도 자격증명이 없습니다
spring:
  flyway:
    enabled: true
    baseline-on-migrate: true
    baseline-version: 0
```

`spring.flyway.user` / `password` / `url` 이 없으므로 **Flyway 는 `spring.datasource` 를 그대로 씁니다.**
이 한 줄이 아래 모든 선택지를 결정합니다.

> **`spring.datasource` 를 `nemo_app` 으로 바꾸는 순간 Flyway 도 `nemo_app` 으로 실행됩니다.**
> DML 전용 롤이면 그 다음 DDL 마이그레이션이 실패하고, 앱이 뜨지 않습니다.

### 그리고 소유권이 두 번째 제약입니다

PostgreSQL 에서 `ALTER` / `DROP` 은 **소유자(또는 소유 롤의 멤버, 슈퍼유저)만** 할 수 있습니다.
`GRANT` 로 줄 수 있는 권한이 아닙니다. 지금 12개 테이블은 전부 `postgres` 소유입니다.

여기서 두 방향이 갈립니다.

- **`nemo_app` 이 DDL 을 못 하게 하려면** → 소유권을 `postgres` 에 **그대로 두면 됩니다.** 이전이 필요 없습니다.
- **`nemo_migration` 이 DDL 을 하게 하려면** → 소유권을 `nemo_migration` 으로 **옮겨야 합니다.**

### 네 가지 안

| 기준 | **A. 현행** | **B. runtime/migration 롤 분리** | **C1. runtime 분리 + Flyway 별도 자격증명** | **C2. runtime 분리 + 마이그레이션 분리 실행** |
|---|---|---|---|---|
| 런타임 롤 | `postgres` | `nemo_app` (DML) | `nemo_app` (DML) | `nemo_app` (DML) |
| 마이그레이션 롤 | `postgres` | `nemo_migration` (DDL) | `postgres` | `postgres` |
| **런타임 서버에 상주하는 secret** | **1개** | **2개** | **2개** (DDL 권한 포함) | **1개** |
| Flyway 실행 방식 | 앱 기동 시, 앱 DataSource | 앱 기동 시, **별도 DataSource 필요** | 앱 기동 시, **`spring.flyway.*` 별도 지정** | **앱에서 끔.** 배포 전 별도 단계로 실행 |
| 테이블 소유권 | `postgres` (그대로) | **`nemo_migration` 으로 이전 필요** | **`postgres` 유지 — 이전 불필요** | **`postgres` 유지 — 이전 불필요** |
| 앱이 `DROP TABLE` 가능 | **가능** | 불가 | 불가 | 불가 |
| 배포 영향 | 없음 | 없음 (앱이 계속 마이그레이션) | 없음 (앱이 계속 마이그레이션) | **배포 절차가 바뀜** — "마이그레이션 먼저, 앱 나중" 단계 추가. [배포 스크립트](../../infra/deploy/README.md) 재설계 필요 |
| 되돌리기 | — | 어려움 (소유권 원복) | 보통 | 보통 |
| 실제 얻는 것 | — | 사고 반경 축소 + DDL 롤 분리 | **사고 반경 축소** | **사고 반경 축소 + 런타임에 DDL secret 없음** |

### 이전 판의 오류 (2차 리뷰 지적)

처음 이 문서를 쓸 때 C 안을 이렇게 적었습니다.

> ~~runtime = `nemo_app` · migration = `postgres` · **관리 secret 1개** · **Flyway 그대로** · **소유권 이전 필요**~~

**두 군데가 틀렸습니다.**

1. **"secret 1개 + Flyway 그대로"는 동시에 성립하지 않습니다.** Flyway 가 앱 DataSource 를
   공유하므로, 런타임을 `nemo_app` 으로 바꾸면 Flyway 도 `nemo_app` 이 됩니다.
   `postgres` 로 계속 돌리려면 자격증명이 하나 더 필요합니다(C1) 또는
   마이그레이션을 앱에서 떼어내야 합니다(C2). 위 표는 이 둘을 나눠 적었습니다.
2. **"소유권 이전 필요"는 반대였습니다.** `nemo_app` 에게서 DDL 을 뺏는 방법이
   **소유권을 `postgres` 에 그대로 두는 것**입니다. 이전이 필요한 쪽은 B 입니다.
   같은 문서의 SQL 초안에는 "소유권은 `postgres` 에 그대로 둔다"라고 맞게 적혀 있어
   표와 본문이 서로 어긋나 있었습니다.

---

## 4. 추천

> **C2 를 권장합니다. 단, 지금 실행하지 않습니다.**

C1 과 C2 는 얻는 보안 이점이 거의 같지만, **런타임 서버에 DDL 권한 secret 을 두느냐**가 다릅니다.

C1 은 `nemo.env` 에 `postgres` 자격증명을 평문으로 상주시킵니다. 그러면
"런타임에서 DDL 권한을 없앤다"는 목적이 절반만 달성됩니다 — 롤은 나눴는데
그 롤로 붙을 수 있는 열쇠가 같은 파일에 있습니다.
[CS 12](../case-studies/12-cloud-operation.md) 에서 "자격증명은 필요한 곳에만 둔다"고 판단한 것과도 어긋납니다.

C2 는 그 secret 이 **마이그레이션을 실행하는 시점에만** 필요합니다.

**왜 지금 안 하는가**

1. **배포 절차가 바뀝니다.** C2 는 "마이그레이션 먼저, 앱 나중" 단계를 새로 만듭니다.
   지금 막 rollback 을 검증해 둔 배포 스크립트를 다시 설계해야 합니다.
   이번 작업 범위가 아닙니다.
2. **재현 환경이 없습니다.** Supabase 관리 롤이 커스텀 롤·소유권과 어떻게 상호작용하는지
   확인하지 못했습니다. **운영 DB 에서 처음 시도하는 것은 안 됩니다.**
3. **얻는 것이 "피해 반경 축소"이고, 그 사고가 일어날 경로를 찾지 못했습니다.**
   위 2절에서 SQL 조립 지점을 전수 확인했고 알려진 인젝션 지점이 없습니다. 급하지 않습니다.

**언제 하는가 — 조건**

- Supabase 브랜치나 별도 프로젝트에서 **전 과정을 한 번 완주**했을 때
- 또는 `DB_PASSWORD` 가 서버 밖(팀원 PC, CI 등)으로 나갈 일이 생겼을 때
- 또는 SQL 을 문자열로 조립하는 코드가 실제로 들어왔을 때

**할 때의 순서 (검증 안 됨 — 초안입니다)**

```sql
-- ⚠️ 실행하지 않았습니다. 브랜치에서 먼저 완주해야 합니다.
CREATE ROLE nemo_app LOGIN PASSWORD '<...>';
GRANT USAGE ON SCHEMA public TO nemo_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO nemo_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO nemo_app;

-- 소유권은 postgres 에 그대로 둔다.
-- 이것이 nemo_app 에게서 DROP/ALTER 를 뺏는 방법이다. 이전하면 안 된다.

-- Flyway 가 앞으로 만들 테이블에도 권한이 붙어야 한다.
-- default privileges 는 '부여한 롤이 만든 객체'에만 적용되므로 FOR ROLE 을 명시한다.
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO nemo_app;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public
  GRANT USAGE, SELECT ON SEQUENCES TO nemo_app;
```

그리고 앱 설정에서 `spring.flyway.enabled: false` 로 바꾸고,
마이그레이션을 배포 전 단계로 옮깁니다(C2).

---

## 4-1. 아직 검증하지 않은 것

이 문서에서 **확인하지 못한 것**을 그대로 적습니다.

- **`ALTER DEFAULT PRIVILEGES FOR ROLE postgres` 가 실제로 Flyway 산출물에 걸리는지** — 문법상 맞지만 실행해 보지 않았습니다
- **Supabase 에서 `CREATE ROLE` 이 되는지.** `postgres` 에 `CREATEROLE` 이 있는 것은 확인했지만 실제로 만들어 보지 않았습니다
- **pooler 사용자명 형식(`nemo_app.<project-ref>`)이 커스텀 롤에서도 동작하는지.** 지금 접속은 `postgres.<ref>` 형식입니다
- **함수·트리거·확장에 대한 권한**을 훑지 않았습니다. 테이블과 시퀀스만 봤습니다
- **C2 의 마이그레이션 실행 단계를 어디에 둘지** — 배포 스크립트, CI, 수동 중 무엇이 맞는지 설계하지 않았습니다

---

## 5. 이번에 실제로 한 일

**없습니다.** 읽기 전용 조회만 했습니다.

```sql
SELECT rolname, rolsuper, rolcreatedb, rolcreaterole, rolbypassrls, rolcanlogin FROM pg_roles ...;
SELECT tablename, tableowner, has_table_privilege(...), rowsecurity FROM pg_tables ...;
SELECT version, description, success FROM flyway_schema_history;
```

권한 변경은 **승인 후** 별도 작업입니다.
