# 앨범/타임라인 개선 After 측정과 인덱스 판단

**상태:** Verified

**측정일:** 2026-08-14

**대상:** N+1 제거(P1-3)와 타임라인 기간 조회(P1-4) 적용 후 성능, 그리고 인덱스 필요 여부

**Before 정본:** [`2026-08-05-baseline.md`](2026-08-05-baseline.md)

---

## 1. 30초 요약

| 항목 | Before | After | 변화 |
|---|---:|---:|---|
| 앨범 목록 SQL 수 | **202** | **4** | -98% |
| 앨범 목록 HTTP avg | **98.91ms** | **10.91ms** | **-89%** |
| 앨범 목록 HTTP p95 | **114.20ms** | **15.19ms** | -87% |
| 타임라인 HTTP avg | 8.27ms | 4.40ms | -47% |
| 사진 목록 HTTP avg (대조군) | 4.72ms | 4.22ms | 변화 없음 |
| 앨범 1페이지 (앨범 5,000개일 때) | 74.26ms | **8.10ms** | 앨범 수와 무관하게 고정 |

**사진 목록은 코드를 바꾸지 않았고 실제로 수치도 그대로다.** 이 대조군이 평평하기 때문에
앨범·타임라인의 개선을 코드 변경 덕분이라고 말할 수 있다.

인덱스는 **지금 데이터(사진 1,000장)에서는 효과가 미미하지만, 20만 장에서는 결정적**이다.
근거를 남기고 SQL은 준비했지만, 마이그레이션 도구가 없어 자동 적용은 하지 않았다.

---

## 2. 이 측정이 baseline과 다른 점 (먼저 밝힘)

2026-08-05 baseline은 **9일 전 다른 시점의 머신 상태**에서 측정됐다.
그 값과 오늘 값을 직접 빼면 환경 차이까지 개선으로 계산된다.

실제로 그런 일이 일어났다. 오늘 After를 baseline과 직접 비교하면:

| API | 2026-08-05 avg | 오늘 After avg | 겉보기 배율 |
|---|---:|---:|---:|
| 앨범 | 121.21ms | 10.91ms | 11.1배 |
| 사진 | 15.13ms | 4.22ms | **3.6배** |
| 타임라인 | 18.31ms | 4.40ms | 4.2배 |

**사진 목록은 이번에 코드를 한 줄도 바꾸지 않았는데 3.6배 빨라졌다.**
즉 환경 자체가 그만큼 빨라진 것이고, 앨범의 11.1배 중 상당 부분이 환경 몫이다.

그래서 **같은 세션·같은 DB·같은 시드에서 이전 코드를 다시 측정**했다.

- 이전 코드는 P1-3(54de4e5)과 P1-4(95bf844) 커밋만 revert한 worktree로 준비했다.
- 벤치마크 하네스와 그 외 모든 코드는 동일하다.
- 이 문서의 Before/After 표는 전부 이 **같은 환경 측정값**이다.

Before 앨범 avg 98.91ms는 baseline 121.21ms와 가까워, baseline 자체는 재현 가능함을 보여준다.

---

## 3. 실행 환경

| 항목 | 조건 |
|---|---|
| DB | Docker PostgreSQL **17.10**, `nemo_benchmark` (baseline과 동일 버전) |
| 데이터 | 사용자 100, 앨범 100, 사진 1,000, 공유 20, 즐겨찾기 20 |
| 측정 사용자 | ID 1, `benchmark-target@nemo.local` |
| HTTP 부하 | API별 1 VU × 30회, 시작 시간 분리 (baseline과 동일한 k6 스크립트) |
| 반복 | 서비스 Query 3회, k6 3회 |
| k6 | v2.2.0 |

### baseline 절차와 달라진 점

1. **`seed.sql`에 `login_fail_count` 컬럼을 추가**했다.
   origin/dev의 로그인 보안 작업이 `users`에 NOT NULL 컬럼을 추가했는데,
   기존 시드가 그 컬럼을 채우지 않아 `ddl-auto=update`가 컬럼 추가에 실패하고 있었다.
   (이미 행이 있는 테이블에 NOT NULL 컬럼은 붙지 않는다)

