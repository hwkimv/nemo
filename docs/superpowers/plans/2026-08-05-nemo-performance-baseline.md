# NEMO Performance Baseline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 로컬 PostgreSQL의 고정 데이터에서 NEMO 핵심 조회 API 3개의 Query 수, 평균 응답시간, p95, 오류율과 실행 계획을 재현 가능하게 측정한다.

**Architecture:** 기존 서비스 로직은 먼저 변경하지 않는다. `benchmark` Spring profile, 전용 PostgreSQL, 결정적 Seed, Hibernate Statistics, k6, PostgreSQL 실행 계획을 분리해 Before 근거를 만든다.

**Tech Stack:** Java 21, Spring Boot 3.5.3, Spring Data JPA, Hibernate Statistics, PostgreSQL 17, Docker Compose, JUnit 5, AssertJ, k6 0.54.0

## Global Constraints

- 운영 Supabase에는 Seed, 부하 요청, `EXPLAIN ANALYZE`를 실행하지 않는다.
- 기존 `dev`, `local`, `prod` profile과 기본 실행 동작을 바꾸지 않는다.
- 측정 전에는 N+1, Index, Pagination 개선 코드를 적용하지 않는다.
- 현재 사용자 미커밋 변경을 reset, restore, clean 또는 덮어쓰기 하지 않는다.
- 운영 DB 정보, JWT, AWS 비밀값을 출력하거나 Git에 기록하지 않는다.
- 측정 DB는 `nemo_benchmark`, 컨테이너는 `nemo-postgres-benchmark`, 포트는 `55432`로 고정한다.
- 데이터는 사용자 100명, 대상 사용자 소유 앨범 100개, 사진 1,000개, 공유 20개, 즐겨찾기 20개로 고정한다.
- 이미지 값은 `https://benchmark.invalid/...` URL을 사용하고 S3를 호출하지 않는다.
- 각 커밋은 지정한 파일만 경로를 명시해 stage한다.

---

## File Map

| File | Responsibility |
|---|---|
| `compose.yaml` | 전용 PostgreSQL 17 컨테이너와 볼륨 |
| `backend/src/main/resources/application-benchmark.yml` | 측정 profile과 Hibernate Statistics |
| `backend/src/test/java/com/nemo/backend/config/BenchmarkProfileContractTest.java` | profile 격리 계약 |
| `tools/performance/sql/seed.sql` | DB 이름 검증과 고정 데이터 재생성 |
| `tools/performance/sql/verify-seed.sql` | Seed 수량 검증 |
| `backend/src/test/java/com/nemo/backend/performance/PerformanceBaselineIntegrationTest.java` | 서비스 Query 수 측정 |
| `backend/build.gradle` | 전용 `performanceBaseline` task |
| `tools/performance/k6/baseline.js` | HTTP 응답시간 측정 |
| `tools/performance/sql/explain.sql` | PostgreSQL 실행 계획 |
| `docs/project/performance/2026-08-05-baseline.md` | 측정 결과 문서 |

---

### Task 1: 격리된 Benchmark Profile

**Files:**
- Create: `backend/src/test/java/com/nemo/backend/config/BenchmarkProfileContractTest.java`
- Create: `backend/src/main/resources/application-benchmark.yml`
- Modify: `compose.yaml`
- Modify: `backend/src/main/java/com/nemo/backend/domain/auth/controller/DevTokenController.java:21`

**Interfaces:**
- Consumes: PostgreSQL JDBC와 기존 개발 토큰 발급기
- Produces: `SPRING_PROFILES_ACTIVE=benchmark`, `localhost:55432/nemo_benchmark`, `/api/auth/dev/seed`

- [ ] **Step 1: 실패하는 계약 테스트 작성**

```java
@Test
void benchmarkUsesDedicatedPostgres() throws IOException {
    PropertySource<?> p = new YamlPropertySourceLoader()
        .load("benchmark", new ClassPathResource("application-benchmark.yml"))
        .getFirst();
    assertThat(p.getProperty("spring.datasource.url"))
        .isEqualTo("${BENCHMARK_DB_URL:jdbc:postgresql://localhost:55432/nemo_benchmark}");
    assertThat(p.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("update");
    assertThat(p.getProperty("spring.jpa.properties.hibernate.generate_statistics"))
        .isEqualTo(true);
    String compose = Files.readString(Path.of("..", "compose.yaml"));
    assertThat(compose).contains("container_name: nemo-postgres-benchmark")
        .contains("POSTGRES_DB: nemo_benchmark").contains("\"55432:5432\"");
}
```

