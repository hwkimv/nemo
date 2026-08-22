---
title: 배포할 곳이 없던 서비스를 AWS에 올리고 일부러 망가뜨려 보기
status: Verified
date: 2026-08-23
---

# Case Study — 배포할 곳이 없던 서비스를 AWS에 올리고 일부러 망가뜨려 보기

> **한 줄 요약** — CI는 있는데 **배포 단계가 없었습니다.** EC2 1대로 올리면서 정적 AWS 키를 IAM Role로 바꿨고, 그 과정에서 **Data API가 활성화될 경우 전체 테이블이 노출되는 `anon`/`authenticated` table privilege**를 발견해 제거했습니다(PostgreSQL 권한 수준에서 확인. HTTP 200 재현은 못 했습니다). 배포로 끝내지 않고 앱·DB·S3를 차례로 망가뜨려 **중단 24.7초 / DB 복구 3.05초**를 실측했습니다. 그 결과가 남긴 숙제 — health 신호가 하나뿐이라 '기동 중'과 'DB 장애'를 구분 못 하는 것, 배포가 사람 손에 있는 것 — 을 이어서 처리했습니다([후속](#후속--health-신호-분리와-배포-자동화-2026-08-22)).

| | |
|---|---|
| **기간** | 2026-08-21 ~ 2026-08-22 |
| **범위** | Backend — AWS EC2 배포, IAM, Supabase, 장애 실험, health 신호 분리, 배포 자동화 |
| **결과** | 배포 + 장애 3종 실측 + DB 노출 권한 차단 + liveness/readiness 분리 + 실패 시 자동 rollback |
| **월 비용** | **$0.73** (정지 시) / **$1.85** (데모 하루 2h) |
| **추가 인프라** | EC2 1대 + 보안그룹. **그 외 없음** |

> ⚠️ **단일 인스턴스입니다.** 고가용성을 구축하지 않았습니다.
> 인스턴스가 죽으면 서비스가 멈춥니다(Docker 재시작으로 24.7초 뒤 복구).

---

## Gap — 배포할 곳이 없었다

CI는 5개 잡으로 잘 돌고 있었습니다([CS 07](07-ci-cd.md)). 그런데 `deploy.yml` 의 마지막 스텝은 이랬습니다.

```
echo "실제 배포 단계는 아직 연결하지 않았습니다."
echo "대상 플랫폼이 정해지면 이 잡 뒤에 배포 스텝을 추가합니다."
```

**테스트 → 빌드 → 이미지까지만 있고 그 뒤가 없었습니다.** 실행 중인 인스턴스가 없으니 운영 환경에서의 설정·권한·장애 복구를 **검증할 방법 자체가 없었습니다.**

### 저장소를 먼저 읽고 확인한 것

| 항목 | 상태 | 근거 |
|---|---|---|
| Docker 이미지 | ✅ | `backend/Dockerfile` 멀티스테이지 |
| CI | ✅ 5잡 게이트 | `.github/workflows/ci.yml` |
| **배포** | ❌ 없음 | `deploy.yml` 마지막 스텝이 `echo` 로 끝남 |
| DB | 외부 Supabase 재사용 가능 | `application-prod.yml` `${DB_URL}` |
| S3 | ⚠️ **정적 키** | `application-prod.yml` `app.s3.accessKey/secretKey` |
| health | ⚠️ `/actuator/health`만 | readiness/liveness 그룹 없음 |
| 관리 포트 | ✅ 9090 분리 | `application-prod.yml` `management.server` |

### 다중 인스턴스에서 깨지는 JVM 내부 상태 — 신규 2건

| 위치 | 문제 |
|---|---|
| `NaverApiClient` `nextSlotAtNanos` 필드 | 리미터 ([CS 11](11-rate-limiter-concurrency.md)) |
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
| 필요할 때만 켜기 | ✅ **$1.85** (하루 2h) | 애매 | ❌ 시간제 없음 |
| 초기 설정 | 중 | 중상 | 하 |
| 운영 | 중 | **하** (자동 교체) | 중 |
| health check | 직접 구성 | 내장 | 직접 |
| 로그 | `docker logs` | CloudWatch 자동 | 직접 |
| 수평 확장 | ❌ | ✅ | ❌ |
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

ALB 없이 인스턴스 퍼블릭 IP로 직접 받습니다. 인스턴스가 1대이고 트래픽이 거의 없는 상태에서 로드밸런서는 분산할 대상도 없이 비용만 두 배로 만듭니다.

---

## 사람이 선택한 것

**AI는 비교표와 추천까지, 결정은 개발자가 했습니다.**

| 결정 | 선택 | 기준 |
|---|---|---|
| 아키텍처 | **EC2 t3.micro** | 정지하면 $0.73, 하루 2h 데모면 $1.85. Fargate는 상시 전제라 $15.93 |
| 비용 상한 | **월 $5 이하, 필요할 때만 실행** | 상시 URL을 포기하고 비용을 택함 |
| IAM | **최소 권한만 추가** | `AdministratorAccess` 거부 |
| Redis | **도입 안 함** | 인스턴스 1개 |

---

## 배포 구조

```
GitHub Actions (deploy.yml, 수동 실행)
   ├─ 배포 전 검증  테스트 + 배포 스크립트 문법 검사
   └─ 이미지 push   ghcr.io/hwkimv/nemo/backend:<SHA>
                          ↓  (public, 인증 없이 pull)
              EC2 t3.micro (ap-northeast-2c)
                 ~/nemo-deploy.sh <SHA>
                   pull → 컨테이너 교체 → /readyz 폴링
                   실패하면 이전 이미지로 자동 rollback
                          ↓
                 docker run --restart unless-stopped
                 -p 8080:8080  -m 700m  (비루트 uid 999)
                 --env-file /home/ec2-user/nemo.env (600)
                          ↓                    ↓
        Supabase PostgreSQL          S3 nemo-s3-prod
        (Session pooler 5432)        (IAM Role, 정적 키 없음)
```

**이것은 deployment automation 이지 continuous deployment 가 아닙니다.**
`main` push 마다 자동으로 나가지 않습니다 — 인스턴스가 평소 정지돼 있어
그렇게 하는 것이 맞지도 않습니다. 자세한 것은 [배포 문서](../../infra/deploy/README.md).

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

## 후속 — health 신호 분리와 배포 자동화 (2026-08-22)

위 장애 실험이 두 가지 숙제를 남겼습니다. 둘 다 처리했습니다.

### 1. `/actuator/health` 하나로는 두 질문에 답할 수 없다

실험 A와 B가 정반대의 요구를 만들었습니다.

| 실험 | 관측 | 이 신호로 무엇을 해야 하나 |
|---|---|---|
| A. 앱 크래시 | 복구 24.7초 중 **21.1초가 JVM 기동** | 기동 중에는 **죽이면 안 된다** |
| B. DB 단절 | 프로세스는 멀쩡, DB 안 쓰는 요청은 **4ms 정상** | **죽일 이유가 없다.** 트래픽만 빼면 된다 |

그런데 둘 다 `/actuator/health`가 `DOWN`을 반환합니다.
이걸 liveness로 쓰면 **기동 중인 앱을 계속 죽여 무한 재시작**에 빠지고,
B에서는 **죽일 필요가 없는 앱을 죽입니다.**

그래서 그룹을 나눴습니다.

```yaml
management.endpoint.health:
  probes.enabled: true
  group:
    liveness:   { include: livenessState,        additional-path: "server:/livez"  }
    readiness:  { include: readinessState,db,    additional-path: "server:/readyz" }
```

**liveness에 의존성을 하나도 넣지 않았습니다.** 무언가를 추가하려면
"그게 고장났을 때 프로세스를 죽이는 게 맞는가"에 먼저 답해야 하고,
대부분의 경우 답은 아니오입니다.

**S3도 readiness에 넣지 않았습니다.** 실험 C에서 S3가 죽어도 조회는 200이었습니다.
readiness에서 빼면 인스턴스 전체가 트래픽에서 제외되어 멀쩡한 조회까지 못 받습니다.

> **`additional-path`를 쓴 이유** — 관리 포트 9090은 `127.0.0.1`에만 바인딩돼 있어
> (CS 06 설계 유지) 컨테이너 밖에서 부를 수 없습니다. 실제로 호스트에서 확인했습니다.
> ```
> docker exec ... curl 127.0.0.1:9090/actuator/health  → 200
> 호스트에서   curl 127.0.0.1:9090/actuator/health  → 연결 불가
> ```
> 배포 판정에 쓰려면 `docker exec + curl`로 들어가야 하고, 그러면 이미지 안에
> `curl`이 있어야 합니다. `additional-path`는 **프로브 둘만** 서비스 포트에 얹습니다.
> `/actuator/prometheus`는 그대로 관리 포트에만 있습니다 — 배포된 컨테이너에서 확인했습니다
> (`8080/actuator/prometheus` → 404). 테스트로도 고정했습니다.

### 2. readiness가 DOWN을 답하는 데 30초가 걸렸다

readiness 프로브는 커넥션을 하나 빌려 검사합니다. 그래서 Hikari의
`connection-timeout`이 이 시간을 그대로 결정합니다. 기본값이 30초였습니다.

로드밸런서 health check 타임아웃은 보통 5~10초입니다. 30초가 걸리면
LB 입장에서 **"응답 없음"과 "DOWN 응답"이 구분되지 않습니다.**
지금은 LB가 없어 피해가 없지만 붙이는 순간 문제가 됩니다.

`connection-timeout: 5000`으로 낮췄습니다.
**대가**: 풀이 포화됐을 때도 5초에 실패합니다. 커넥션을 오래 기다려서라도
성공시키고 싶은 요청이 있다면 손해입니다. 현재 트래픽에서 풀 포화가 관측된 적이 없어
이쪽을 택했고, 관측되면 재검토할 값으로 주석에 남겼습니다.

### Before / After — 같은 방법으로 쟀습니다

주입은 실험 B와 동일합니다 — `DOCKER-USER` 체인에서 pooler IP 3개의 5432를 `DROP`.

| | Before (`/actuator/health` 하나) | After (분리 + 5s) |
|---|---|---|
| 정상 시 | `200 UP` 12ms | `/livez` **7.8ms** · `/readyz` **15.7ms** |
| **DB 차단 시 liveness** | — (구분 불가) | **`200 UP` 8.2ms** ← 죽이지 않습니다 |
| **DB 차단 시 readiness** | `503 DOWN` **34.5초** | `503 DOWN` **6.0초** |
| DB 안 쓰는 요청 | `200` 10ms | `200` 13ms |
| 프로세스 | 살아 있음 | 살아 있음 |
| DB 복구 후 readiness | — | **30ms** 만에 회복 |

**Before의 34.5초는 "DB가 죽었다"와 "앱이 죽었다"를 구분하지 못합니다.**
After는 `/livez`가 8.2ms에 UP을 답해 그 둘을 갈라 줍니다.

### Docker HEALTHCHECK — 관측용이지 복구 장치가 아닙니다

`Dockerfile`에 readiness를 보는 `HEALTHCHECK`를 넣었습니다.
**그런데 이것이 컨테이너를 재시작시키지 않는다는 것을 실제로 확인했습니다.**

DB를 차단한 채 60초를 관찰한 결과입니다.

| 경과 | HEALTHCHECK | 컨테이너 |
|---|---|---|
| +15s | `healthy` streak=2 | running **restarts=0** |
| +30s | `healthy` streak=2 | running **restarts=0** |
| **+45s** | **`unhealthy`** streak=3 | running **restarts=0** |
| +60s | `unhealthy` streak=4 | running **restarts=0** |

**`unhealthy`가 되어도 `RestartCount`가 0입니다.**
`--restart unless-stopped`는 **프로세스 종료**에만 반응합니다.
health 상태를 보고 컨테이너를 교체하는 것은 Swarm이나 Kubernetes이고,
여기는 단일 호스트의 `docker run`입니다.

이걸 혼동하면 **"자동 복구된다"고 믿으면서 실제로는 아무도 복구하지 않는 상태**가 됩니다.
HEALTHCHECK의 용도는 두 가지로 한정했습니다 — `docker ps`에서 눈으로 보기,
그리고 배포 스크립트의 성공 판정. DB가 복구되자 `healthy`로 돌아왔습니다.

### 3. 배포를 사람 손에서 뗐다

배포가 "SSH로 들어가 `docker pull` / `rm` / `run`을 손으로 치는 것"이었습니다.
그러면 **새 컨테이너가 실제로 요청을 받을 수 있는지 확인**하는 사람도,
**안 됐을 때 되돌리는** 사람도 없습니다. 기동에 21초 걸리는 앱이라
"떴네" 하고 창을 닫은 뒤에 죽는 경우를 사람이 잡지 못합니다.

#### 세 가지 방식을 비교했습니다

| 기준 | A. SSH from Actions | B. OIDC → SSM | **C. 호스트 스크립트** |
|---|---|---|---|
| 새 장기 자격증명 | **SSH 개인키 필요** | 불필요 | **불필요** |
| 인바운드 포트 | **22를 러너 대역까지 개방** | 불필요 | **불필요** |
| IP 변동 대응 | **깨짐** (Elastic IP 없음) | 무관 (instance-id) | **무관** |
| 인스턴스가 꺼져 있을 때 | 실패 | 실패 | 실행 자체를 안 함 |
| 구현 복잡도 | 중 | 중상 | **하** |
| rollback 구현 | 가능 | 가능 | **가능** |
| **현재 계정에서 가능한가** | 가능하나 위험 | **불가능** | **가능** |

**B를 먼저 검토했습니다.** 인바운드 포트도 장기 자격증명도 필요 없어 가장 낫습니다.
그런데 실측해 보니 셋 다 막혀 있었습니다.

```
aws iam list-open-id-connect-providers      → {"OpenIDConnectProviderList": []}
aws iam list-attached-user-policies …       → IAMReadOnlyAccess (provider·role 생성 불가)
aws iam list-attached-role-policies …       → nemo-s3-access 하나뿐 (SSM 미등록)
```

**없는 권한을 억지로 만들지 않았습니다.**

A는 가능하지만 이 환경에서 두 가지가 걸립니다. **Elastic IP를 안 붙여서
켤 때마다 퍼블릭 IP가 바뀝니다** — GitHub Secrets에 호스트를 박아두면 재기동마다
깨집니다. 그리고 22번은 현재 개발자 IP `/32`만 열려 있는데, 러너 대역까지 열면
경계가 크게 넓어집니다. **장기 SSH 키까지 새로 만들어야 합니다.**

> **사람이 고른 것: C.** 기준은 "기술적으로 멋진 것"이 아니라
> **"지금 규모에서 가장 작은 복잡도로 안전하게 도는 것"** 이었습니다.
> 인스턴스가 평소 정지돼 있는 운영 방식에는 push 방식 자체가 맞지 않습니다.

#### 배포 실험 — 성공 / 실패 / rollback

실패 주입은 **`/readyz`가 없는 구버전 이미지를 배포**하는 방식을 골랐습니다.
운영 데이터도 `nemo.env`도 건드리지 않으면서
"배포 계약을 만족하지 못하는 이미지"라는 현실적인 실패를 만듭니다.

| 시나리오 | 결과 | exit | A. 감지 | B. 롤백→UP | **C. 실제 중단** |
|---|---|---:|---:|---:|---:|
| 정상 배포 | 성공 | **0** | — | — | **24.1s** |
| `/readyz` 없는 이미지 (404) | 롤백 성공 | **1** | 29.1s | 24.0s | **52.4s** |
| `docker run` 1회 실패 | **롤백 성공** | **1** | 1.3s | 23.6s | **24.1s** |
| `docker run` 지속 실패 (후보 == 실패 이미지) | 롤백 대상 없음 | **2** | 1.5s | — | 미복구 |
| **로그 저장 실패 + readiness 실패** | **롤백 성공** | **1** | 28.1s | 24.4s | **52.4s** |
| **last-good 기록 실패 (새 버전 정상)** | **서비스 유지** | **4** | — | — | **없음** |
| 롤백 이미지도 기동 실패 | 사람 개입 | **2** | 1.6s | — | 미복구 |
| 이미지 pull 실패 | 기존 유지 | **3** | — | — | **없음** |
| 동시 배포 2개 | 하나만 진행 | **3** | — | — | — |

**8종 전부 EC2 에서 실제로 돌렸습니다.**

롤백 후 API도 확인했습니다 — `/readyz` 200 · `/` 200 · **로그인(DB 경유) 401** ·
보호 경로 401. 로그인이 401을 반환했다는 것은 **DB 조회까지 실제로 갔다 왔다**는
뜻입니다(존재하지 않는 계정). `/readyz`만 보고 "살아 있다"고 판단하지 않았습니다.

### 보조 작업이 복구를 막고 있었습니다 (2차 리뷰 지적)

배포 스크립트에는 복구와 직접 상관없는 곁일이 둘 있습니다 — **진단 로그 저장**과
**last-good 기록**. 둘 다 `set -euo pipefail` 아래 무방비였습니다.

| 곁일이 실패하면 | 예전 | 지금 |
|---|---|---|
| 로그 디렉터리를 못 만듦 | **rollback 하기 전에 스크립트 종료.** 서비스는 다운인데 종료 코드는 `1`(=복구 성공) | 경고만 남기고 **rollback 계속** → `1` |
| last-good 파일을 못 씀 | 새 버전이 정상인데 **임의 종료 → `1`**(=배포 실패로 읽힘) | 서비스 유지 + **`4`** |

**원칙은 하나입니다 — 진단도 기록도 복구보다 우선하지 않습니다.**
다만 무시한 실패는 무엇을 못 했는지 로그에 남깁니다. `|| true` 로 뭉개지 않았습니다.

`4` 를 새로 둔 이유는 `2`(롤백 실패)와 **서비스 상태가 정반대**이기 때문입니다.
하나로 합치면 감시 도구가 정상 서비스를 장애로 읽습니다.
그리고 이 실패의 피해는 제한적입니다 — `write_state` 가 tmp + `mv` 라 실패해도
**기존 state 가 그대로 남고**, 그 값은 예전에 readiness 까지 확인된 이미지입니다.
다음 배포의 롤백 후보가 한 단계 옛 버전이 될 뿐 **검증되지 않은 이미지로 롤백하지 않습니다.**

실측(EC2) — `NEMO_LOG_DIR=/proc/nope` 로 로그 저장을 실패시킨 뒤 배포를 실패시켰습니다.

```
ERROR: 로그 디렉터리를 만들지 못했습니다: /proc/nope
ERROR: 진단 로그 없이 롤백을 계속합니다 (복구가 우선입니다)
       롤백: ghcr.io/hwkimv/nemo/backend:83a154be...
       readiness UP (status=200)
       롤백 성공. 서비스는 이전 버전으로 살아 있습니다.
exit=1
```

### "26초 만에 롤백"은 한 조각이었습니다 (리뷰 지적)

처음 이 실험을 적을 때 **B 구간만** 말했습니다. 사용자가 서비스를 못 쓰는 시간은
**C 구간**입니다.

| | 리뷰 전 | 지금 |
|---|---:|---:|
| A. 배포 시작 → 실패 감지 | **123s** (120s 상한을 다 채움) | **29.1s** |
| B. 실패 감지 → 롤백 readiness UP | 26s | 24.0s |
| **C. 실제 서비스 중단** | **약 148s** | **52.4s** |
| D. 배포 시작 → 복구 완료 | 약 149s | **53.1s** |

> **`C ≠ A + B` 입니다.** `A + B = D`(53.1s)이고, C는 그보다 0.7초 짧습니다.
>
> ```
> T_START ──0.7s── T_DOWN ───────────── T_DETECT ──────────── T_RECOVER
>    │   docker pull    │                    │                     │
>    │ (기존 컨테이너 유지) │                    │                     │
>    ├─────────── A = 29.1s ─────────────────►│                     │
>    │                  │◄──────── B = 24.0s ───────────────────────►│
>    │                  │◄──────── C = 52.4s ───────────────────────►│
>    ├────────────────────── D = 53.1s ──────────────────────────────►│
> ```
>
> A에는 `docker pull` 구간이 들어 있는데 그때는 **기존 컨테이너가 아직 돌고 있습니다.**
> 중단은 `T_DOWN`부터입니다. 처음에는 이 둘을 섞어 "52.4초 = 29.1 + 24.0"으로
> 적었는데 계산이 맞지 않았습니다.

감지가 줄어든 것은 **404를 기다리지 않게** 고쳤기 때문입니다.
`/readyz`가 404면 그 이미지에 프로브가 없다는 뜻이라 기다려도 생기지 않습니다.

그 판정이 정상 배포를 죽이지 않는지 먼저 실측했습니다 — 같은 이미지를 두 번
기동시키며 200ms 간격으로 폴링한 결과 `000`(연결 거부) ×101 → `503` ×1 → `200`이었고
**404는 한 번도 나오지 않았습니다.** Spring Boot는 포트를 열기 전에 매핑을 등록합니다.

**남은 22초는 JVM 기동이라 줄일 수 없습니다** — 앱이 듣기 시작해야 404인지 알 수 있습니다.

> **정상 배포에도 23.7초의 중단이 있습니다.** 롤백과 무관하게 단일 인스턴스에서
> 컨테이너를 교체하기 때문입니다. 처음에는 이 값을 아예 말하지 않았습니다.
> "zero-downtime이 아니다"라는 말의 실제 크기가 이것입니다.

### `docker run` 실패는 한 번도 롤백된 적이 없었습니다 (리뷰 지적)

가장 아팠던 지적입니다.

```bash
start_container "$NEW_IMAGE"    # 조건문 밖 bare call
```

`set -euo pipefail`에서 이 호출이 실패하면 스크립트가 **그 자리에서 죽습니다.**
아래 롤백 구문에 도달조차 하지 않습니다.

더 나쁜 것은 종료 코드였습니다. 그때 종료 코드가 **1**인데,
이 스크립트의 계약에서 1은 **"새 배포 실패 + 이전 버전 복구 성공"** 입니다.
**기존 컨테이너를 이미 내린 뒤 서비스가 죽었는데 호출자에게는 살아 있다고 말합니다.**

기존 실패 실험(`/readyz` 없는 이미지)은 "기동은 성공, readiness 실패"라
**이 경로를 한 번도 지나가지 않았습니다.** 그래서 못 봤습니다.

고친 뒤 실제로 재현했습니다 — `docker` shim으로 첫 `run`만 실패시켜
운영 데이터와 `nemo.env`를 건드리지 않고 "기동은 실패했지만 롤백은 가능한" 상태를
만들었습니다. 결과는 위 표 C2입니다 — **감지 1.4초, 롤백 성공, exit 1.**

지금은 기존 컨테이너를 내린 뒤의 모든 실패가 `rollback_to_previous()` 하나로 모입니다.
`docker pull` 실패만 예외입니다 — 아직 아무것도 내리지 않았으므로 되돌릴 것이 없고,
기존 서비스가 그대로 살아 있습니다(exit 3).

**실패한 이미지가 last-good을 오염시키지 않는 것도 확인했습니다.**

```
last-good : ghcr.io/hwkimv/nemo/backend:75c8dfd…   ← 실패 후에도 그대로
실행 중   : ghcr.io/hwkimv/nemo/backend:75c8dfd…
```

readiness까지 확인된 이미지만 기록하기 때문입니다.
실패한 컨테이너의 로그는 `~/deploy-logs/`에 남아, 롤백으로 컨테이너가 사라진 뒤에도
원인을 볼 수 있습니다. 실제로 남은 마지막 줄이 정확히 실패 원인이었습니다.

```
WARN c.n.b.g.e.GlobalExceptionHandler : [NOT_FOUND] GET /readyz
```

### 부수적으로 고친 것 — 컨테이너가 root로 돌고 있었다

배포 검증 중에 확인했습니다.

```
Before : uid=0(root) gid=0(root)
After  : uid=999(nemo) gid=999(nemo)
```

앱은 8080(비특권 포트)만 열고 파일을 쓰지 않으므로 잃는 기능이 없습니다.

---

## 배포하며 고친 것 3가지

### 1. 지도 키가 없으면 앱이 아예 안 떴다

`@Value` 에 기본값이 없어 **지도 하나 때문에 앨범·타임라인·인증까지 전부 못 떴습니다.** `application-prod.yml`에 `naver` 블록도 없었습니다.

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
- **퍼블릭 IP가 재시작마다 바뀝니다.** Elastic IP를 안 붙였습니다(정지 중 과금 회피)
- **HTTPS가 없습니다.** 평문 HTTP입니다. 아래 참고
- **CloudWatch 로그 수집이 없습니다.** `docker logs`뿐이라 인스턴스가 사라지면 로그도 사라집니다
- **Prometheus/Grafana가 이 인스턴스에 붙어 있지 않습니다.** 관리 포트는 `127.0.0.1`에만 열려 있습니다
- **zero-downtime 이 아닙니다.** 포트 8080 을 직접 쓰는 단일 인스턴스라 컨테이너 교체 사이에 중단이 있습니다
- **DB 마이그레이션은 rollback 되지 않습니다.** Flyway 가 적용한 스키마 변경은 이전 이미지로 되돌려도 남습니다
- **배포 실패 알림이 없습니다.** 종료 코드로만 드러납니다
- **다중 인스턴스로 늘리면 레이트 리미터가 깨집니다** — 같은 jar 로 **JVM 2개**를 띄워 실측했습니다(각 5.0 → 합계 10.0 req/s). 문제가 되는 것은 컨테이너 격리가 아니라 `nextSlotAtNanos` 가 JVM 안의 필드라는 사실입니다. [CS 11](11-rate-limiter-concurrency.md#다중-인스턴스-한계--재현했습니다)
- **`.env`에 `SENTRY_DSN`이 비어 있어 오류 추적이 꺼져 있습니다**

---

## HTTPS — 지금은 조건이 안 됩니다

배포·rollback·health 작업을 끝낸 뒤 실제 조건을 확인했습니다.

| 필요한 것 | 현재 |
|---|---|
| 도메인 | **없음.** `route53domains:ListDomains` 권한도 없어 계정 보유분조차 확인 불가 |
| 고정 주소 | **없음.** Elastic IP 미부착 → 인스턴스를 켤 때마다 퍼블릭 IP가 바뀜 |
| ACM 인증서 | 확인 불가 (`acm:ListCertificates` AccessDenied) |

**self-signed 인증서를 만들어 두고 "HTTPS 구축"이라고 쓰지 않겠습니다.**
브라우저와 Flutter 클라이언트가 모두 거부하고, 신뢰 체인이 없으면
HTTPS가 막으려는 중간자 공격을 실제로는 막지 못합니다. **이름만 HTTPS입니다.**

ALB($16/월)나 Route53을 이것 때문에 새로 붙이지도 않았습니다.
현재 인스턴스 비용(정지 시 $0.73)보다 비싸고, 인스턴스가 1대라 분산할 대상도 없습니다.

**조건이 생기면 할 것 — 도메인 확보가 먼저입니다.**

```
1. 도메인 확보
2. Elastic IP 부착        ← 없으면 DNS 레코드가 재기동마다 어긋난다
                             (정지 중에도 과금되므로 상시 운용으로 바꾸는 결정이 함께 필요)
3. Caddy 또는 Nginx + Let's Encrypt 를 같은 인스턴스에 리버스 프록시로
4. 8080 을 보안그룹에서 닫고 443 만 개방
```

**ALB가 아니라 Caddy/Nginx를 고른 이유**는 인스턴스가 1대이기 때문입니다.
ALB는 여러 대에 나눠 보내는 장치인데 보낼 곳이 하나뿐이면 비용만 듭니다.
Let's Encrypt는 무료이고 갱신이 자동입니다.

> **2번이 공짜가 아닙니다.** Elastic IP는 인스턴스가 **정지 중일 때도 과금**됩니다.
> 지금의 "안 쓸 때 끈다"는 비용 전략과 정면으로 충돌합니다.
> HTTPS를 붙이는 결정은 사실상 **상시 가동으로 바꾸는 결정**과 묶여 있습니다.
> 그래서 "나중에 하면 되는 일"이 아니라 **운영 방식을 바꾸는 일**로 기록해 둡니다.

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
| **아키텍처** | EC2 선택 (Fargate 대비 월 $2.06 차이 + "정지하면 $0.73" 을 근거로 판단) |
| **비용 상한** | 월 $5, 상시 URL 포기 |
| **IAM 범위** | 최소 권한만 추가. `AdministratorAccess` 거부 |
| **DB 노출 차단 방식** | RLS 대신 권한 회수 |
| **이미지 공개** | 검증 후 승인 |
| **리소스 생성 승인** | 각 단계마다 |

### 절차가 막아 준 것

1. **거짓 수치를 안 남겼습니다** — PID 1 SIGKILL이 무시되는 걸 모르고 "247ms 복구"를 적을 뻔했습니다
2. **이미지 공개 전에 검사했습니다** — `.dockerignore` 구멍을 그때 찾았습니다
3. **틀린 서술을 정정했습니다** — "마이그레이션 도구 없음"은 사실이 아니었습니다

### 독립 리뷰가 6건을 더 잡았습니다

구축한 세션이 아닌 **별도 세션**에 PR 을 던져 리뷰를 받았습니다
([`review-deployment.md`](../../prompts/engineering/review-deployment.md)).

| # | 지적 | 판정 |
|---|---|---|
| 1 | base 스키마가 Flyway V1 과 같은 객체를 만든다 | ✅ **머지 차단** |
| 2 | S3 자격증명이 반쪽이면 조용히 다른 계정으로 붙는다 | ✅ **머지 차단** |
| 3 | EC2 쪽에만 Public IPv4 가 빠져 비교가 불공정하다 | ✅ **머지 차단** |
| 4 | "DB 전체가 인터넷에 열려 있었다"는 실측 범위를 넘는다 | ✅ 표현 축소 |
| 5 | `deploy.yml` 이 쓰지도 않는 secret 존재를 검사한다 | ✅ 제거 |
| 6 | 테스트 이름과 실제 검사 대상이 다르다 | ✅ 제거 |

**6건 전부 실제 문제였습니다.** 특히 1번이 아팠습니다 —
바로 위 [3. 배포하며 고친 것](#2-flyway가-있는-걸-모르고-스키마를-통째로-넣었다)에서 겪은 그 장애인데,
**문서에는 "고쳤다"고 적고 파일에는 경고 주석만 달아 놨습니다.** 재발하는 상태였습니다.

> 자기가 고쳤다고 믿는 것을 자기가 검증하면 못 찾습니다.
> 재발 방지로 `SchemaFlywayOverlapTest` 를 넣고, **수정 전 파일로 되돌려 실패하는 것까지** 확인했습니다.

> AI를 **분석·구축·측정 보조**로 쓰고, **비용·보안·아키텍처 판단은 개발자가** 했습니다.

---

## 참고

- 스키마 부트스트랩: `tools/schema/sql/schema-postgres.sql` + `02-revoke-postgrest-exposure.sql`
- 배포 파이프라인: `.github/workflows/deploy.yml`
- 관련: [CS 07 — CI 관문](07-ci-cd.md) · [CS 10 — S3↔DB 정합성](10-storage-consistency.md) ·
  [CS 11 — 레이트 리미터](11-rate-limiter-concurrency.md)