2. **LocalStack 대신 최소 S3 스텁**을 썼다. Docker Desktop의 포트 포워딩이 고장나
   4566 포트를 열 수 없었다. 측정 대상 3개 API는 S3를 전혀 호출하지 않으며,
   `S3PhotoStorage`가 기동 시 `headBucket()` 한 번 부르는 것만 통과하면 된다.
   → **측정값에는 영향이 없다.**

> 부수적으로 발견한 것: S3에 연결되지 않으면 앱이 아예 기동하지 않는다.
> 읽기 전용 API만 쓰는 상황에서도 그렇다. 별도 과제로 기록해둔다.

---

## 4. 서비스 계층 Query 수 (3회)

Hibernate Statistics의 prepared statement 수. 인증과 HTTP 직렬화는 제외.

| API | Before Query | After Query | Before 시간 평균 | After 시간 평균 |
|---|---:|---:|---:|---:|
| **앨범 목록** | **202 / 202 / 202** | **4 / 4 / 4** | 298.0ms | 73.3ms |
| 사진 목록 | 2 / 2 / 2 | 2 / 2 / 2 | 23.7ms | 16.3ms |
| 타임라인 | 1 / 1 / 1 | 1 / 1 / 1 | 158.7ms | 83.3ms |

앨범 202개의 내역과 4개로 줄어든 구조는 개선 문서를 참고한다.

타임라인은 **Query 수가 1개로 같지만 읽는 행이 다르다.** 이전에는 1,000행을 모두
읽어 JVM에서 버렸고, 지금은 해당 월 93행만 읽는다.

---

## 5. k6 HTTP 측정 (3회, 같은 환경)

세 실행 모두 checks 91개 전부 통과, `http_req_failed` 0%.

### 앨범 목록 `GET /api/albums?ownership=OWNED&page=0&size=10`

| 지표 | 1회 | 2회 | 3회 | 평균 |
|---|---:|---:|---:|---:|
| Before avg | 111.03 | 91.61 | 94.09 | **98.91ms** |
| After avg | 14.38 | 10.46 | 7.88 | **10.91ms** |
| Before p95 | 152.11 | 93.58 | 96.91 | **114.20ms** |
| After p95 | 19.70 | 16.38 | 9.49 | **15.19ms** |

### 사진 목록 (대조군 — 코드 변경 없음)

| 지표 | 1회 | 2회 | 3회 | 평균 |
|---|---:|---:|---:|---:|
| Before avg | 5.77 | 4.73 | 3.67 | **4.72ms** |
| After avg | 5.16 | 4.09 | 3.41 | **4.22ms** |

차이는 측정 잡음 범위다. **대조군이 평평하다는 것이 이 측정의 신뢰 근거다.**

### 타임라인 `GET /api/timeline?year=2025&month=1`

| 지표 | 1회 | 2회 | 3회 | 평균 |
|---|---:|---:|---:|---:|
| Before avg | 8.82 | 7.82 | 8.17 | **8.27ms** |
| After avg | 5.93 | 3.82 | 3.45 | **4.40ms** |
| Before p95 | 10.39 | 9.85 | 9.28 | **9.84ms** |
| After p95 | 9.43 | 4.35 | 3.97 | **5.92ms** |

> 낮은 동시성의 로컬 측정이다. 최대 처리량이나 운영 지연시간을 뜻하지 않는다.

---

## 6. 실행 계획 (EXPLAIN ANALYZE)

### 6-1. 새로 생긴 앨범 목록 쿼리

앨범들의 살아있는 사진을 한 번에 가져오는 쿼리 (N+1을 없애며 도입한 것):

```
Hash Join  (actual time=0.389..0.743 rows=1000)
  -> Seq Scan on album_photos (rows=1000)
  -> Seq Scan on photos (Filter: NOT deleted, rows=1000)
  -> Seq Scan on album (Filter: user_id = 1, rows=100)
Buffers: shared hit=35
Execution Time: 0.981 ms
```

세 테이블 모두 Seq Scan이지만 **이 크기에서는 그게 최적**이다. 총 35페이지만 읽는다.
예상 행 수와 실제 행 수가 정확히 일치해(1000/1000, 100/100) 통계도 정상이다.

**DB는 병목이 아니다.** 실행이 1ms인데 HTTP 응답은 10.91ms다. 나머지는 JVM·직렬화·네트워크다.