- [ ] **Step 2: RED 확인**

Run: `cd backend && ./gradlew test --tests com.nemo.backend.config.BenchmarkProfileContractTest`

Expected: profile 파일과 Compose 서비스가 없어 FAIL.

- [ ] **Step 3: 최소 구현**

Compose에는 다음 서비스를 추가한다.

```yaml
  postgres-benchmark:
    image: postgres:17-alpine
    container_name: nemo-postgres-benchmark
    environment:
      POSTGRES_DB: nemo_benchmark
      POSTGRES_USER: nemo_benchmark
      POSTGRES_PASSWORD: nemo_benchmark_local_only
    ports: ["55432:5432"]
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U nemo_benchmark -d nemo_benchmark"]
      interval: 2s
      timeout: 3s
      retries: 20
    volumes: ["postgres_benchmark_data:/var/lib/postgresql/data"]
    profiles: ["benchmark"]
```

`application-benchmark.yml`에는 다음 값을 둔다.

```yaml
spring:
  datasource:
    driver-class-name: org.postgresql.Driver
    url: ${BENCHMARK_DB_URL:jdbc:postgresql://localhost:55432/nemo_benchmark}
    username: ${BENCHMARK_DB_USER:nemo_benchmark}
    password: ${BENCHMARK_DB_PASSWORD:nemo_benchmark_local_only}
  jpa:
    database-platform: org.hibernate.dialect.PostgreSQLDialect
    hibernate.ddl-auto: update
    show-sql: false
    properties:
      hibernate.format_sql: false
      hibernate.generate_statistics: true
app:
  public-base-url: http://localhost:8080
  jwt:
    secret: benchmark-local-secret-key-32bytes-minimum
    issuer: nemo-backend-benchmark
    access-ttl-ms: 3600000
  s3:
    bucket: nemo-benchmark-unused
    region: ap-northeast-2
    endpoint: http://localhost:4566
    accessKey: benchmark
    secretKey: benchmark
    pathStyle: true
    createBucketIfMissing: false
```

`DevTokenController`는 다음 profile에서만 활성화한다.

```java
@Profile({"local", "dev", "benchmark"})
```

- [ ] **Step 4: GREEN 확인**

Run: `cd backend && ./gradlew test --tests com.nemo.backend.config.BenchmarkProfileContractTest && cd .. && docker compose --profile benchmark config --quiet`.

Expected: 테스트 PASS, Compose exit code 0.

- [ ] **Step 5: 커밋**

```bash
git add compose.yaml backend/src/main/resources/application-benchmark.yml \
 backend/src/main/java/com/nemo/backend/domain/auth/controller/DevTokenController.java \
 backend/src/test/java/com/nemo/backend/config/BenchmarkProfileContractTest.java
git commit -m "test: add isolated PostgreSQL benchmark profile"
```

---

### Task 2: 안전하고 결정적인 Seed

**Files:**
- Create: `tools/performance/sql/seed.sql`
- Create: `tools/performance/sql/verify-seed.sql`

**Interfaces:**
- Consumes: Hibernate가 생성한 NEMO 테이블
- Produces: ID 1인 `benchmark-target@nemo.local`과 100/100/1,000/20/20 데이터셋

- [ ] **Step 1: 안전장치 RED 작성**

```sql
\set ON_ERROR_STOP on
DO $$
BEGIN
  IF current_database() <> 'nemo_benchmark' THEN
    RAISE EXCEPTION 'Refusing to seed database %', current_database();
  END IF;
END
$$;
```

Run: `docker compose exec -T postgres-benchmark psql -U nemo_benchmark -d postgres < tools/performance/sql/seed.sql`.

Expected: `Refusing to seed database postgres`로 FAIL하고 row 변경 없음.

- [ ] **Step 2: 고정 Seed 구현**

다음 transaction 시작과 초기화 뒤 PostgreSQL `generate_series`를 사용한다.

```sql
BEGIN;
TRUNCATE TABLE album_favorite,album_share,album_photos,album,
 refresh_tokens,friend,timeline,photos,users RESTART IDENTITY CASCADE;
```

