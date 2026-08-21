---
title: 배포할 곳이 없던 서비스를 AWS에 올리고 일부러 망가뜨려 보기
status: Verified
date: 2026-08-21
---

# Case Study — 배포할 곳이 없던 서비스를 AWS에 올리고 일부러 망가뜨려 보기

> **한 줄 요약** — CI는 있는데 **배포 단계가 없었습니다.** EC2 1대로 올리면서 정적 AWS 키를 IAM Role로 바꿨고, 그 과정에서 **anon 키로 전체 테이블에 접근 가능한 권한**이 열려 있던 것을 발견해 막았습니다. 배포로 끝내지 않고 앱·DB·S3를 차례로 망가뜨려 **중단 24.7초 / DB 복구 3.05초**를 실측했습니다.

| | |
|---|---|
| **기간** | 2026-08-21 |
| **범위** | Backend — AWS EC2 배포, IAM, Supabase, 장애 실험 |
| **결과** | 인터넷에서 동작하는 배포 + 장애 3종 실측 + DB 노출 권한 차단 |
| **월 비용** | **$0.73** (정지 시) / **$1.85** (데모 하루 2h) |
| **추가 인프라** | EC2 1대 + 보안그룹. **그 외 없음** |

> ⚠️ **단일 인스턴스입니다.** 고가용성을 구축하지 않았습니다.
> 인스턴스가 죽으면 서비스가 멈춥니다(Docker 재시작으로 24.7초 뒤 복구).

---

## Gap — 배포할 곳이 없었다

CI는 5개 잡으로 잘 돌고 있었습니다([CS 07](07-ci-cd.md)). 그런데 `deploy.yml:108`에 이렇게 적혀 있었습니다.

```
echo "실제 배포 단계는 아직 연결하지 않았습니다."
echo "대상 플랫폼이 정해지면 이 잡 뒤에 배포 스텝을 추가합니다."
```

**테스트 → 빌드 → 이미지까지만 있고 그 뒤가 없었습니다.** 이력서에 쓸 URL도 없고, "운영에서 어떻게 되나"를 말할 근거도 없었습니다.

### 저장소를 먼저 읽고 확인한 것

| 항목 | 상태 | 근거 |
|---|---|---|
| Docker 이미지 | ✅ | `backend/Dockerfile` 멀티스테이지 |
| CI | ✅ 5잡 게이트 | `.github/workflows/ci.yml` |
| **배포** | ❌ 없음 | `deploy.yml:108` |
| DB | 외부 Supabase 재사용 가능 | `application-prod.yml:13` `${DB_URL}` |
| S3 | ⚠️ **정적 키** | `application-prod.yml:39-40` |
| health | ⚠️ `/actuator/health`만 | readiness/liveness 그룹 없음 |
| 관리 포트 | ✅ 9090 분리 | `application-prod.yml:66` |

### 다중 인스턴스에서 깨지는 JVM 내부 상태 — 신규 2건

| 위치 | 문제 |
|---|---|
| `NaverApiClient:218` `nextSlotAtNanos` | 리미터 ([CS 11](11-rate-limiter-concurrency.md)) |
| `NaverResponseCache` | 캐시 ([CS 05](05-map-api-cache.md)) |
| **`PasswordResetService:56`** | **비밀번호 재설정 토큰이 메모리에.** A에서 발급 → B로 라우팅되면 실패 |
| **`EmailVerificationService:27`** | **이메일 인증코드가 메모리에.** 같은 문제 |

**리미터보다 이 둘이 다중 인스턴스의 진짜 블로커입니다.** 사용자가 바로 겪는 실패이기 때문입니다.

---

## AWS 후보와 비용

