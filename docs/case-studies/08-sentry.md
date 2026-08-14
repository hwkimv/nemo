---
title: Sentry를 붙였는데 이벤트가 0건이었다
status: Verified
date: 2026-08-14
---

# Case Study — Sentry를 붙였는데 이벤트가 0건이었다

> **한 줄 요약** — 연동만 하고 끝내지 않기 위해 실제 예외를 수집해 원인을 고치는 데까지 갔습니다. 그 과정에서 두 가지가 드러났습니다. **전역 예외 핸들러 때문에 Sentry가 아무것도 못 받고 있었고**, 정상적인 사용자 상황(중복 친구 요청)이 **500으로 보고되고 있었습니다.**

| | |
|---|---|
| **기간** | 2026-08-14 |
| **범위** | Backend — Sentry SDK, 스크러빙, 친구 도메인 예외 |
| **결과** | 중복 친구 요청 **500 → 409**, Sentry 이벤트 **1건 → 0건** |
| **증거** | 실제 수집된 이벤트 payload, `SentryScrubberTest` (7) |

---

## Problem

> 단순 연동은 경험으로 쓰지 않는다.
> `실제 예외 발생 → 수집 → Stack Trace 확인 → 원인 코드 특정 → 수정 → 재검증`까지 연결한다.

이 기준을 만족하려면 **진짜 예외 하나를 끝까지 따라가야** 했습니다.

동시에 걸리는 문제가 있었습니다. [CS 03](03-security-boundaries.md)에서 서버 로그의 토큰을 지웠는데,
**Sentry SDK는 기본적으로 요청 헤더를 이벤트에 붙입니다.** 거기에 `Authorization`이 그대로 들어갑니다.
로그에서 지운 값이 외부 서비스로 나가면 지운 의미가 없습니다. 오히려 더 넓게 퍼집니다.

---

## Analyze / Constraints

**제약 1 — Sentry 계정이 없습니다.**

DSN 없이는 "수집됐다"를 확인할 수 없습니다. 그렇다고 계정을 만들어 실제 서비스로
테스트 이벤트를 보내면, **무엇이 전송되는지 확인하기 전에 이미 보낸 뒤**가 됩니다.
토큰이 섞여 있었다면 늦습니다.

→ Sentry DSN은 결국 HTTP endpoint입니다. **그 자리에 스텁을 두면 SDK가 보내는
envelope를 그대로 받아볼 수 있습니다.** ([CS 05](05-map-api-cache.md)의 Naver 스텁과 같은 방식)

**제약 2 — 어떤 예외를 고를 것인가.**

일부러 예외를 만들어 넣는 것은 의미가 없습니다. 이미 있는 것을 찾아야 했습니다.

`GlobalExceptionHandler`를 읽다가 발견했습니다.

| 예외 | 처리 |
|---|---|
| `IllegalArgumentException` | 400 / 409 (전용 핸들러 있음) |
| `IllegalStateException` | **최종 fallback → 500** |

그리고 `FriendService`에는 `IllegalStateException`이 3곳 있었습니다.

```java
throw new IllegalStateException("이미 친구 요청을 보냈거나 친구 상태입니다.");   // line 51
throw new IllegalStateException("해당 친구 요청을 수락할 권한이 없습니다.");     // line 215
throw new IllegalStateException("해당 친구 요청을 거절할 권한이 없습니다.");     // line 258
```

**전부 정상적인 사용자 상황입니다.** 중복 요청, 남의 요청을 수락하려는 시도.
게다가 API 문서(`@ApiResponses`)에는 **409로 적혀 있었습니다.**

> 문서와 실제 동작이 다릅니다. 그리고 Sentry를 붙이면 **정상적인 사용자 행동이
> 서버 오류로 계속 쌓입니다.** 알림 피로의 전형적인 원인입니다.

---

## Options

**스크러빙을 차단 목록으로 할 것인가, 허용 목록으로 할 것인가.**