```sql
INSERT INTO users (email,password,nickname,profile_image_url,provider,social_id,
 plan_type,max_photo_count,created_at,updated_at)
SELECT CASE WHEN n=1 THEN 'benchmark-target@nemo.local'
 ELSE format('benchmark-user-%s@nemo.local',n) END,
 '{noop}benchmark',format('benchmark-user-%s',n),'','local',NULL,'PLUS',5000,
 timestamp '2025-01-01'+n*interval '1 minute',
 timestamp '2025-01-01'+n*interval '1 minute'
FROM generate_series(1,100) n;

INSERT INTO photos (user_id,image_url,thumbnail_url,taken_at,location,brand,
 favorite,memo,created_at,deleted)
SELECT 1,format('https://benchmark.invalid/photos/%s.webp',n),
 format('https://benchmark.invalid/thumbs/%s.webp',n),
 timestamp '2025-01-01 12:00'+((n-1)%365)*interval '1 day',
 format('Seoul-%s',((n-1)%25)+1),
 CASE WHEN n%2=0 THEN '인생네컷' ELSE '포토이즘' END,
 n%10=0,format('benchmark memo %s',n),
 timestamp '2025-01-01 12:00'+n*interval '1 minute',false
FROM generate_series(1,1000) n;
```

나머지 관계 데이터는 다음 SQL로 넣고 하나의 transaction으로 commit한다.

```sql
INSERT INTO album (name,description,cover_photo_url,user_id,created_at,updated_at)
SELECT format('Benchmark Album %s',n),'benchmark owned album',NULL,1,
 timestamp '2025-01-01'+n*interval '1 hour',
 timestamp '2025-01-01'+n*interval '1 hour'
FROM generate_series(1,100) n;

INSERT INTO album_photos (album_id,photo_id)
SELECT ((n-1)/10)+1,n FROM generate_series(1,1000) n;

INSERT INTO album_share
 (album_id,user_id,role,status,active,created_at,updated_at)
SELECT n,n+1,'VIEWER','ACCEPTED',true,
 timestamp '2025-02-01',timestamp '2025-02-01'
FROM generate_series(1,20) n;

INSERT INTO album_favorite (album_id,user_id,created_at,updated_at)
SELECT n,1,timestamp '2025-02-01',timestamp '2025-02-01'
FROM generate_series(1,20) n;

COMMIT;
```

- [ ] **Step 3: 수량 검증**

`verify-seed.sql`은 다음 한 row를 반환한다.

```sql
SELECT
 (SELECT id FROM users WHERE email='benchmark-target@nemo.local') target_user_id,
 (SELECT count(*) FROM users) users,
 (SELECT count(*) FROM album WHERE user_id=1) owned_albums,
 (SELECT count(*) FROM photos WHERE user_id=1 AND deleted=false) photos,
 (SELECT count(*) FROM album_share WHERE status='ACCEPTED' AND active=true) shares,
 (SELECT count(*) FROM album_favorite WHERE user_id=1) favorites;
```

Expected: `1, 100, 100, 1000, 20, 20`.

Run: 전용 DB에 `seed.sql`과 `verify-seed.sql`을 차례로 `psql -U nemo_benchmark -d nemo_benchmark`로 실행.

- [ ] **Step 4: 커밋**

```bash
git add tools/performance/sql/seed.sql tools/performance/sql/verify-seed.sql
git commit -m "test: add deterministic benchmark dataset"
```

---

### Task 3: Hibernate Query 수 Baseline

**Files:**
- Modify: `backend/build.gradle`
- Create: `backend/src/test/java/com/nemo/backend/performance/PerformanceBaselineIntegrationTest.java`

**Interfaces:**
- Consumes: `AlbumService`, `PhotoService`, `TimelineService`, Hibernate `Statistics`
- Produces: `BASELINE api=<name> queries=<number> elapsed_ms=<number> rows=<number>`

- [ ] **Step 1: 전용 task 부재 RED 확인**

Run: `cd backend && ./gradlew performanceBaseline`

Expected: task를 찾지 못해 FAIL.

- [ ] **Step 2: Gradle task 구현**

일반 `test`는 `performance` tag를 제외하고, 새 task는 해당 tag만 실행한다.

```groovy
tasks.named('test') {
  useJUnitPlatform { excludeTags 'performance' }
}
tasks.register('performanceBaseline', Test) {
  group = 'verification'
  useJUnitPlatform { includeTags 'performance' }
  testLogging { events 'passed', 'failed'; showStandardStreams = true }
  shouldRunAfter tasks.named('test')
}
```

- [ ] **Step 3: 통합 테스트 구현**

`@Tag("performance")`, `@ActiveProfiles("benchmark")`, `@SpringBootTest`를 사용한다. 각 호출 직전에 `Statistics.clear()`하고 다음 결과를 검증한다.