> ⚠️ `pricing:GetProducts` 권한이 없어 **AWS 가격 API로 검증하지 못한 공시 요율 추정치**입니다.
> 프리티어 잔여 여부도 확인 권한이 없어 **프리티어 없음 기준**입니다.
>
> **정정(리뷰 반영)** — 처음에는 EC2 비용에서 **Public IPv4 요금을 빠뜨렸습니다.**
> Fargate 쪽에는 넣고 EC2 쪽에는 안 넣어 비교가 불공정했습니다.
> 2024년 2월부터 EC2 의 퍼블릭 IPv4 도 시간당 $0.005 로 과금됩니다.

| 기준 | A. EC2 t3.micro | B. ECS Fargate | C. Lightsail |
|---|---|---|---|
| **월 비용** | **$13.87** | $15.93 | $10.00 |
| 필요할 때만 켜기 | ✅ **$1.51** | 애매 | ❌ 시간제 없음 |
| 초기 설정 | 중 | 중상 | 하 |
| 운영 | 중 | **하** (자동 교체) | 중 |
| health check | 직접 구성 | 내장 | 직접 |
| 로그 | `docker logs` | CloudWatch 자동 | 직접 |
| 수평 확장 | ❌ | ✅ | ❌ |
| 포트폴리오 가치 | 중 | **상** | 하 |
| 권한 제약 | 우회 가능 | TaskRole 필요 | **권한 없음** |

**EC2 상세**: t3.micro 730h × $0.0130 = $9.49 · EBS gp3 8GB × $0.0912 = $0.73 ·
**Public IPv4 730h × $0.005 = $3.65** → 합계 **$13.87**

**Fargate 상세**: vCPU 0.25 × 730h × $0.04656 = $8.50 · 메모리 1GB × 730h × $0.00511 = $3.73 ·
Public IPv4 $3.65 = $15.93

**두 안의 차이는 $6.06 이 아니라 $2.06 입니다.** 처음 계산에서 EC2 쪽 IPv4 를 빠뜨려
차이를 세 배로 부풀려 보고 있었습니다. 그래도 EC2 를 고른 판단은 유지합니다 —
**정지 가능 여부**가 결정적이기 때문입니다(아래 참고).

### ⚠️ t3.micro 는 `unlimited` 모드입니다

실측: `aws ec2 describe-instance-credit-specifications` → **`unlimited`**

CPU 베이스라인(10%)을 넘겨 쓰면 **surplus credit 이 추가 과금**됩니다
(vCPU-시간당 $0.05). 위 계산에는 들어 있지 않습니다.
지금은 트래픽이 거의 없어 베이스라인을 넘길 일이 없지만,
부하 테스트를 오래 돌리면 예상 밖 요금이 붙을 수 있습니다.
`standard` 로 바꾸면 초과분을 과금 대신 스로틀링으로 처리합니다.

### 제외한 것
EKS · Kubernetes · RDS 신규 · **NAT Gateway($32/월)** · **ALB($16/월)** · Multi-AZ · Auto Scaling

ALB 없이 인스턴스 퍼블릭 IP로 직접 받습니다. 트래픽이 거의 없는 포트폴리오에 로드밸런서는 비용만 두 배로 만듭니다.

---

## 사람이 선택한 것

**AI는 비교표와 추천까지, 결정은 개발자가 했습니다.**

| 결정 | 선택 | 기준 |
|---|---|---|
| 아키텍처 | **EC2 t3.micro** | 필요할 때만 켜면 $1.51. Fargate는 상시 전제라 $16 |
| 비용 상한 | **월 $5 이하, 필요할 때만 실행** | 상시 URL을 포기하고 비용을 택함 |
| IAM | **최소 권한만 추가** | `AdministratorAccess` 거부 |
| Redis | **도입 안 함** | 인스턴스 1개 |

---

## 배포 구조

```
GitHub Actions (deploy.yml)
   ├─ 배포 전 검증  테스트 + secret 확인
   └─ 이미지 push   ghcr.io/hwkimv/nemo/backend:<SHA>
                          ↓  (public, 인증 없이 pull)
              EC2 t3.micro (ap-northeast-2c)
                 docker run --restart unless-stopped
                 -p 8080:8080  -m 700m
                 --env-file /home/ec2-user/nemo.env (600)
                          ↓                    ↓
        Supabase PostgreSQL          S3 nemo-s3-prod
        (Session pooler 5432)        (IAM Role, 정적 키 없음)
```