| 방식 | 장점 | 위험 |
|---|---|---|
| 차단 목록 (`Authorization`, `Cookie` … 를 지움) | 헤더가 많이 남아 진단에 유리 | **새 헤더가 생기면 아무도 손대지 않아도 샌다** |
| **허용 목록 (남길 것만 남김)** | 새 헤더는 자동으로 차단됨 | 진단에 필요한 헤더를 빠뜨릴 수 있음 |

**허용 목록을 골랐습니다.** 놓쳤을 때의 결과가 다르기 때문입니다.
허용 목록에서 빠뜨리면 "정보가 부족해 불편"하지만,
차단 목록에서 빠뜨리면 "비밀값이 외부로 나갑니다."

---

## Action

### 1. 스크러빙 (`SentryScrubber`)

```java
private static final Set<String> ALLOWED_HEADERS = Set.of(
        "accept", "accept-encoding", "accept-language",
        "content-type", "content-length", "user-agent", "referer",
        "x-request-id");   // 서버 로그와 이벤트를 잇는 값
```

| 대상 | 처리 |
|---|---|
| 헤더 | 허용 목록에 없으면 제거 (`Authorization`, `Cookie`, `X-Api-Key` … 자동으로 걸림) |
| 쿠키 | 통째로 제거 — `refreshToken`이 들어 있다 |
| 요청 본문 | 통째로 제거 — 비밀번호가 들어올 수 있다 |
| 서버 환경변수 | 통째로 제거 — DB 비밀번호 등 |
| 쿼리스트링 | **민감한 이름의 값만** 가림. 어떤 파라미터로 요청했는지는 원인 파악에 필요하다 |
| 사용자 | 내부 id만 남기고 이메일·IP 제거 |

### 2. 수집 서버 스텁

`tools/observability/sentry-stub/stub.py` — Sentry envelope를 받아 파싱해 보관합니다.
`GET /__last`로 **실제 전송된 payload를 그대로** 볼 수 있습니다.

```
DSN: http://stub@localhost:9998/1
```

---

## Result

### 발견 ① — Sentry가 아무것도 받지 못하고 있었다

DSN을 스텁으로 맞추고 중복 친구 요청을 보냈습니다.

```
HTTP 500                     ← 예외는 확실히 발생
Sentry 이벤트: 0건            ← 그런데 아무것도 안 왔다
```

원인은 `GlobalExceptionHandler`였습니다.

**Sentry의 servlet 통합은 "처리되지 않은 예외"를 잡습니다.**
그런데 이 앱은 전역 핸들러가 모든 예외를 잡아 응답으로 바꿉니다.
Sentry가 볼 수 있는 예외가 **존재하지 않습니다.**

`sentry-spring-boot-starter-jakarta`에는 Logback 연동이 들어 있지 않았습니다.
`sentry.logging.minimum-event-level: error` 설정도 그래서 아무 효과가 없었습니다.

```gradle
implementation 'io.sentry:sentry-logback:7.14.0'
```

이걸 추가하자 핸들러가 남기는 `log.error(..., ex)`가 이벤트가 됐습니다.

> 덤으로 좋은 성질이 생깁니다. 기존 핸들러는 이미
> **예상 가능한 오류는 `log.warn`, 진짜 문제는 `log.error`** 로 구분해 쓰고 있었습니다.
> Logback 연동은 그 구분을 그대로 물려받습니다.

**"Sentry를 붙였다"와 "Sentry가 받고 있다"는 다릅니다.** 확인하지 않았으면 몰랐을 것입니다.

### 실제로 수집된 이벤트

```
level      : error
environment: local-verify
logger     : com.nemo.backend.global.exception.GlobalExceptionHandler
예외       : IllegalStateException: 이미 친구 요청을 보냈거나 친구 상태입니다.

NEMO 코드 프레임 (최근 순):
  com.nemo.backend.domain.friend.service.FriendService.sendFriendRequest      line 51
  com.nemo.backend.domain.friend.controller.FriendController.addFriend        line 87
  com.nemo.backend.domain.auth.jwt.JwtAuthenticationFilter.doFilterInternal   line 91
```

**스택 트레이스가 원인 코드를 정확히 지목했습니다** — `FriendService line 51`.

