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

이 저장소에서 **개인적으로** 한 작업입니다. 네 가지가 핵심이고, 나머지는 아래
[Case Studies](#case-studies)에 있습니다. 모든 숫자는 재현 명령과 함께 문서에 있습니다.

### 1. 성능 — 측정해서 고치고, 다시 측정했습니다

| | Before | After |
|---|---:|---:|
| 앨범 목록 DB 쿼리 | 202개 | **4개** |
| 앨범 목록 응답 | 99ms | **11ms** |
| 앨범 5,000개일 때 (페이지네이션) | 74ms | **8ms** — 앨범 수와 무관하게 고정 |
| 타임라인 조회 (읽는 행 1,000 → 93) | 8.3ms | **4.4ms** |

측정하다 **코드를 한 줄도 안 바꾼 API가 3.6배 빨라져 있는 것**을 발견해서,
같은 시각·같은 DB에서 이전 코드를 다시 재고 바꾸지 않은 API를 대조군으로 삼아
환경 덕분에 빨라진 몫을 분리했습니다. → [CS 04](docs/case-studies/04-query-performance.md)

### 2. 데이터 정합성 — 트랜잭션이 지켜주지 못하는 경계

`@Transactional`이 붙어 있어 안전해 보이던 사진 업로드·삭제에서,
**롤백해도 되돌아가지 않는 상태 3가지**를 테스트로 먼저 재현했습니다.

| 실패 경로 | 재현된 결과 |
|---|---|
| S3 성공 → DB 실패 | DB 0건인데 S3에 고아 객체 1개. 치우는 코드 없음 |
| S3 삭제 성공 → DB 실패 | **파일은 사라졌는데 DB는 살아 있다고 적혀 있음** (되살릴 수 없음) |
| S3 삭제 실패 | DB는 삭제 처리. **지울 키를 아는 코드가 사라짐** |

해결은 **즉시 보상 삭제 + PostgreSQL에 적어 두는 정리 작업 + 지수 백오프 재시도**입니다.
메시지 큐를 쓰지 않은 이유는 하나입니다 — **지울 키를 잃지 않는 것이 목적**인데
그건 이미 있는 DB가 트랜잭션과 함께 해 줍니다. RabbitMQ를 넣으면 운영할 브로커가
하나 늘고, 브로커가 죽으면 같은 문제가 다시 생깁니다.
→ [CS 10](docs/case-studies/10-storage-consistency.md)

### 3. 동시성 / 외부 API — `AtomicLong`을 썼는데 안 막히던 리미터

`get()`과 `set()`은 각각 원자적이지만 **`읽기 → 계산 → sleep → 쓰기` 전체는 아니었습니다.**
스레드들이 같은 값을 읽고, 같이 자고, 같이 깨어 함께 나갔습니다.

| 동시성 | Before | After | 목표 |
|---:|---:|---:|---:|
| 4 | 18.6 req/s | **5.0** | 5.0 |
| 8 | 37.2 req/s | **5.0** | 5.0 |
| 16 | **74.0 req/s** | **5.0** | 5.0 |

**CAS 슬롯 예약**으로 고쳤습니다(의존성 추가 0). 대기 상한을 넘긴 요청은
**슬롯을 소비하지 않고** 거절합니다 — 처음 구현은 거절할 요청도 슬롯을 예약해
아무도 외부 API를 부르지 않는데 사용자만 429를 받는 상태가 됐고, PR 리뷰에서 잡혔습니다.

보장하는 것은 **장기 평균 호출률**이지 "어떤 1초 구간에서도 ≤ 5"인
strict sliding window가 아닙니다. 지연이 끼면 1초 창에 8~9회가 들어가는 것을
테스트로 기록해 두었습니다. **JVM 1개 안에서만 유효**하다는 것도
인스턴스 2개를 띄워 실측했습니다(각 5.0인데 합산 10.0).
→ [CS 11](docs/case-studies/11-rate-limiter-concurrency.md)

### 4. 운영 / AWS — 배포하고, 일부러 망가뜨렸습니다

CI가 이미지까지만 만들고 **배포할 곳이 없던** 서비스를 EC2 1대에 올렸습니다.

| 한 일 | 결과 |
|---|---|
| 정적 AWS 키 → IAM Role | 컨테이너 안 `AWS_ACCESS_KEY` **0개**. `ListAllMyBuckets`는 `AccessDenied`로 최소 권한 실증 |
| `anon`/`authenticated` 테이블 권한 회수 | 11개 테이블 전부 → **0건**. `ALTER DEFAULT PRIVILEGES`로 앞으로 만들 테이블도 닫힘 |
| 장애 3종 주입·실측 | 앱 크래시 **24.7초**(85%가 JVM 기동) / DB 단절 시 앱은 살아 있고 DB 쓰는 요청만 실패 / S3 불가 시 업로드만 502 |
| liveness / readiness 분리 | DB 차단 시 `/livez` **UP 6.7ms**, `/readyz` **DOWN**. 기동 중인 앱을 죽이지 않습니다 |
| readiness 응답 시간 | **30.1초 → 5초** (Hikari `connection-timeout`) |
| 배포 자동화 + rollback | 실패한 배포를 감지해 **26초 만에 이전 이미지로 자동 복구**. 실패 이미지는 last-good을 오염시키지 않습니다 |

**단일 인스턴스입니다. 고가용성이 아닙니다.** 그 밖에 하지 않은 것은
[알려진 한계](#알려진-한계)에 그대로 적어 두었습니다.
→ [CS 12](docs/case-studies/12-cloud-operation.md) · [배포 구조](infra/deploy/README.md)

### AI 보조 개발 워크플로우

도구를 썼다는 이야기가 아니라 **검증 절차**가 핵심입니다.

```
Issue → 코드 분석(파일:라인) → 실패 재현 → 대안 비교
      → ★ 사람의 설계 결정 → 구현 → 회귀 테스트
      → 독립 리뷰(다른 세션) → 최종 검증
```

독립 리뷰가 실제로 잡아낸 것들입니다 — 리미터에서 **유령 슬롯**(거절된 요청이
미래 슬롯을 소비), 배포 작업에서 **"고쳤다"고 문서에만 적고 파일은 안 고친 스키마 충돌**,
비용 비교에서 **빠뜨린 Public IPv4**, 그리고 **실측 범위를 넘은 보안 문구**.
자기가 고쳤다고 믿는 것은 자기가 검증해도 나오지 않습니다.
→ [워크플로우 문서](docs/ai-development-workflow.md)

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
- `AlbumPhoto` 관계 엔티티로 요청 순서를 저장하고 삭제 뒤 sequence를 재정렬
- `PhotoTag` 생성·조회·삭제 API와 소유권·친구·공유 앨범 권한 검증

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
| 10 | [DB 트랜잭션이 지켜주지 못하는 경계](docs/case-studies/10-storage-consistency.md) | S3↔DB 불일치 3가지를 테스트로 재현. 보상 처리 + DB 기반 재시도로 복구 |
| 11 | [AtomicLong을 썼는데 동시 요청에서 막지 못한 리미터](docs/case-studies/11-rate-limiter-concurrency.md) | 외부 API 호출률 동시 16건 **74.0 → 5.0 req/s**. CAS 슬롯 예약, 의존성 추가 0 |
| 12 | [배포할 곳이 없던 서비스를 AWS에 올리고 일부러 망가뜨려 보기](docs/case-studies/12-cloud-operation.md) | EC2 1대 배포. 정적 키 → IAM Role, anon 접근 권한 차단, 장애 3종 실측 |

전체 문서 지도와 측정 원자료는 [문서 허브](docs/README.md)에 있습니다.

### 04번을 먼저 보시길 권합니다

성능 개선을 측정하다 **코드를 한 줄도 바꾸지 않은 API가 3.6배 빨라져 있는 것**을 발견했습니다.
9일 전 기준선과 비교하면 환경 덕분에 빨라진 몫까지 성과로 계산됩니다.

그래서 같은 시각·같은 DB에서 이전 코드를 다시 측정하고, 바꾸지 않은 API를 대조군으로 삼아
개선분을 분리했습니다. 이 저장소에서 성능 수치를 다루는 방식이 그 문서에 다 들어 있습니다.

## 기술 스택

**Backend** — Java 21 · Spring Boot 3.5.3 · Spring Security + JWT(jjwt) · Spring Data JPA · Flyway · springdoc-openapi · AWS SDK v2 (S3) · ZXing(QR) · Jsoup

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

운영 프로필은 환경변수를 요구합니다 — `SPRING_PROFILES_ACTIVE=prod`, `DB_URL` / `DB_USER` / `DB_PASSWORD`, `JWT_SECRET`, `PUBLIC_BASE_URL`. 저장소에는 비밀값을 커밋하지 않습니다.

> **AWS 키는 넣지 마십시오.** `app.s3.accessKey` / `app.s3.secretKey`(환경변수 `AWS_ACCESS_KEY` / `AWS_SECRET_KEY`)를
> 비워 두면 EC2 인스턴스 프로파일에서 임시 자격증명을 받습니다. 값을 주면 `S3Config`가
> 정적 키 분기를 타서 **IAM Role을 쓰지 않게 됩니다.** 한쪽만 주면 기동 시점에 실패합니다 —
> 조용히 개발자 PC의 `~/.aws`로 넘어가 엉뚱한 계정·버킷에 붙는 것을 막기 위해서입니다.

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

### 배포

```
GitHub Actions (수동 실행)          EC2 호스트
  test → 이미지 push → SHA 출력  →  ~/nemo-deploy.sh <SHA>
                                      pull → 교체 → /readyz 폴링
                                      실패하면 이전 이미지로 자동 rollback
```

`main` push마다 자동으로 나가지 않습니다. **deployment automation이지
continuous deployment가 아닙니다.** 왜 GitHub Actions가 EC2에 직접 접속하지 않는지는
[배포 문서](infra/deploy/README.md)에 실측 근거와 함께 적었습니다.

### 모니터링

```bash
docker compose --profile monitoring up -d
```

Grafana `http://localhost:3000` (admin / admin) → NEMO 폴더. 앱은 호스트에서 띄운 상태여야 합니다.

### Test

```bash
cd backend && ./gradlew test
```

**192 tests / 실패·오류·skip 0.** Gradle toolchain이 **Java 21**을 요구합니다 — JDK 23에서는 빌드가 깨집니다.

성능 측정을 재현하려면 PostgreSQL과 k6가 필요합니다.

```bash
docker compose --profile benchmark up -d postgres-benchmark
docker compose exec -T postgres-benchmark psql -U nemo_benchmark -d nemo_benchmark < tools/performance/sql/seed.sql
cd backend && SPRING_PROFILES_ACTIVE=benchmark ./gradlew performanceBaseline --rerun-tasks --no-daemon
k6 run -e BASE_URL=http://localhost:8080 tools/performance/k6/baseline.js
```

## 알려진 한계

문서가 실제 코드보다 앞서 나가지 않도록, 현재 확인되지 않은 것을 적어 둡니다.

- **단일 인스턴스 배포입니다. 고가용성이 아닙니다.** EC2 1대이고 인스턴스가 죽으면 24.7초 멈춥니다(Docker 재시작). HTTPS·도메인·로드밸런서·CloudWatch 로그 수집이 없습니다. ([CS 12](docs/case-studies/12-cloud-operation.md))
- **알림이 없습니다.** liveness/readiness와 배포 실패는 종료 코드·프로브로 드러나지만, 사람에게 알리는 경로가 없습니다. CloudWatch 알람이 다음 단계입니다.
- **배포가 continuous deployment는 아닙니다.** GitHub Actions가 테스트·이미지 push까지 하고, 배포 실행은 호스트의 `nemo-deploy.sh`(readiness 확인 + 자동 rollback)가 합니다. `main` push마다 자동으로 나가지 않습니다 — 인스턴스가 평소 정지돼 있고, OIDC→SSM은 현재 IAM 권한으로 구축할 수 없습니다. ([근거](infra/deploy/README.md))
- **zero-downtime이 아닙니다.** 포트 8080을 직접 쓰는 단일 인스턴스라 컨테이너를 교체하는 사이에 중단이 있습니다. 앞단에 Nginx/ALB를 두면 없앨 수 있지만 지금 필요하지 않은 인프라라 넣지 않았습니다.
- **DB 마이그레이션은 rollback되지 않습니다.** Flyway가 적용한 스키마 변경은 이전 이미지로 되돌려도 그대로 남습니다. 하위 호환되지 않는 마이그레이션을 포함한 배포는 이 스크립트로 안전하게 되돌릴 수 없습니다.
- **외부 API 레이트 리미터는 JVM 안에서만 유효합니다.** 인스턴스 2개를 띄워 실측했습니다 — 각각은 5.0 req/s를 정확히 지키는데 **네이버가 받는 합산은 10.0 req/s**입니다. 다만 다중 인스턴스로 갈 때 더 급한 건 리미터가 아니라 **메모리에 있는 비밀번호 재설정 토큰·이메일 인증코드**입니다. ([CS 11](docs/case-studies/11-rate-limiter-concurrency.md))
- **`min-interval-ms=200`의 근거가 약합니다.** 네이버가 공표한 쿼터가 아니라 기존 코드에 있던 값을 설정으로 옮긴 것입니다. 실제 허용치 확인이 필요합니다.
- **리미터가 보장하는 것은 장기 평균 호출률입니다.** 어떤 1초 구간에서도 5회 이하라는 strict sliding window는 보장하지 않습니다. GC·스케줄링 지연으로 호출이 뭉칠 수 있습니다. ([CS 11](docs/case-studies/11-rate-limiter-concurrency.md))
- **기존에 쌓인 S3 고아 객체는 그대로입니다.** 이번 정합성 작업은 앞으로 생기는 것을 막을 뿐입니다. 과거 것을 찾으려면 S3 객체 목록과 DB를 대조하는 별도 작업이 필요합니다. ([CS 10](docs/case-studies/10-storage-consistency.md))
- **`storage_cleanup_task` 테이블에 보관 정책이 없습니다.** `COMPLETED` 행이 계속 쌓입니다. 삭제 쿼리는 SQL 파일에 주석으로만 있고 자동화하지 않았습니다.
- **스키마 부트스트랩이 두 갈래입니다.** Flyway가 증분 마이그레이션을 맡지만 base 스키마를 만드는 마이그레이션이 없습니다. 새 DB는 base를 수동 적용한 뒤 Flyway V1이 얹힙니다.
- **조회 성능만 측정했습니다.** 앨범·타임라인·사진 조회는 Before/After가 있지만, 업로드·QR 경로는 측정하지 않았습니다.
- **낮은 동시성 로컬 측정입니다.** 1 VU 기준이라 최대 처리량이나 운영 지연시간을 뜻하지 않습니다.
- **인덱스는 근거만 확보하고 적용하지 않았습니다.** 부분·표현식 인덱스는 JPA로 표현할 수 없습니다. Flyway가 있으므로 마이그레이션으로 넣을 수는 있지만, 20만 행 측정에서 얻은 이득이 현재 데이터 규모에서 유의미한지 확인하지 않아 적용하지 않았습니다. SQL은 `tools/performance/sql/indexes.sql`에 있습니다.
- **지도 뷰포트 1회 요청이 외부 API를 10번 부릅니다.** (실측 25 → 10) 캐시가 반복은 막아주지만 첫 요청은 여전히 1.9초입니다. 남은 9회가 키워드 검색이라, 키워드 9개가 다 필요한지를 여러 지역에서 반복 측정한 뒤 줄일 계획입니다. ([CS 05](docs/case-studies/05-map-api-cache.md))
- **지도 캐시는 여전히 프로세스별 로컬 캐시입니다.** 크기 상한(1000 entry)과 통계는 있지만, 인스턴스가 여러 개면 캐시가 공유되지 않고 재시작하면 사라집니다. Redis는 인스턴스가 1개인 지금 도입할 근거가 없어 두었습니다.
- **TTL 5분/30분은 최적값이 아닙니다.** 데이터 변경 특성으로 정한 초기값이고, TTL별 성능 비교는 하지 않았습니다. Grafana의 적중률·축출을 보고 조정할 값입니다. ([근거](docs/evidence/2026-08-20-map-cache-split.md))
- **사진 업로드는 저장 한도 동시성 경로만 확인했습니다.** 실제 S3 저장·QR·친구 경로는 아직 테스트가 없습니다.
- LocalStack과 실제 S3의 동작 차이(Content-Type, presigned URL 세부)는 실제 AWS에서 재검증이 필요합니다.
- **상시 공개 인스턴스가 없습니다.** 비용 때문에 필요할 때만 켭니다. 퍼블릭 IP는 Elastic IP를 붙이지 않아 켤 때마다 바뀝니다.
- **Flutter 정적 분석은 error만 파이프라인을 막습니다.** `info` 지적(`avoid_print` 등)이 많아 우선 error만 막고 점진적으로 줄입니다.
- **Sentry에 접속 IP 기반 위치가 저장됩니다.** SDK에서 IP를 지워도 Sentry가 수집 시점의 접속 IP로 지역을 역산합니다. 막으려면 프로젝트 설정에서 `Prevent Storing of IP Addresses`를 켜야 합니다.
- **Sentry 알림 규칙이 없습니다.** 어떤 이벤트에 누구에게 알릴지는 정하지 않았습니다.
- **동시성은 사진 저장 한도만 확인했습니다.** "행 개수"나 "합계"에 대한 조건은 전부 같은 위험을 갖습니다. 전수 점검은 하지 않았습니다.
- **사진 한도 동시성은 로컬 Docker PostgreSQL 17.10에서도 확인했습니다.** 동시 8건의 한도 보장과 잠금 대기·타임아웃·데드락 감지 동작을 검증했지만, 운영 Supabase 부하를 재현한 결과는 아닙니다. ([근거](docs/evidence/2026-08-20-postgresql-photo-quota-concurrency.md))
- **다른 도메인에도 `IllegalStateException`이 남아 있을 수 있습니다.** 친구 도메인만 도메인 오류로 정리했습니다.