### 왜 Session pooler(5432)인가

직결 주소를 쓰려다 막혔습니다.

```
db.<ref>.supabase.co                     → IPv6 만
aws-0-ap-northeast-2.pooler.supabase.com → IPv4 3개, 5432/6543 열림
```

**기본 VPC의 EC2에는 IPv6가 없습니다.** 직결로는 붙지 않습니다.
Transaction pooler(6543)가 아니라 Session(5432)을 씁니다 — Transaction 모드는
prepared statement를 유지하지 않아 Hibernate와 궁합이 나쁩니다.

EC2에서 실제로 확인:
```
postgres | tables=11 | server=2406:da12:... | version=17.6
```
`inet_server_addr`가 IPv6를 반환합니다. **pooler가 IPv4로 받아 내부에서 IPv6 백엔드로 넘깁니다.** 우려했던 문제를 pooler 구조가 해결합니다.

---

## 보안 / IAM

### 정적 키를 없앴다

`S3Config`가 **항상 `StaticCredentialsProvider`만** 써서 AWS 위에서 돌아도 IAM Role을 쓸 수 없는 구조였습니다.

```java
키가 비면   → DefaultCredentialsProvider  (인스턴스 프로파일 / TaskRole)
키가 있으면 → StaticCredentialsProvider   (LocalStack, AWS 밖)
```

**장기 Access Key는 한 번 새면 직접 폐기하기 전까지 유효합니다.** 인스턴스 프로파일은 수 시간마다 도는 임시 자격증명을 줍니다.

### 최소 권한 — 실제로 확인

역할 `nemo-ec2-role`에는 액션 5개만 있습니다.

```
GetObject / PutObject / DeleteObject  →  arn:aws:s3:::nemo-s3-prod/*
ListBucket / GetBucketLocation        →  arn:aws:s3:::nemo-s3-prod
```

EC2에서 런타임 검증:

| 동작 | 결과 |
|---|---|
| 컨테이너 안 `AWS_ACCESS_KEY` 환경변수 | **0개** |
| IMDSv2 → 역할 | `nemo-ec2-role` |
| Get / Put / Delete / List | ✅ 성공 |
| **`ListAllMyBuckets`** | ❌ **AccessDenied** |
| 다른 버킷 | ❌ 접근 불가 |

`iam:PassRole`도 좁혔습니다.

```json
"Condition": { "StringEquals": { "iam:PassedToService": "ec2.amazonaws.com" } }
```

시뮬레이션으로 확인:

| 시나리오 | 결과 |
|---|---|
| `nemo-ec2-role` → EC2 | `allowed` |
| `nemo-ec2-role` → Lambda | `implicitDeny` |
| `ecsTaskExecutionRole` → EC2 | `implicitDeny` |

### ⚠️ anon/authenticated 롤이 모든 테이블에 접근 가능했다

스키마를 넣자마자 확인해 보니 **11개 테이블 전부** 이랬습니다.

```
anon_select = true, anon_insert = true, authenticated_select = true
```

Supabase는 `public` 스키마를 **PostgREST(Data API)로 노출**합니다.
anon 키는 프론트엔드에 박혀 나가는 **공개 값**이라 비밀이 아닙니다.
Data API 가 켜져 있다면 이런 요청이 통과합니다.

```
GET  /rest/v1/users?select=*            비밀번호 해시·이메일
GET  /rest/v1/refresh_tokens?select=*   세션 토큰 전부
POST /rest/v1/photos                    임의 삽입
```

[CS 03](03-security-boundaries.md)에서 로그의 토큰을 지웠는데 DB 권한이 열려 있으면 의미가 없습니다.

