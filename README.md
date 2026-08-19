# 네컷모아 (NEMO)

셀프 포토부스 사진을 QR 또는 직접 업로드로 모아 클라우드에 보관하고, 날짜·브랜드·장소·친구 기준으로 정리해 캘린더 회고와 공유 앨범을 제공하는 앱입니다.

> ### 이 저장소에 대하여
>
> **원본**: [KDUcapstone/nemo-app](https://github.com/KDUcapstone/nemo-app) — KDU 캡스톤 3인 팀 졸업작품 (김한욱 · 문한일 · 임다빈)
>
> 이 저장소는 **김한욱의 개인 고도화 사본**입니다. 팀 작업 히스토리를 그대로 옮겼기 때문에 커밋 작성자는 원본과 동일하며, 팀원이 작성한 코드가 그대로 포함되어 있습니다. 아래 [내 담당 범위](#내-담당-범위)에 실제 기여 경계를 커밋 수와 함께 적었습니다.
>
> 팀 공동 작업은 원본 저장소에서 계속되고, 이 저장소에는 개인적으로 추가한 테스트·문서·배포 작업이 올라갑니다.

---

## 한눈에

이 저장소에서 **개인적으로** 한 작업과 그 결과입니다. 모든 숫자는 재현 명령과 함께 문서에 있습니다.

| 한 일 | 결과 | 근거 |
|---|---|---|
| 앨범 목록 N+1 제거 | DB 쿼리 **202 → 4**, 응답 **99ms → 11ms** | [CS 04](docs/case-studies/04-query-performance.md) |
| 타임라인 조회를 DB 기간 쿼리로 | 응답 **8.3ms → 4.4ms**, 읽는 행 1,000 → 93 | [CS 04](docs/case-studies/04-query-performance.md) |
| 앨범 목록 DB 페이지네이션 | 앨범 5,000개에서 **74ms → 8ms**, 앨범 수와 무관하게 고정 | [CS 04](docs/case-studies/04-query-performance.md) |
| 인증·권한 경계 결함 4건 수정 | 타인 사진 접근 차단, 토큰 로그 제거 | [CS 03](docs/case-studies/03-security-boundaries.md) |
| 지도 API 캐시 효과 측정 | 반복 조회 외부 호출 **820 → 0회**, **8.2초 → 8.3ms** | [CS 05](docs/case-studies/05-map-api-cache.md) |
| 지도 외부 호출 감축 (실제 API) | 뷰포트 1회 **25 → 10회**, 응답 **4,770 → 1,857ms** (결과 동일) | [CS 05](docs/case-studies/05-map-api-cache.md) |
| 지도 API HUB 이관 대응 | 지역 검색 401 → 200. 구 경로가 NCP 키를 못 받는 것을 실측으로 확인 | [CS 05](docs/case-studies/05-map-api-cache.md) |
| 캐시를 데이터 성격별로 분리 | Local Search 5분 / Reverse Geocoding 30분. 실제 API 적중률 **98.9% / 69.9%** | [CS 05](docs/case-studies/05-map-api-cache.md) |
| 모니터링 구축 (Prometheus/Grafana) | 지표로 **레이트 리미터가 동시성에 무력**한 것 발견 | [CS 06](docs/case-studies/06-monitoring.md) |
| CI 파이프라인 구축 | 테스트 실패 시 빌드·이미지 차단. **결함 5건 발견·수정** | [CS 07](docs/case-studies/07-ci-cd.md) |
| Sentry 오류 추적 | 중복 친구 요청 **500 → 409**. 토큰·breadcrumb 스크러빙 검증 | [CS 08](docs/case-studies/08-sentry.md) |
| 동시성 검증 | 동시 업로드가 저장 한도를 넘던 문제 (**26장 → 20장**) | [CS 09](docs/case-studies/09-concurrency.md) |
| 인증 경로 테스트 | 전체 **110개** (인증 37 + 보안 17 + 조회 20 + 캐시 6 + 스크러빙 13 + 동시성 2 + 기타 15) | [CS 01](docs/case-studies/01-jwt-authentication.md) |
| PostgreSQL 전환 후 런타임 하드닝 | 프로필 분리, 운영 공개 표면 차단 | [CS 02](docs/case-studies/02-postgres-runtime-hardening.md) |

**측정하지 않은 것은 개선했다고 쓰지 않았습니다.** 확인되지 않은 항목은 [알려진 한계](#알려진-한계)에 그대로 적어 두었습니다.

## WHY

포토부스는 브랜드마다 사진 다운로드 방식과 보관 위치가 제각각입니다. 카카오톡으로 받은 것, 브랜드 앱에 남은 것, 갤러리에 저장한 것이 흩어지고, 시간이 지나면 "언제 누구와 어디서 찍었는지"라는 맥락이 먼저 사라집니다.

네컷모아는 **사진 파일과 촬영 맥락을 함께 저장**해서, 나중에 다시 찾고 회고할 수 있게 만듭니다.

## WHAT

| 기능 | 설명 |
|---|---|
| QR 가져오기 | 포토부스 QR을 스캔해 원본을 내려받고 중복을 걸러 저장 |
| 직접 업로드 | 갤러리 파일 업로드. EXIF에서 촬영일 추출 |
| 자동·수동 분류 | 날짜 · 브랜드 · 장소 · 함께 찍은 친구 · 해시태그 |
| 캘린더 회고 | 월별 캘린더와 타임라인에서 과거 사진 탐색 |
| 공유 앨범 | 공유 링크 또는 친구 초대로 공동 앨범 구성 |
| 지도 | 사진을 찍은 포토부스 위치 확인 |

## Architecture

```text
Flutter 앱
  │  http + JWT
  ▼ HTTPS
Spring Boot API
  ├─ auth     인증·토큰 발급/검증, 이메일 인증, 비밀번호 재설정
  ├─ user     프로필, 계정 상태
  ├─ photo    업로드, QR 가져오기, 태그, 즐겨찾기
  ├─ album    앨범, 공유 링크, 참여자 권한
  ├─ friend   친구 관계
  ├─ timeline 캘린더·타임라인 조회 모델
  ├─ map      포토부스 위치 검색
  └─ storage  파일 저장 추상화
        │
        ├─ PostgreSQL (Supabase) — 사용자·사진 메타데이터·관계
        └─ S3 / LocalStack       — 원본·압축본·썸네일

관측
  RequestIdFilter ──> 모든 로그에 같은 requestId (응답 헤더 X-Request-Id)
  Actuator/Micrometer ──> Prometheus ──> Grafana   (운영은 별도 관리 포트)
  GlobalExceptionHandler ──> Sentry                (전송 전 토큰·PII 제거)
```

### 왜 이 구조인가

**파일과 메타데이터를 분리했습니다.** 큰 바이너리는 오브젝트 스토리지에, 검색·정렬에 필요한 메타데이터는 RDB에 두고 DB에는 객체 키만 저장합니다. 사진 목록 조회가 파일 크기와 무관해집니다.

**마이크로서비스로 나누지 않았습니다.** QR·이미지 처리를 별도 Node.js 서비스로 분리하는 안이 논의됐지만, 3인 팀이 2개월 안에 만드는 규모에서는 배포 대상이 늘어난 만큼 운영과 설명이 어려워집니다. 단일 Spring Boot 애플리케이션으로 시작하고, Spring이 감당하지 못하는 라이브러리 제약이 실제로 생기면 그때 분리하는 것이 맞다고 판단했습니다.

**이미지 업로드는 앱 → Spring multipart 방식입니다.** presigned URL이 서버 부하 면에서 낫지만 업로드 완료 확인 API가 추가로 필요합니다. 현재 트래픽에서는 구현이 단순한 쪽을 택했고, 파일 크기나 동시 업로드가 문제가 되면 전환합니다.

## 내 담당 범위

3인 팀 / 팀장 · 풀스택. 팀 작업 히스토리 482커밋 중 120커밋 (`git shortlog -sne --all`, 개인 고도화 커밋 제외).

역할 주장을 검증할 수 있도록, 아래 숫자는 `dev` 브랜치에서 디렉터리별로 센 **커밋 수(내 커밋 / 전체)** 입니다.

```bash
git log dev --author=hwkimv --oneline -- <경로> | wc -l
```

### 직접 설계·구현

| 영역 | 커밋 | 내용 |
|---|---|---|
| `map` | **6 / 6** | 네이버 지역·지도 API 연동, 검색 결과 가공, 주변 포토부스 조회 |
| `friend` | **6 / 10** | 친구 관계 도메인과 상태 모델링 |
| `auth` | **13 / 33** | JWT 발급·검증 구조 통합, 리프레시 토큰 회전 정책, 이메일 인증·비밀번호 재설정 |
| `timeline` | 2 / 4 | 캘린더·타임라인 조회 모델 |
| `storage` | 1 / 1 | 파일 저장 추상화 |
| 인프라·문서 | 3 / 4 | LocalStack·Nginx 설정, 문서 허브 |

### 개인 고도화 (이 저장소)

팀 작업 이후 혼자 진행한 작업입니다. 전부 [Case Study](#case-studies)로 근거를 남겼습니다.

**보안·인증**
- JWT 인증 테스트 37개 — 클럭 스큐 3분과 `isExpired()`의 실제 동작을 규명
- 인증·권한 경계 결함 4건 수정 — 타인 사진 접근 차단, 토큰 로그 제거
- Supabase PostgreSQL 전환 및 런타임 하드닝 — 프로필 분리, 운영 공개 표면 차단

**성능**
- 앨범 목록 N+1 제거 — DB 쿼리 202 → 4, 응답 99ms → 11ms (**같은 환경 Before/After**)
- 타임라인·페이지네이션을 DB로 이동, 인덱스 효과를 20만 행에서 측정
- 지도 API 캐시 효과 측정 — 외부 호출 820 → 0회

**관측·운영**
- Actuator → Prometheus → Grafana. 지표로 **동시성 결함**을 찾아냄
- Sentry — 실제 예외를 수집해 원인까지 수정. 토큰·breadcrumb 스크러빙
- GitHub Actions — 테스트 실패 시 이미지 빌드 차단

**정합성**
- 동시 업로드가 저장 한도를 넘던 문제 — 깨지는 것을 먼저 증명하고 수정

> 이 과정에서 **계획에 없던 결함 13건**을 추가로 발견해 고쳤습니다.
> 대부분은 "만들어서"가 아니라 **"확인해봐서"** 나왔습니다.

### 팀원 주도

- `photo` (7 / 55), `album` (6 / 40) — 사진 업로드·앨범 도메인의 본체는 팀원이 구현했습니다. 저는 API Contract 조율과 통합 시나리오 검증에 참여했습니다.
- Flutter 클라이언트 (12 / 165) — 화면 구현은 팀원이 담당했습니다.

> 커밋 수는 기여의 **경계**를 보여주기 위한 것이지 크기를 재는 지표가 아닙니다. 한 줄짜리 커밋과 300줄짜리 커밋이 같은 1로 세어집니다.

## Case Studies

각 문서는 `문제 → 분석 → 선택지 → 실행 → 결과 → 한계` 순서입니다.
**무엇을 만들었는지보다, 무엇을 비교하고 왜 그것을 골랐는지**를 적었습니다.

| # | 주제 | 핵심 결과 |
|---|---|---|
| 01 | [JWT 인증 경로에 검증 가능한 경계 세우기](docs/case-studies/01-jwt-authentication.md) | 테스트 37개. 클럭 스큐 3분과 `isExpired()`의 실제 동작 규명 |
| 02 | [Supabase PostgreSQL 전환 후 런타임 하드닝](docs/case-studies/02-postgres-runtime-hardening.md) | 프로필 분리, 운영 smoke test, 개발용 표면 차단 |
| 03 | [인증·권한 경계의 구멍 4개 막기](docs/case-studies/03-security-boundaries.md) | 타인 사진 접근 차단, 토큰 로그 제거. 회귀 테스트 17개 |
| 04 | [앨범 목록 N+1 제거와 측정](docs/case-studies/04-query-performance.md) | **DB 쿼리 202 → 4, 응답 99ms → 11ms** |
| 05 | [지도 API 캐시가 가리고 있던 것](docs/case-studies/05-map-api-cache.md) | 외부 호출 820 → 0회. 실제 API로 재측정해 뷰포트 25 → 10회 |
| 06 | [지표를 붙이고 나서 알게 된 것](docs/case-studies/06-monitoring.md) | Actuator→Prometheus→Grafana. 지표가 찾아준 동시성 결함 |
| 07 | [테스트를 통과하지 않은 코드가 못 지나가게 막기](docs/case-studies/07-ci-cd.md) | GitHub Actions 관문. 돌려보며 드러난 결함 5건 |
| 08 | [Sentry를 붙였는데 이벤트가 0건이었다](docs/case-studies/08-sentry.md) | 전역 핸들러가 삼키던 예외. 정상 상황이 500이던 문제 |
| 09 | [unique 제약이 지켜주지 않는 조건 하나](docs/case-studies/09-concurrency.md) | 깨지는 것을 먼저 증명하고 고친 동시성 결함 |

전체 문서 지도와 측정 원자료는 [문서 허브](docs/README.md)에 있습니다.

### 04번을 먼저 보시길 권합니다

성능 개선을 측정하다 **코드를 한 줄도 바꾸지 않은 API가 3.6배 빨라져 있는 것**을 발견했습니다.
9일 전 기준선과 비교하면 환경 덕분에 빨라진 몫까지 성과로 계산됩니다.

그래서 같은 시각·같은 DB에서 이전 코드를 다시 측정하고, 바꾸지 않은 API를 대조군으로 삼아
개선분을 분리했습니다. 이 저장소에서 성능 수치를 다루는 방식이 그 문서에 다 들어 있습니다.

## 기술 스택

**Backend** — Java 21 · Spring Boot 3.5.3 · Spring Security + JWT(jjwt) · Spring Data JPA · springdoc-openapi · AWS SDK v2 (S3) · ZXing(QR) · Jsoup

**관측·검증** — Actuator + Micrometer(Prometheus, Caffeine 캐시 지표 포함) · Grafana · Sentry · JUnit5 + AssertJ · k6 · GitHub Actions

**Frontend** — Flutter (Dart SDK 3.8+) · provider · http · flutter_naver_map · mobile_scanner(QR) · image_picker · geolocator

**Data / Infra** — PostgreSQL(Supabase, 운영) · H2(개발) · MariaDB(레거시) · LocalStack · Docker · Nginx

> 지도 캐시는 **Caffeine 로컬 캐시 2개**입니다. 데이터 성격이 달라 나눴습니다 —
> Local Search는 `expireAfterWrite` 5분, Reverse Geocoding은 30분.
> Redis가 아니라 **프로세스 안의 메모리**입니다. Redis는 다중 인스턴스 요구가
> 실제로 생기면 검토합니다 — 지금은 인스턴스가 1개라 근거가 없습니다.

## 실행

### Backend

```bash
cd backend && ./gradlew bootRun
```

기본 프로필은 `dev`입니다. 환경변수 없이 H2 인메모리로 뜹니다. Swagger는 `http://localhost:8080/swagger-ui/index.html`.

운영 프로필은 환경변수를 요구합니다 — `SPRING_PROFILES_ACTIVE=prod`, `DB_URL` / `DB_USER` / `DB_PASSWORD`, `JWT_SECRET`, `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY`. 저장소에는 비밀값을 커밋하지 않습니다.

### Frontend

```bash
cd frontend && flutter run --dart-define=API_BASE_URL=http://10.0.2.2:8080
```

Mock API는 `--dart-define=USE_MOCK_API=true`일 때만 켜집니다.

### CI

`main`·`dev` push와 모든 PR에서 자동 실행됩니다.

```
backend-test ──> backend-build ──> docker-image
```

테스트가 실패하면 빌드도 이미지도 만들지 않습니다. `.github/workflows/ci.yml`

> 관문이 실제로 강제되려면 저장소 설정에서 브랜치 보호 규칙에 `backend-test`를 필수로 지정해야 합니다.

### 모니터링

```bash
docker compose --profile monitoring up -d
```

Grafana `http://localhost:3000` (admin / admin) → NEMO 폴더. 앱은 호스트에서 띄운 상태여야 합니다.

### Test

```bash
cd backend && ./gradlew test
```

**110 tests.** Gradle toolchain이 **Java 21**을 요구합니다 — JDK 23에서는 빌드가 깨집니다.

성능 측정을 재현하려면 PostgreSQL과 k6가 필요합니다.

```bash
docker compose --profile benchmark up -d postgres-benchmark
docker compose exec -T postgres-benchmark psql -U nemo_benchmark -d nemo_benchmark < tools/performance/sql/seed.sql
cd backend && SPRING_PROFILES_ACTIVE=benchmark ./gradlew performanceBaseline --rerun-tasks --no-daemon
k6 run -e BASE_URL=http://localhost:8080 tools/performance/k6/baseline.js
```

## 알려진 한계

포트폴리오 문서가 실제보다 앞서 나가지 않도록, 현재 확인되지 않은 것을 적어 둡니다.

- **조회 성능만 측정했습니다.** 앨범·타임라인·사진 조회는 Before/After가 있지만, 업로드·QR 경로는 측정하지 않았습니다.
- **낮은 동시성 로컬 측정입니다.** 1 VU 기준이라 최대 처리량이나 운영 지연시간을 뜻하지 않습니다.
- **인덱스는 근거만 확보하고 적용하지 않았습니다.** 부분·표현식 인덱스는 JPA로 표현할 수 없고 마이그레이션 도구가 아직 없습니다. SQL은 `tools/performance/sql/indexes.sql`에 있습니다.
- **지도 뷰포트 1회 요청이 외부 API를 10번 부릅니다.** (실측 25 → 10) 캐시가 반복은 막아주지만 첫 요청은 여전히 1.9초입니다. 남은 9회가 키워드 검색이라, 키워드 9개가 다 필요한지를 여러 지역에서 반복 측정한 뒤 줄일 계획입니다. ([CS 05](docs/case-studies/05-map-api-cache.md))
- **지도 캐시는 여전히 프로세스별 로컬 캐시입니다.** 크기 상한(1000 entry)과 통계는 있지만, 인스턴스가 여러 개면 캐시가 공유되지 않고 재시작하면 사라집니다. Redis는 인스턴스가 1개인 지금 도입할 근거가 없어 두었습니다.
- **TTL 5분/30분은 최적값이 아닙니다.** 데이터 변경 특성으로 정한 초기값이고, TTL별 성능 비교는 하지 않았습니다. Grafana의 적중률·축출을 보고 조정할 값입니다. ([근거](docs/evidence/2026-08-20-map-cache-split.md))
- **사진 업로드·QR·친구 경로에는 아직 테스트가 없습니다.** 인증·앨범·타임라인 경로만 덮여 있습니다.
- LocalStack과 실제 S3의 동작 차이(Content-Type, presigned URL 세부)는 실제 AWS에서 재검증이 필요합니다.
- 배포는 Railway + Supabase 방향으로 설계했으나 상시 공개 인스턴스는 아직 없습니다.
- **지도 API의 레이트 리미터가 동시 요청에 동작하지 않습니다.** 의도는 초당 5회인데 동시 8건이면 초당 40회가 나갑니다. 모니터링을 붙이며 발견했고 아직 고치지 않았습니다. ([CS 06](docs/case-studies/06-monitoring.md))
- **Flutter 정적 분석은 error만 파이프라인을 막습니다.** `info` 지적(`avoid_print` 등)이 많아 우선 error만 막고 점진적으로 줄입니다.
- **Sentry에 접속 IP 기반 위치가 저장됩니다.** SDK에서 IP를 지워도 Sentry가 수집 시점의 접속 IP로 지역을 역산합니다. 막으려면 프로젝트 설정에서 `Prevent Storing of IP Addresses`를 켜야 합니다.
- **Sentry 알림 규칙이 없습니다.** 어떤 이벤트에 누구에게 알릴지는 정하지 않았습니다.
- **동시성은 사진 저장 한도만 확인했습니다.** "행 개수"나 "합계"에 대한 조건은 전부 같은 위험을 갖습니다. 전수 점검은 하지 않았습니다.
- **동시성 검증이 H2 기준입니다.** `SELECT ... FOR UPDATE`는 PostgreSQL에서도 같은 의미지만 잠금 대기·데드락 동작은 다릅니다.
- **다른 도메인에도 `IllegalStateException`이 남아 있을 수 있습니다.** 친구 도메인만 도메인 오류로 정리했습니다.
- **배포 스텝이 없습니다.** `deploy.yml`은 배포 전 관문(테스트·secret 확인·이미지 push)까지만 있고, 실제 배포는 대상 플랫폼이 정해지면 붙입니다.