```java
albumService.getAlbums(1L,"OWNED",false)                  // size 100
photoService.list(1L,PageRequest.of(0,20,
 Sort.by(Sort.Direction.DESC,"takenAt")),null,null,null) // total 1000
timelineService.getTimeline(1L,2025,1)                   // non-empty
```

공통 측정 함수는 다음 계약을 사용한다.

```java
private <T> Measurement<T> measure(String api, Supplier<T> action) {
  Statistics statistics = entityManagerFactory
      .unwrap(SessionFactory.class).getStatistics();
  statistics.clear();
  long started = System.nanoTime();
  T value = action.get();
  long elapsedMs = TimeUnit.NANOSECONDS
      .toMillis(System.nanoTime() - started);
  long queries = statistics.getPrepareStatementCount();
  assertThat(queries).isPositive();
  System.out.printf("BASELINE api=%s queries=%d elapsed_ms=%d%n",
      api,queries,elapsedMs);
  return new Measurement<>(value,queries,elapsedMs);
}
private record Measurement<T>(T value,long queries,long elapsedMs) {}
```

- [ ] **Step 4: GREEN과 회귀 확인**

Run: `./gradlew performanceBaseline --rerun-tasks`와 `./gradlew test --rerun-tasks`.

Expected: 성능 테스트 3개 PASS와 세 BASELINE 행, 일반 테스트 PASS.

- [ ] **Step 5: 커밋**

```bash
git add backend/build.gradle \
 backend/src/test/java/com/nemo/backend/performance/PerformanceBaselineIntegrationTest.java
git commit -m "test: measure service query baselines"
```

---

### Task 4: k6 HTTP Baseline과 실행 계획

**Files:**
- Create: `tools/performance/k6/baseline.js`
- Create: `tools/performance/sql/explain.sql`

**Interfaces:**
- Consumes: benchmark 서버, 개발 토큰, 세 조회 API, Seed DB
- Produces: k6 JSON 3개와 `explain-before.txt`

- [ ] **Step 1: k6 파일 부재 RED 확인**

Run: `docker run --rm -i grafana/k6:0.54.0 inspect - < tools/performance/k6/baseline.js`

Expected: 파일이 없어 FAIL.

- [ ] **Step 2: k6 스크립트 구현**

다음 구조로 구현한다. 세 scenario는 단일 VU 30회이며 시작 시간을 분리한다.

```javascript
import http from 'k6/http';
import { check } from 'k6';

const baseUrl = __ENV.BASE_URL || 'http://host.docker.internal:8080';
export const options = {
  scenarios: {
    albums: { executor:'shared-iterations', vus:1, iterations:30, exec:'albums' },
    photos: { executor:'shared-iterations', vus:1, iterations:30, exec:'photos', startTime:'35s' },
    timeline: { executor:'shared-iterations', vus:1, iterations:30, exec:'timeline', startTime:'70s' },
  },
  thresholds: { http_req_failed:['rate==0'], checks:['rate==1'] },
};
export function setup() {
  const r = http.post(`${baseUrl}/api/auth/dev/seed?email=benchmark-target@nemo.local`);
  check(r, { 'token issued': x => x.status === 200 });
  return { headers:{ Authorization:`Bearer ${r.json('accessToken')}` } };
}
function get(path,data,api) {
  const r = http.get(`${baseUrl}${path}`, { headers:data.headers, tags:{api} });
  check(r, { [`${api} 200`]: x => x.status === 200 });
}
export function albums(data) {
  get('/api/albums?ownership=OWNED&page=0&size=10',data,'albums');
}
export function photos(data) {
  get('/api/photos?page=0&size=20&sort=takenAt,desc',data,'photos');
}
export function timeline(data) {
  get('/api/timeline?year=2025&month=1',data,'timeline');
}
```

- [ ] **Step 3: 실행 계획 SQL 구현**

DB 이름 안전장치 다음에 다음 Query를 둔다.

```sql
EXPLAIN (ANALYZE,BUFFERS,FORMAT TEXT)
SELECT p.* FROM photos p WHERE p.user_id=1 AND p.deleted=false
ORDER BY p.taken_at DESC LIMIT 20 OFFSET 0;

EXPLAIN (ANALYZE,BUFFERS,FORMAT TEXT)
SELECT p.* FROM photos p WHERE p.user_id=1 AND p.deleted=false
ORDER BY p.taken_at DESC;
```

- [ ] **Step 4: GREEN과 Raw 결과 생성**