> **어디까지 확인했고 어디부터 확인 못 했나 (리뷰 반영)**
>
> 확인한 것 — `has_table_privilege('anon', ...)` 로 **PostgreSQL 권한 수준**에서
> 11개 테이블 전부 SELECT·INSERT 가능함을 봤습니다. 이건 실측입니다.
>
> **확인하지 못한 것** — anon 키로 실제 `GET /rest/v1/users` 를 보내
> **200 과 데이터를 받아내는 end-to-end 증명은 못 했습니다.**
> 권한을 회수한 뒤에 검증을 시도했기 때문입니다.
>
> 사후에 일회용 테이블로 경로를 재현하려 했으나, 그 시점에 PostgREST 가
> `PGRST002 Could not query the database for the schema cache` 로 응답했습니다.
> **제 권한 회수가 Data API 자체를 망가뜨린 것**으로 보입니다
> (`/rest/v1/` 가 404 가 아니라 PostgREST 오류를 주므로 엔드포인트 자체는 살아 있습니다).
>
> 따라서 정확한 표현은 **"DB 전체가 인터넷에 열려 있었다"** 가 아니라
> **"Data API 가 활성화될 경우 전체 테이블이 노출되는 anon/authenticated 권한이 있었고, 그것을 제거했다"** 입니다.
> 보안 문구는 실측 범위를 넘지 않게 적습니다.

**RLS 대신 권한 회수를 골랐습니다.** NEMO는 Spring Boot가 `postgres` 롤로 직접 붙고 Supabase 클라이언트 SDK를 쓰지 않습니다. PostgREST는 **우리가 쓰지 않는 문**입니다. 쓰지 않는 문은 정책으로 지키는 것보다 닫는 편이 확실합니다.

| | 적용 전 | 적용 후 |
|---|---|---|
| `anon` SELECT/INSERT | 가능 | **차단** |
| `authenticated` SELECT | 가능 | **차단** |
| 앱 롤(`postgres`) | ✅ | ✅ **유지** |
| 남은 권한 | 11테이블 전부 | **0건** |

`ALTER DEFAULT PRIVILEGES`도 걸어 **앞으로 만들 테이블도 자동으로 닫힙니다.** 나중에 스키마를 다시 만들었을 때 실제로 자동 적용되는 것을 확인했습니다.

> **부수 효과** — 이 회수 이후 Supabase Data API 가 `PGRST002` 로 응답합니다.
> PostgREST 가 스키마 캐시를 만들지 못하는 상태입니다.
> NEMO 는 Data API 를 쓰지 않으므로 서비스에는 영향이 없지만,
> **나중에 Flutter 에서 Supabase 클라이언트 SDK 를 쓰려면 이 결정을 되돌려야 합니다.**
> 그때는 권한 회수 대신 RLS + 테이블별 정책으로 가야 합니다.

### 이미지를 공개하기 전에 검사했다

GHCR 패키지를 public으로 바꾸기 전에 **실제 이미지를 pull 해서** 검사했습니다.

| 검사 | 방법 | 결과 |
|---|---|---|
| `.env` 파일 | `docker export` → `tar -tf` | **0개** |
| 개인키 | `BEGIN * PRIVATE KEY` 전체 검색 | **0개** |
| `docker history` | `--no-trunc` 20줄 | 비밀값 없음 |
| `docker inspect` ENV | 7개 전부 | 베이스 이미지 값 + `SPRING_PROFILES_ACTIVE` |
| **실제 비밀값 문자열** | `.env` 값으로 파일시스템 `grep -rF` | **없음** |

> `.pem` 20여 개가 잡혔지만 전부 `/etc/ssl/certs/`의 **공개 CA 루트 인증서**입니다.

**실제 위험을 하나 찾았습니다.** `.dockerignore`에 `.env`가 없었습니다. 지금 이미지는 Actions가 만들어 안전하지만(`.env`가 `.gitignore`에 있어 체크아웃에 없음), **로컬 빌드는 그 보호를 못 받습니다.** 멀티스테이지라 최종 이미지엔 안 남아도 **builder 레이어에 남고 `cache-to: mode=max`로 캐시에 올라갑니다.**