### 스크러빙 검증 — 실제 전송된 payload

요청에 일부러 토큰을 넣어 보냈습니다.

```
Authorization: Bearer <token>
POST /api/friends?debugParam=abc&token=SUPERSECRET123
```

수집된 이벤트:

```
query   : debugParam=abc&token=[redacted]
headers : {"content-length":"18","content-type":"application/json",
           "user-agent":"curl/8.5.0","accept":"*/*"}
cookies : None
data    : None
user    : {}
```

| 항목 | 결과 |
|---|---|
| `Authorization` 헤더 | **없음** |
| `token=SUPERSECRET123` | **`token=[redacted]`** |
| 쿠키·본문·환경변수 | **없음** |
| `debugParam=abc` | 남음 (진단에 필요) |

### 발견 ② — 정상적인 사용자 상황이 500이었다

스택 트레이스가 가리킨 곳을 고쳤습니다. 예상 가능한 상태·권한 오류에 도메인 코드를 부여합니다.

```java
FRIEND_REQUEST_ALREADY_EXISTS(HttpStatus.CONFLICT, ...)   // 409
FRIEND_REQUEST_FORBIDDEN(HttpStatus.FORBIDDEN, ...)       // 403
```

**모든 `IllegalStateException`을 바꾸지는 않았습니다.** 그렇게 하면 진짜 프로그래밍 버그를
4xx로 숨기게 됩니다. 예상 가능한 세 곳만 도메인 오류로 올렸습니다.

### 재검증

| | 수정 전 | 수정 후 |
|---|---|---|
| 중복 친구 요청 | **HTTP 500** | **HTTP 409** (API 문서와 일치) |
| Sentry 이벤트 | **1건** | **0건** |
| 응답 코드 | `INTERNAL_ERROR` | `FRIEND_REQUEST_ALREADY_EXISTS` |

**그리고 진짜 장애는 여전히 잡힙니다.** 외부 API 자격증명이 잘못된 상태로 지도를 조회하자
Sentry 이벤트가 수집됐고, 헤더에는 토큰이 없었습니다.
Sentry를 침묵시킨 것이 아니라 **소음만 걷어낸 것**입니다.

---

## Limit / Next Condition

- **실제 Sentry 서비스에 연결해보지 못했습니다.** 계정이 없어 스텁으로 검증했습니다.
  전송되는 payload는 동일하지만, Sentry UI의 그룹핑·알림 규칙은 확인하지 못했습니다.
  DSN을 넣으면 그대로 동작해야 합니다.
- **알림 규칙이 없습니다.** 어떤 이벤트에 누구에게 알릴지는 정하지 않았습니다.
- **`release` 값이 비어 있습니다.** CI에서 커밋 SHA를 주입하면 이벤트와 배포를 이을 수 있습니다.
- **남은 `IllegalStateException`이 다른 도메인에도 있을 수 있습니다.**
  친구 도메인만 정리했습니다. 같은 기준으로 전수 점검이 필요합니다.
- **`traces-sample-rate: 0`입니다.** 성능 추적은 Prometheus/Grafana([CS 06](06-monitoring.md))가
  이미 하고 있어 중복이라 껐습니다.

---

## Evidence

| 항목 | 위치 |
|---|---|
| 스크러빙 | `backend/.../global/observability/SentryScrubber.java` |
| 스크러빙 테스트 (7개) | `SentryScrubberTest` |
| 수집 서버 스텁 | `tools/observability/sentry-stub/stub.py` |
| 도메인 오류 코드 | `ErrorCode.FRIEND_REQUEST_ALREADY_EXISTS`, `FRIEND_REQUEST_FORBIDDEN` |

```bash
# 스텁 실행 후 DSN을 가리키고 앱 기동
python3 tools/observability/sentry-stub/stub.py --port 9998 &
SENTRY_DSN="http://stub@localhost:9998/1" ./gradlew bootRun

# 중복 친구 요청 → 409, Sentry 이벤트 없음
curl -s http://localhost:9998/__last | python3 -m json.tool
```
