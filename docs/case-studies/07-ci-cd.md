---
title: 테스트를 통과하지 않은 코드가 못 지나가게 막기
status: Verified
date: 2026-08-14
---

# Case Study — 테스트를 통과하지 않은 코드가 못 지나가게 막기

> **한 줄 요약** — 목표는 자동 배포가 아니라 **관문을 만드는 것**이었습니다. 파이프라인을 짜고 실제로 돌려보는 과정에서 **결함 5건**이 드러났습니다. 그중 3건은 CI를 실행해보지 않았다면 계속 몰랐을 것들입니다.

| | |
|---|---|
| **기간** | 2026-08-14 |
| **범위** | GitHub Actions, Dockerfile 검증, compose 설정 검증 |
| **결과** | 테스트 실패 시 빌드·이미지 단계 차단. **결함 5건 발견·수정** |
| **증거** | `.github/workflows/`, `actionlint` 통과, 테스트 94개 |

---

## Problem

테스트가 94개 있지만 **아무도 강제로 돌리지 않았습니다.**

- 로컬에서 `./gradlew test`를 잊고 push하면 그대로 넘어갑니다
- `backend/Dockerfile`은 `bootJar -x test`로 이미지를 만듭니다.
  **테스트를 건너뛰고 이미지가 만들어집니다.**
- 배포 대상이 아직 없어 "배포 전에 확인한다"는 절차 자체가 없었습니다

즉 **테스트를 통과하지 않은 코드가 이미지가 될 수 있는 상태**였습니다.

---

## Analyze / Constraints

**이미지 안에서 테스트를 돌려야 하는가?**

Dockerfile에 `bootJar -x test`가 있는 것을 "잘못됐다"고 볼 수도 있습니다.
하지만 이미지 빌드 시간에 테스트를 넣으면 빌드가 느려지고, 캐시가 깨질 때마다 다시 돕니다.

→ **이미지 안에서 돌리지 않는 대신, CI가 이미지 빌드 전에 통과를 보장**하면 됩니다.
`needs:`로 순서를 강제하면 테스트가 실패했을 때 이미지 잡은 아예 실행되지 않습니다.

**배포할 곳이 없다**

README에 적혀 있듯 상시 공개 인스턴스가 없습니다.
없는 배포 단계를 있는 것처럼 워크플로에 적어두면 문서가 실제보다 앞서 나갑니다.

→ 배포는 **수동 실행(workflow_dispatch)** 으로 두고, 대신 **배포 전 관문**을 코드로 고정합니다.

---

## Options

| 방법 | 장점 | 단점 | 선택 |
|---|---|---|---|
| Dockerfile에서 테스트 실행 | 이미지 자체가 보증 | 빌드 느려짐, 캐시 깨질 때마다 재실행 | ❌ |
| **CI 잡 의존성(`needs`)으로 순서 강제** | 빠르고 명시적, 실패 지점이 분명 | CI 밖에서 이미지를 만들면 우회 가능 | ✅ |
| 브랜치 보호 규칙만 사용 | 설정이 간단 | 무엇을 검사할지는 여전히 정의 필요 | 병행 |

두 번째를 고르고, 브랜치 보호 규칙은 저장소 설정에서 함께 켜는 것으로 정리했습니다.

---

## Action

### 파이프라인 구조

```
backend-test ──┬─> backend-build ──> docker-image
               └─> (실패하면 아래는 아예 실행되지 않는다)

compose-config  ┐
frontend        ┴─ 독립적으로 병렬 실행
```

| 잡 | 하는 일 |
|---|---|
| `backend-test` | `./gradlew test`. 실패 시 리포트를 아티팩트로 남김 |
| `backend-build` | `bootJar`. **테스트 통과 후에만** |
| `docker-image` | 이미지 빌드 + **실제로 기동되는지 확인**. push는 안 함 |
| `compose-config` | 프로필별 `docker compose config` + mount 대상 존재 확인 |
| `frontend` | `flutter analyze` + `flutter test` |

**이미지가 만들어졌다 ≠ 뜬다.** 그래서 빌드 후 컨테이너를 띄우고
`/actuator/health`가 응답할 때까지 기다립니다. 실패하면 로그를 출력하고 잡을 실패시킵니다.

### 배포는 관문만 먼저

`deploy.yml`은 수동 실행이며, 배포 전에 이것들을 확인합니다.

1. 테스트 재실행
2. **필수 secret이 실제로 설정돼 있는지 확인** — 값은 출력하지 않고 존재 여부만 봅니다
3. GHCR에 이미지 push

