# 네컷모아 (NEMO)

셀프 포토부스 사진을 QR 또는 직접 업로드로 모아 클라우드에 보관하고, 날짜·브랜드·장소·친구 기준으로 정리해 캘린더 회고와 공유 앨범을 제공하는 앱입니다.

> ### 이 저장소에 대하여
>
> **원본**: [KDUcapstone/nemo-app](https://github.com/KDUcapstone/nemo-app) — KDU 캡스톤 3인 팀 졸업작품 (김한욱 · 문한일 · 임다빈)
>
> 이 저장소는 **김한욱의 개인 고도화 사본**입니다. 팀 작업 히스토리를 그대로 옮겼기 때문에 커밋 작성자는 원본과 동일하며, 팀원이 작성한 코드가 그대로 포함되어 있습니다. 아래 [내 담당 범위](#내-담당-범위)에 실제 기여 경계를 커밋 수와 함께 적었습니다.
>
> 팀 공동 작업은 원본 저장소에서 계속되고, 이 저장소에는 개인적으로 추가한 테스트·문서·배포 작업이 올라갑니다.

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

- **JWT 인증 테스트 37개** — [Case Study](docs/portfolio/case-study-jwt-authentication.md)
- Supabase PostgreSQL 전환 및 런타임 하드닝 — [Case Study](docs/project/case-studies/2026-08-02-supabase-postgresql-runtime-hardening.md)

### 팀원 주도

- `photo` (7 / 55), `album` (6 / 40) — 사진 업로드·앨범 도메인의 본체는 팀원이 구현했습니다. 저는 API Contract 조율과 통합 시나리오 검증에 참여했습니다.
- Flutter 클라이언트 (12 / 165) — 화면 구현은 팀원이 담당했습니다.

> 커밋 수는 기여의 **경계**를 보여주기 위한 것이지 크기를 재는 지표가 아닙니다. 한 줄짜리 커밋과 300줄짜리 커밋이 같은 1로 세어집니다.

## Case Studies

| 주제 | 상태 | 핵심 증거 |
|---|---|---|
| [JWT 인증 경로에 검증 가능한 경계 세우기](docs/portfolio/case-study-jwt-authentication.md) | `Verified` | 테스트 37개, 클럭 스큐·`isExpired()` 동작 규명 |
| [Supabase PostgreSQL 전환 후 런타임 하드닝](docs/project/case-studies/2026-08-02-supabase-postgresql-runtime-hardening.md) | `Verified` | 프로필 분리, 운영 smoke test, 개발용 표면 차단 |

문서 상태 규칙(`Draft` / `Verified` / `Historical`)은 [문서 허브](docs/project/README.md)에 있습니다.

## 기술 스택

**Backend** — Java 21 · Spring Boot 3.5.3 · Spring Security + JWT(jjwt) · Spring Data JPA · springdoc-openapi · Actuator · AWS SDK v2 (S3) · ZXing(QR) · Jsoup

**Frontend** — Flutter (Dart SDK 3.8+) · provider · http · flutter_naver_map · mobile_scanner(QR) · image_picker · geolocator

**Data / Infra** — PostgreSQL(Supabase, 운영) · H2(개발) · MariaDB(레거시) · LocalStack · Docker · Nginx

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

### Test

```bash
cd backend && ./gradlew test
```

48 tests. Gradle toolchain이 **Java 21**을 요구합니다 — JDK 23에서는 빌드가 깨집니다.

## 알려진 한계

포트폴리오 문서가 실제보다 앞서 나가지 않도록, 현재 확인되지 않은 것을 적어 둡니다.

- 사진·앨범·타임라인 경로에는 아직 테스트가 없습니다. 인증 경로만 덮여 있습니다.
- 성능 수치를 측정하지 않았습니다. Caffeine 캐시가 적용돼 있으나 적용 전후 호출 수·응답시간을 비교하지 않았습니다.
- LocalStack과 실제 S3의 동작 차이(Content-Type, presigned URL 세부)는 실제 AWS에서 재검증이 필요합니다.
- 배포는 Railway + Supabase 방향으로 설계했으나 상시 공개 인스턴스는 아직 없습니다.