### 6-2. 타임라인: 기간 조회로 바꾼 효과

| | 읽은 행 | 정렬 대상 | 정렬 메모리 | 실행시간 |
|---|---:|---:|---:|---:|
| Before (전체 조회) | 1,000 | 1,000 | 204kB | 0.520ms |
| After (기간 조회) | 1,000 스캔 → **93 반환** | **93** | 41kB | **0.148ms** |

인덱스가 없어 Seq Scan은 여전히 1,000행을 훑지만, **정렬과 JVM으로 넘기는 행이
1,000 → 93으로 줄었다.** 실제 이득은 이 지점이다.

---

## 7. 인덱스 판단

> 원칙: 인덱스를 먼저 만들지 않는다. 실행 계획에서 병목을 확인한 뒤 결정한다.

1,000행에서는 어떤 쿼리도 1ms를 넘지 않아 판단 근거가 부족했다.
그래서 **사진 20만 장을 넣고(사용자 50, 기존 시드는 건드리지 않음) 다시 비교**했다.

### 측정 결과

| 쿼리 | 데이터 | 인덱스 없음 | 인덱스 있음 | 배율 |
|---|---|---:|---:|---:|
| 사진 목록 첫 페이지 | 1,000장 | Seq Scan | Index Scan 0.069ms | — |
| 사진 목록 첫 페이지 | **20만장** | Parallel Seq Scan **14.469ms** | Index Scan **0.077ms** | **188배** |
| 타임라인 한 달 | 1,000장 | Seq Scan 0.148ms | Bitmap Index 0.138ms | 차이 없음 |
| 타임라인 한 달 | **20만장** | Parallel Seq Scan **15.534ms** | Index Scan **4.535ms** | **3.4배** |

### 왜 이런 차이가 나는가

**사진 목록(188배)** — `WHERE user_id AND deleted=false ORDER BY taken_at DESC LIMIT 20`.
인덱스가 이미 정렬된 순서를 주므로 **20건을 읽고 즉시 멈춘다.** 정렬 단계가 통째로 사라진다.
인덱스가 없으면 20만 행을 전부 읽고 정렬해서 상위 20개만 쓰고 버린다.

**타임라인(3.4배)** — 범위 검색은 인덱스로 걸러지지만, `ORDER BY taken_at DESC` 정렬은
여전히 남는다(인덱스 키는 `COALESCE(taken_at, created_at)`이라 정렬 순서가 다르다).
그래서 이득이 사진 목록만큼 극적이지 않다.

### 사용한 인덱스

```sql
CREATE INDEX ix_photos_user_taken
    ON photos (user_id, taken_at DESC) WHERE deleted = false;

CREATE INDEX ix_photos_user_effective_date
    ON photos (user_id, (COALESCE(taken_at, created_at))) WHERE deleted = false;
```

두 번째는 **표현식 인덱스**다. "촬영일이 없으면 업로드일" 규칙을 그대로 인덱스로 만들었다.
평범한 `taken_at` 인덱스는 `COALESCE(...)` 조건에 쓰이지 않는다.

### Trade-off

| 항목 | 값 |
|---|---|
| 인덱스 용량 | 각 **6.2MB** / 사진 20만 장 (테이블 30MB의 약 20%) |
| 쓰기 비용 | 업로드·삭제마다 인덱스 2개 추가 갱신 |
| 부분 인덱스 효과 | `WHERE deleted = false`라 삭제된 사진은 인덱스에 없다 |

### 결론

**두 인덱스 모두 정당하다.** 특히 `ix_photos_user_taken`은 가장 많이 쓰는 화면(사진 목록)을
받치고, 사진이 쌓일수록 이득이 커진다. 사진 서비스에서 사진은 줄지 않는다.

**다만 이번에 자동 적용하지는 않았다.**

- JPA `@Table(indexes = ...)`는 부분 인덱스·DESC·표현식을 **표현할 수 없다.**
- 현재 저장소에 Flyway/Liquibase 같은 마이그레이션 도구가 없다.
- prod는 `ddl-auto=validate`인데, validate는 인덱스를 검사하지 않으므로
  수동 적용해도 기동을 막지는 않는다.