k6 `inspect`, 실행 중인 benchmark 서버를 대상으로 k6 3회, psql 실행 계획을 수행한다.

Expected: checks 100%, 오류율 0, `build/performance-baseline/k6-run-1.json`부터 `3.json`, `explain-before.txt` 생성.

- [ ] **Step 5: 스크립트만 커밋**

```bash
git add tools/performance/k6/baseline.js tools/performance/sql/explain.sql
git commit -m "test: add HTTP and query-plan baselines"
```

Raw 결과는 실행 환경 산출물이므로 커밋하지 않는다.

---

### Task 5: Before 결과 문서화

**Files:**
- Create: `docs/project/performance/2026-08-05-baseline.md`
- Modify: `docs/project/README.md`

**Interfaces:**
- Consumes: Query 측정 3회, k6 JSON 3개, 실행 계획, Seed 검증
- Produces: `Verified` 상태의 재현 가능한 Before 문서

- [ ] **Step 1: Query 측정 3회 저장**

`performanceBaseline --rerun-tasks`를 3회 실행해 `query-run-1.txt`, `query-run-2.txt`, `query-run-3.txt`로 저장한다.

Expected: 각 파일에 albums, photos, timeline BASELINE 행 존재.

- [ ] **Step 2: 증거 완전성 확인**

세 Query 로그, 세 k6 JSON, 실행 계획 파일이 모두 비어 있지 않은지 `test -s`와 `rg`로 검사한다.

- [ ] **Step 3: 실제 관찰값 문서화**

문서 섹션은 측정 목적, 아키텍처와 JPA 관계, 데이터 조건, API 흐름, Query 수 3회, k6 3회, 실행 계획, 병목 후보, 비범위, 재현 명령 순서로 고정한다. Raw 숫자를 그대로 옮기고 평균은 세 실행의 산술평균으로 계산하며 개선됐다는 표현은 쓰지 않는다.

- [ ] **Step 4: 인덱스 연결과 검사**

`docs/project/README.md`에 다음 행을 추가한다.

```markdown
| 2026-08-05 | Verified | [핵심 조회 API 성능 Baseline](performance/2026-08-05-baseline.md) | PostgreSQL Seed, Hibernate Query 수, k6 3회, 실행 계획 |
```

Run: 금지 표식 `T[B]D|T[O]DO|_{3}|개선 완료|향상` 검색과 `git diff --check`.

Expected: 금지 표식과 whitespace 오류 없음.

- [ ] **Step 5: 커밋**

```bash
git add docs/project/performance/2026-08-05-baseline.md docs/project/README.md
git commit -m "docs: record NEMO performance baseline"
```

---

### Task 6: 전체 검증과 안전 종료

**Files:**
- Verify: `backend/`, `compose.yaml`, `tools/performance/`, Baseline 문서

**Interfaces:**
- Consumes: Tasks 1-5 전체 결과
- Produces: 회귀, 재현성, 운영 DB 비접촉, 작업 트리 보존 증거

- [ ] **Step 1: 전체 Backend와 성능 테스트**

Run: `cd backend && ./gradlew clean test --no-daemon && ./gradlew performanceBaseline --rerun-tasks`.

Expected: 일반 테스트 전체 PASS, 성능 테스트 3개 PASS.

- [ ] **Step 2: Compose와 k6 구문 확인**

Run: `docker compose --profile benchmark config --quiet`와 k6 `inspect`.

Expected: 두 명령 exit code 0.

- [ ] **Step 3: 전용 컨테이너만 종료**

Run: `docker compose --profile benchmark stop postgres-benchmark`.

Expected: 전용 컨테이너가 실행 중이 아니며 볼륨은 유지됨.

- [ ] **Step 4: 범위와 비밀값 검사**

```bash
git diff --check
git status --short
rg -n "supabase\.co|DB_PASSWORD=|JWT_SECRET=|AWS_SECRET_ACCESS_KEY=" \
 compose.yaml backend/src/main/resources/application-benchmark.yml \
 tools/performance docs/project/performance
```

Expected: whitespace 오류 없음, 기존 변경 보존, 새 작업은 계획된 경로만 포함, 비밀값 패턴 없음.

- [ ] **Step 5: 문서와 Raw 결과 대조**

데이터 규모, 세 API Query 수, k6 결과, 실행 계획, 제한사항을 Raw 파일과 한 줄씩 대조한다. 불일치는 실제 값으로 고친 뒤 문서에 `git diff --check`를 다시 실행한다.

Expected: Raw 결과와 문서 값 일치.