실제 배포 스텝은 대상 플랫폼이 정해지면 뒤에 붙입니다. **지금 없는 단계를 있는 것처럼 적지 않았습니다.**

---

## Result

### 파이프라인을 짜다 발견한 것 1 — compose가 없는 파일을 참조

```yaml
# compose.yaml (nginx-prod)
- ./infra/nginx/nemo.prod.conf:/etc/nginx/conf.d/default.conf:ro
```

`infra/nginx/`에는 `nemo.conf`와 `nemo.dev.conf`만 있었습니다.
**`nemo.prod.conf`는 존재하지 않았습니다.**

`nemo.conf`가 내용상 prod 설정(`server { }` 블록)이었으므로 이름을 바로잡았습니다.
`nemo.dev.conf`와 짝이 맞아 어느 프로필이 어느 파일을 쓰는지 이름만 보고 알 수 있습니다.

그리고 **같은 실수가 다시 나지 않도록 CI가 검사**합니다.

> `docker compose config`만으로는 이걸 못 잡습니다. 파싱은 파일 존재를 확인하지 않습니다.
> 그래서 config 출력에서 mount source를 뽑아 실제 존재 여부를 따로 확인합니다.
> 단, `frontend/build/web` 같은 **빌드 산출물은 제외**합니다. 체크아웃 직후 없는 것이 정상입니다.

### 파이프라인을 짜다 발견한 것 2 — S3 없이는 앱이 기동하지 않음

CI 러너에는 S3가 없습니다. 컨테이너 기동 확인 단계를 넣으려니 앱이 뜨지 않았습니다.

```java
} catch (SdkClientException e) {
    throw new ApiException(ErrorCode.STORAGE_FAILED, "S3 연결 실패: " + ...);
}
```

기동 시 `headBucket()`이 연결에 실패하면 예외를 던져 **컨텍스트 생성이 중단**됩니다.
그러면 S3와 아무 관계 없는 조회 API(앨범 목록·타임라인·지도)까지 함께 죽습니다.

이건 [CS 04](04-query-performance.md)와 [CS 06](06-monitoring.md) 측정 중에도 계속 걸림돌이었고,
README에 "알려진 한계"로 적어둔 항목이었습니다.

**스토리지 장애의 영향 범위는 "파일을 다루는 요청"으로 좁혀져야 합니다.**
기동은 계속하고 경고를 크게 남기며, 실제 업로드·다운로드 시점에 `STORAGE_FAILED`로 실패시킵니다.

```java
log.warn("[S3] 버킷 확인 실패 — 스토리지 없이 기동합니다. "
        + "파일 업로드·다운로드는 실패합니다. bucket={} ...", bucket, e.getMessage());
```

잘못된 설정을 조용히 넘기지 않도록 경고는 남깁니다.
이 동작은 `S3PhotoStorageStartupTest`로 고정했습니다.

### 검증

| 항목 | 결과 |
|---|---|
| `actionlint` (워크플로 문법·액션 사용·shellcheck) | 두 파일 모두 통과 |
| 백엔드 테스트 | **94개** 통과 |
| compose mount 경로 | 설정 파일 전부 존재 (빌드 산출물 제외) |

### 첫 실행이 바로 두 가지를 더 잡았다

PR을 올리자 CI가 실제로 돌았고 **실패했습니다.** 그리고 그 실패가 둘 다 진짜 문제였습니다.

**③ `gradlew`에 실행 권한이 없었다**

```
./gradlew: Permission denied     (exit 126)
```

`backend/gradlew`가 git에 `100644`로 커밋돼 있었습니다. 실행 비트가 없습니다.

로컬에서는 `sh gradlew`로 우회하면 되고, `Dockerfile`은 `RUN chmod +x gradlew`가 있어
가려져 있었습니다. **아무 CI도 없었기 때문에 아무도 몰랐습니다.**

```bash
git update-index --chmod=+x backend/gradlew
```

**④ `flutter analyze`는 info만 있어도 실패한다**

`--no-fatal-warnings`를 붙였는데도 실패했습니다. 로그에는 `error`가 하나도 없고
전부 `info`(`avoid_print`, `constant_identifier_names` 등)였습니다.

`flutter analyze`는 지적이 하나라도 있으면 종료코드가 0이 아닙니다.
`--no-fatal-infos`까지 붙여야 error만 파이프라인을 막습니다.

> 이걸 그대로 두면 CI가 **상시 빨간불**이 됩니다. 그러면 아무도 CI를 보지 않게 되고,
> 관문은 이름만 남습니다. 우선 error만 막고 info/warning은 로그로 남겨 점진적으로 줄입니다.

**⑤ `/actuator/health`가 매번 Gmail SMTP에 접속하고 있었다**