---

## 장애 재현

배포 성공에서 끝내지 않고 **일부러 망가뜨렸습니다.**

### A. 애플리케이션 종료

| 항목 | 값 |
|---|---|
| 주입 | 호스트에서 JVM 프로세스에 `SIGKILL` |
| 첫 실패 감지 | 94ms 후 |
| 복구 완료 | 24,760ms 후 |
| **실제 중단 시간** | **24.7초** |
| 자동 재시작 | ✅ `RestartCount` 1 → 2 |
| 앱 기동 시간 | 21.1초 |

**중단 시간의 85%가 JVM 기동 시간입니다.** Docker 재시작 자체는 1초 미만입니다.

#### 방법을 두 번 고쳤다

| 시도 | 관측 | 왜 틀렸나 |
|---|---|---|
| `docker exec ... kill -9 1` | 247ms 복구, 재시작 0 | **PID 네임스페이스 안에서 PID 1은 SIGKILL을 무시한다.** 아무 일도 안 일어남 |
| `docker kill` | exit 137, **재시작 안 됨** | Docker가 *수동 중지*로 보고 restart 정책을 적용하지 않음 |
| 호스트에서 `kill -9 <hostpid>` | ✅ 진짜 크래시 | |

첫 결과를 그대로 적었다면 **"247ms 만에 복구된다"는 거짓 수치**가 남았을 것입니다.
1초 간격 폴링으로도 다운 구간을 놓쳐, 200ms 간격으로 외부 응답을 관찰해 다시 쟀습니다.

### B. DB 연결 오류

| 항목 | 값 |
|---|---|
| 주입 | `DOCKER-USER` 체인에서 pooler IP 3개 5432 `DROP` |
| 요청 응답 | **HTTP 500**, 30.0초 (Hikari 타임아웃) |
| `/actuator/health` | **`{"status":"DOWN"}` 503** |
| health 응답 시간 | **30초** ⚠️ |
| 앱 프로세스 | **죽지 않음** |
| DB 복구 후 | **3.05초** 만에 자동 복구, 재시작 불필요 |
| DB 안 쓰는 요청 | ✅ 4ms 정상 응답 |

#### 여기서도 방법이 두 번 틀렸다

- `/etc/hosts` 조작만으로는 **기존 커넥션 풀이 살아 있어** 재현되지 않았습니다
- 방화벽 규칙을 `OUTPUT` 체인에 넣었는데 **컨테이너 트래픽은 `FORWARD`를 탑니다.** `DOCKER-USER`로 옮겨야 했습니다
- 복구가 안 되길래 방화벽을 의심했는데, 실제 원인은 앞서 `sed -i`가 `Device or resource busy`로 실패해 **`/etc/hosts`에 조작이 남아 있던 것**이었습니다

### C. S3 권한 오류

| 항목 | 값 |
|---|---|
| 주입 | 역할이 접근 못 하는 버킷을 가리키게 함 |
| 기동 | ✅ **정상 기동** (S3 없이도 뜸) |
| 업로드 | **HTTP 502** `STORAGE_FAILED`, **0.41초** |
| 사진 목록 조회 | ✅ 200 |
| 앨범 목록 조회 | ✅ 200 |
| **정리 작업 생성** | **0건** |
| 복구 후 업로드 | ✅ 201 |

**정리 작업 0건이 올바른 동작입니다.** S3 업로드가 실패했으니 고아 객체가 없고 치울 것도 없습니다. [CS 10](10-storage-consistency.md)의 정리 작업은 *"S3 성공 → DB 실패"* 전용이라, 여기서 만들어지면 오히려 버그입니다.

