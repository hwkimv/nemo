# 네컷모아 (NEMO)

네컷사진을 앨범으로 정리하고 공유하는 Flutter·Spring Boot 기반 서비스입니다.

## 프로젝트 구성

- `frontend/`: Flutter 클라이언트
- `backend/`: Spring Boot API 서버
- `docs/`: 기획, 설계, 구현 계획, 검증 기록

## 기술 스택

- Client: Flutter, Dart, Provider
- Server: Java 21, Spring Boot, Spring Security/JWT, Spring Data JPA
- Data/Infra: PostgreSQL 17.6 (Supabase), AWS S3, Docker

## 실행 프로필

| 프로필 | 목적 | 데이터베이스 | 주요 특성 |
|---|---|---|---|
| `dev` | 기본 개발·테스트 | H2 인메모리 | H2 콘솔과 Swagger 사용 가능 |
| `local` | 로컬 PostgreSQL 연동 | 환경변수 `DB_URL` | JWT·S3 값도 환경변수로 주입 |
| `prod` | 운영 조건 검증 | PostgreSQL | 스키마 검증, H2·Swagger 비활성화 |

기본 프로필은 `dev`입니다. 운영 프로필은 DB, JWT, 공개 URL, S3 관련 환경변수가 필요하며 실제 비밀값은 저장소에 커밋하지 않습니다.

## 실행 방법

### Backend

```powershell
cd backend
.\gradlew.bat bootRun
```

운영 조건에서는 필요한 환경변수를 주입한 뒤 프로필을 명시합니다.

```powershell
$env:SPRING_PROFILES_ACTIVE = "prod"
.\gradlew.bat bootRun
```

### Flutter

Android 에뮬레이터에서 로컬 Backend를 사용할 때:

```powershell
cd frontend
flutter run --dart-define=API_BASE_URL=http://10.0.2.2:8080
```

Mock API가 필요한 테스트·시연 환경에서는 명시적으로 활성화합니다.

```powershell
flutter run --dart-define=USE_MOCK_API=true
```

실기기는 `API_BASE_URL`에 개발 PC의 LAN IP를 사용해야 합니다. 평문 HTTP 허용은 Android debug 빌드에만 적용했으며 배포 환경은 HTTPS를 전제로 합니다.

## 검증

```powershell
cd backend
.\gradlew.bat clean test --no-daemon

cd ..\frontend
flutter test
flutter analyze
```

최신 검증 결과와 남은 제한사항은 [프로젝트 문서 인덱스](docs/project/README.md)와 [Supabase PostgreSQL 런타임 하드닝 사례](docs/project/case-studies/2026-08-02-supabase-postgresql-runtime-hardening.md)에 기록합니다.

## 역할과 기여 범위

포트폴리오에는 `PM + 백엔드 공동 담당`으로 표기합니다. 본인의 확인 가능한 기여는 요구사항·일정 조율, 인증·QR·앨범·사진·공유·스토리지 API, DB 전환 후 런타임 안정화와 검증 문서화입니다. 팀 전체 결과와 개인 구현을 구분해 설명합니다.