권한과 analyze를 고치자 네 잡이 통과하고 **컨테이너 기동 확인**만 남았습니다.
앱은 떴는데(요청이 필터 체인까지 도달) `/actuator/health`가 503을 돌려주고 있었습니다.

원인은 `MailHealthIndicator`였습니다.

```
JavaMailSenderImpl.testConnection
  → MailHealthIndicator.doHealthCheck
```

**health를 부를 때마다 `smtp.gmail.com`에 실제로 접속을 시도합니다.** 두 가지가 문제입니다.

1. health check는 보통 **수 초 간격으로 계속** 호출됩니다. 그때마다 외부 SMTP에 접속하면
   느리고, 메일 제공자가 차단할 수도 있습니다.
2. **메일이 죽으면 전체 health가 DOWN**이 됩니다. 그러면 오케스트레이터가
   "조회 API는 멀쩡히 서비스 중인" 인스턴스를 죽은 것으로 보고 재시작합니다.

메일은 이메일 인증·비밀번호 재설정에 쓰는 **보조 기능**입니다.
그 가용성이 서비스 전체의 생존 여부를 결정해서는 안 됩니다.
메일 장애는 해당 기능의 실패로 드러나야 합니다.

```yaml
management.health.mail.enabled: false
```

> S3 기동 결합도(②)와 **같은 종류의 문제**입니다.
> 부수적인 외부 의존성이 서비스 전체를 끌고 내려가는 구조.
> 이번에는 health check라는 다른 얼굴로 나타났을 뿐입니다.
>
> 이 상태는 사실 이번 세션 내내 있었습니다. 측정 중에 `{"status":"DOWN"}`을
> 여러 번 봤지만 API가 동작했기 때문에 그냥 지나쳤습니다.
> **CI가 health를 판정 기준으로 삼자 비로소 무시할 수 없게 됐습니다.**

### 3차 실행에서 전부 통과

```
✓ Backend 테스트        1m29s
✓ Backend 빌드          1m29s
✓ Docker 이미지 빌드    2m44s   (이미지 빌드 + 컨테이너 기동 확인)
✓ compose 설정 검증        7s
✓ Frontend 분석·테스트  1m40s
```

**"워크플로를 실제로 실행해보지 못했다"는 한계가 이 PR에서 해소됐습니다.**
그리고 실행해보지 않았다면 ③④⑤는 계속 몰랐을 것입니다.

세 번의 실행이 각각 다른 층을 검사했다는 점이 남길 만합니다.

| 실행 | 통과한 관문 | 걸린 것 |
|---|---|---|
| 1차 | — | 빌드 도구 실행 권한, 정적 분석 종료코드 |
| 2차 | 테스트·빌드·설정·프론트 | **런타임** — 컨테이너가 뜨지만 health가 DOWN |
| 3차 | 전부 | — |

정적 검사(`actionlint`)로는 1차의 문제도 잡히지 않았고,
테스트로는 2차의 문제가 잡히지 않았습니다.
**실제로 이미지를 만들어 띄워봐야 나오는 층이 따로 있습니다.**

---

## Limit / Next Condition

- **브랜치 보호 규칙은 저장소 설정이라 코드로 넣을 수 없습니다.**
  `main`에 대해 `backend-test` 통과를 필수로 지정해야 관문이 실제로 강제됩니다.
- **배포 스텝이 없습니다.** 대상 플랫폼이 정해지면 `deploy.yml`의 `push-image` 뒤에 붙입니다.
- **Flutter analyze는 `--no-fatal-warnings`입니다.** 경고가 많을 수 있어 우선 오류만 막습니다.
  경고까지 막으려면 플래그를 빼면 됩니다.
- **통합 테스트가 H2 기준입니다.** CI에 PostgreSQL 서비스 컨테이너를 붙이면
  [CS 03](03-security-boundaries.md)에서 남긴 "H2 통과를 PostgreSQL 증거로 쓰지 않는다"는
  한계를 좁힐 수 있습니다.

---

## Evidence

| 항목 | 위치 |
|---|---|
| CI 워크플로 | `.github/workflows/ci.yml` |
| 배포 관문 | `.github/workflows/deploy.yml` |
| S3 기동 결합도 테스트 | `S3PhotoStorageStartupTest` |
| 이름을 바로잡은 nginx 설정 | `infra/nginx/nemo.prod.conf` |

```bash
# 워크플로 문법 검증 (로컬)
actionlint .github/workflows/ci.yml .github/workflows/deploy.yml

# CI가 하는 것과 같은 검사
cd backend && ./gradlew test
docker compose --profile prod config
```