> IAM 정책을 Deny로 바꾸려던 첫 시도가 `AccessDenied`로 막혔습니다 — `IAMReadOnlyAccess`뿐이라서입니다. **최소 권한이 의도대로 동작한다는 부수적 증거**가 됐습니다.

### 종합 — 장애 격리

| 장애 | 영향 범위 | 자동 복구 |
|---|---|---|
| 앱 크래시 | 전체 24.7초 | ✅ Docker restart |
| DB 단절 | DB 쓰는 요청만 (500) | ✅ 3.05초, 앱 재시작 불필요 |
| S3 불가 | **업로드만** (502) | ✅ 즉시 |
| 지도 키 없음 | **지도만** | — (기동 경고) |

---

## 배포하며 고친 것 3가지

### 1. 지도 키가 없으면 앱이 아예 안 떴다

`@Value("${NAVER_LOCAL_CLIENT_ID}")`에 기본값이 없어 **지도 하나 때문에 앨범·타임라인·인증까지 전부 못 떴습니다.** `application-prod.yml`에 `naver` 블록도 없었습니다.

[CS 05](05-map-api-cache.md)에서 `S3PhotoStorage`에 내렸던 판단과 정반대였습니다 — 그때는 "스토리지 장애의 영향 범위를 파일 요청으로 좁힌다"고 했는데 지도는 서비스 전체를 죽이고 있었습니다. 빈 기본값 + 기동 경고로 맞추고 테스트로 고정했습니다.

### 2. Flyway가 있는 걸 모르고 스키마를 통째로 넣었다

**"마이그레이션 도구가 없다"고 적었는데 틀렸습니다.** Flyway가 `build.gradle:30`에 있고 prod에서 켜져 있습니다.

`V1__album_photo_and_photo_tag.sql`은 **base 위에 얹는 증분 마이그레이션**인데, Hibernate 생성 DDL을 통째로 먼저 넣어서:

```
V1: ALTER TABLE album_photos ADD COLUMN sequence
→ column already exists → Flyway 실패 → 컨테이너 재시작 루프
```

DB를 되돌려 `base(sequence·PK·photo_tag 제외)` → `Flyway V1` 순서로 다시 만들었습니다. 데이터 0행이라 잃은 것은 없습니다.

### 3. 배포 파이프라인이 여전히 정적 키를 요구했다

`deploy.yml`이 `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY`를 필수로 검증하고 있었습니다. 단순히 불필요한 게 아니라 **해롭습니다** — 이 secret을 넣는 순간 `S3Config`가 정적 키 분기를 타서 **인스턴스 프로파일을 안 씁니다.**

`SENTRY_DSN`도 차단 조건에서 경고로 낮췄습니다. 앱은 DSN이 없어도 도는데(`application.yml:109` `${SENTRY_DSN:}`) 검증이 앱보다 엄격해 배포가 막혔습니다.

---

## 비용 통제

| 시나리오 | 월 |
|---|---:|
| **평소 정지, 볼륨만** | **$0.73** |
| 데모 하루 2h × 30일 | **$1.85** |
| 실수로 상시 실행 | **$13.87** |

**정지하면 Public IPv4 도 함께 사라집니다**(Elastic IP 를 안 붙였기 때문).
그래서 정지 시 비용은 EBS $0.73 뿐이고, 이 "끌 수 있다"는 점이
Fargate 대비 EC2 를 고른 실질적 이유입니다.

AWS Budgets 월 $5 + 알림 3단계(실제 80% / 실제 100% / **예측 100%**)를 걸었습니다.
작업 종료 시점 **실지출 $0.03**입니다.

> **AWS에는 진짜 하드 상한이 없습니다.** Budget Action이 가장 가깝지만 청구 데이터가 수 시간 지연되고, 실행 역할과 `iam:PassRole` 확장이 필요합니다. **리소스가 t3.micro 1대뿐이라 최대 손실이 $14 수준**이므로 넣지 않았습니다. (단 `unlimited` 모드라 CPU 를 오래 쓰면 그보다 늘 수 있습니다) 실제 통제는 "안 쓸 때 끄는 것"입니다.