→ SQL은 [`tools/performance/sql/indexes.sql`](../../tools/performance/sql/indexes.sql)에 두고,
마이그레이션 도구 도입(P2-4)이 끝나면 정식 마이그레이션으로 옮긴다.

---

## 8. 재현 순서

```bash
docker compose --profile benchmark up -d postgres-benchmark
docker compose exec -T postgres-benchmark psql -U nemo_benchmark -d nemo_benchmark < tools/performance/sql/seed.sql
docker compose exec -T postgres-benchmark psql -U nemo_benchmark -d nemo_benchmark < tools/performance/sql/verify-seed.sql

# 서비스 계층 Query 수
cd backend
SPRING_PROFILES_ACTIVE=benchmark ./gradlew performanceBaseline --rerun-tasks --no-daemon
cd ..

# HTTP 측정 (benchmark 프로필 서버가 떠 있어야 한다)
k6 run -e BASE_URL=http://localhost:8080 tools/performance/k6/baseline.js

# 실행 계획
docker compose exec -T postgres-benchmark psql -U nemo_benchmark -d nemo_benchmark < tools/performance/sql/explain.sql
```

같은 환경 Before를 다시 만들려면 P1-3·P1-4 커밋만 revert한 worktree를 쓴다.

```bash
git worktree add /tmp/before-wt HEAD --detach
cd /tmp/before-wt && git revert --no-commit <P1-3 SHA> <P1-4 SHA>
```

---

## 8-1. 앨범 목록 DB 페이지네이션

N+1을 없앤 뒤에도 `AlbumController`는 전체 목록을 만든 뒤 메모리에서 정렬하고
`subList()`로 잘랐다. 1페이지만 봐도 앨범 전부와 그 사진 정보를 메모리에 올리는 구조다.

정렬·페이징을 DB로 옮긴 뒤, **앨범 수를 늘려가며 1페이지 응답시간**을 비교했다.
(같은 서버·같은 DB, `page=0&size=10`, 워밍업 3회 후 20회 평균)

| 앨범 수 | 메모리 페이징 (이전) | DB 페이징 (이후) |
|---:|---:|---:|
| 100개 | 18.57ms | **8.10ms** |
| 5,000개 | **74.26ms** | **8.10ms** |
| 5,000개 중 마지막 페이지(499) | — | 8.47ms |

**핵심은 배율이 아니라 기울기다.** 앨범이 50배가 되는 동안
메모리 페이징은 4배 느려졌고, DB 페이징은 **변하지 않았다.**
마지막 페이지도 첫 페이지와 같다.

100개 규모에서는 차이가 작다(18.57 → 8.10ms). k6 측정에서도 9.83ms로
N+1만 고친 상태(10.91ms)와 사실상 같았다. **지금 데이터에서는 병목이 아니었다.**
이 변경의 값어치는 현재 수치가 아니라 데이터가 늘어날 때 드러난다.

### 구현에서 주의한 점

- 소유 앨범(`album`)과 공유받은 앨범(`album_share`)은 다른 테이블이라 `UNION ALL`로 합친다.
- 정렬 기준이 요청마다 달라져(createdAt/title, asc/desc) native query를 썼다.
  정렬 컬럼 문자열은 `AlbumSortField` enum에서만 나오므로 사용자 입력이 SQL에 들어가지 않는다.
- 제목 정렬은 기존 `String.CASE_INSENSITIVE_ORDER`와 맞추려고 `LOWER()`를 쓴다.
- **정렬 값이 같을 때를 대비해 `album_id`를 tie-breaker로 넣었다.**
  이게 없으면 같은 `createdAt`을 가진 앨범이 페이지 경계에서 중복되거나 누락된다.
  기존 메모리 정렬에는 이 보장이 없었다.

---

## 9. 남은 것

- **인덱스 정식 적용.** 마이그레이션 도구 도입 후.
- **고동시성 측정.** 이번 측정은 1 VU다. 최대 처리량은 측정하지 않았다.
- **S3 미연결 시 기동 실패.** 읽기 전용 API만 쓰는 경우에도 앱이 뜨지 않는다.
- **`getAlbums(userId, ownership, favoriteOnly)` 전체 목록 메서드가 남아 있다.**
  목록 화면은 더 이상 쓰지 않지만 다른 호출부가 있어 제거하지 않았다.