---

## 남은 한계

- **단일 인스턴스입니다.** 고가용성이 아닙니다. 인스턴스가 죽으면 24.7초 멈춥니다
- **health가 DB 장애 시 30초 걸립니다.** 로드밸런서 타임아웃보다 길면 판정이 애매해집니다
- **readiness/liveness 분리가 없습니다.** "기동 중"과 "DB 장애"를 구분 못 합니다.
  Case A의 24.7초 중 21초가 기동인데, 이걸 liveness로 죽이면 무한 재시작에 빠집니다
- **퍼블릭 IP가 재시작마다 바뀝니다.** Elastic IP를 안 붙였습니다(정지 중 과금 회피)
- **HTTPS가 없습니다.** ALB도 도메인도 없어 평문 HTTP입니다
- **CloudWatch 로그 수집이 없습니다.** `docker logs`뿐이라 인스턴스가 사라지면 로그도 사라집니다
- **Prometheus/Grafana가 이 인스턴스에 붙어 있지 않습니다.** 관리 포트는 `127.0.0.1`에만 열려 있습니다
- **배포가 반자동입니다.** 이미지 빌드는 CI가 하지만 EC2 배포는 SSH 수동입니다
- **다중 인스턴스 실험을 아직 하지 않았습니다** — 로컬 Docker로 진행 예정
- **`.env`에 `SENTRY_DSN`이 비어 있어 오류 추적이 꺼져 있습니다**

---

## AI Agent 활용

[AI 보조 개발 워크플로우](../ai-development-workflow.md)를 이 작업에도 적용했습니다.

### AI가 한 것

| 단계 | 내용 |
|---|---|
| 저장소 분석 | 배포 gap 확인, JVM 내부 상태 4곳 식별(비밀번호 재설정·이메일 인증 신규 발견) |
| 아키텍처 비교 | EC2/Fargate/Lightsail 11개 기준 |
| 비용 계산 | 공시 요율 기반 추정 (API 검증 불가를 명시) |
| IAM 정책 초안 | 최소 권한 + `PassedToService` 조건 |
| 배포 실행 | 보안그룹·EC2 생성, 컨테이너 실행 |
| 검증 | 런타임 자격증명·최소권한·이미지 비밀값 검사 |
| 장애 시나리오 | 설계 + 주입 + 측정 |

### 사람이 한 것

| 결정 | 내용 |
|---|---|
| **아키텍처** | EC2 선택 (Fargate 대비 $6 차이를 비용으로 판단) |
| **비용 상한** | 월 $5, 상시 URL 포기 |
| **IAM 범위** | 최소 권한만 추가. `AdministratorAccess` 거부 |
| **DB 노출 차단 방식** | RLS 대신 권한 회수 |
| **이미지 공개** | 검증 후 승인 |
| **리소스 생성 승인** | 각 단계마다 |

### 절차가 막아 준 것

1. **거짓 수치를 안 남겼습니다** — PID 1 SIGKILL이 무시되는 걸 모르고 "247ms 복구"를 적을 뻔했습니다
2. **이미지 공개 전에 검사했습니다** — `.dockerignore` 구멍을 그때 찾았습니다
3. **틀린 서술을 정정했습니다** — "마이그레이션 도구 없음"은 사실이 아니었습니다

> AI를 **분석·구축·측정 보조**로 쓰고, **비용·보안·아키텍처 판단은 개발자가** 했습니다.

---

## 참고

- 스키마 부트스트랩: `tools/schema/sql/schema-postgres.sql` + `02-revoke-postgrest-exposure.sql`
- 배포 파이프라인: `.github/workflows/deploy.yml`
- 관련: [CS 07 — CI 관문](07-ci-cd.md) · [CS 10 — S3↔DB 정합성](10-storage-consistency.md) ·
  [CS 11 — 레이트 리미터](11-rate-limiter-concurrency.md)
