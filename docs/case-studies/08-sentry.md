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
| **증거** | 실제 Sentry UI에서 확인한 이벤트, 스크러빙 테스트 13개 |

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

**제약 1 — 무엇이 나가는지 먼저 알아야 합니다.**

실제 서비스로 테스트 이벤트를 먼저 보내면 **무엇이 전송되는지 확인하기 전에 이미 보낸 뒤**가 됩니다.
토큰이 섞여 있었다면 늦습니다. 그래서 순서를 정했습니다.
**스텁으로 payload를 확인하고, 안전한 것을 본 뒤에 실제 DSN을 연결합니다.**

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

### 실제 Sentry 연결 확인

스텁으로 payload를 검증한 뒤, 실제 DSN을 넣고 같은 경로를 다시 돌렸습니다.

```
INFO : Initializing SDK with DSN: 'https://...@o4511909740937216.ingest.us.sentry.io/...'
DEBUG: Capturing event: 98598ec7b8f04b02937565369998a699
DEBUG: Envelope sent successfully.
DEBUG: Envelope flushed
```

외부 API가 죽은 상태로 지도를 조회해 **진짜 500을 만들었고**, 그 이벤트가 실제 Sentry로
전송됐습니다. Sentry UI에서 저장된 이벤트를 열어 확인한 결과입니다.

**전달 경로** — `mechanism: LogbackSentryAppender`

이벤트가 servlet 통합이 아니라 **Logback appender를 통해** 들어왔습니다.
`sentry-logback`을 추가한 이유(전역 핸들러가 예외를 삼켜 Sentry가 0건이던 문제)가
UI 태그로 그대로 확인됩니다.

**스택 트레이스** — 체인 3단으로 원인이 끝까지 보입니다.

```
RuntimeException: Naver Local API 호출 실패
  → ResourceAccessException
    → ConnectException: Connection refused

NaverApiClient:170        searchLocal                ← 실제 실패 지점
PhotoboothService:391     getPhotoboothsInViewport
PhotoboothController:71   viewport
JwtAuthenticationFilter:91
```

**HTTP Request** — 스크러빙 결과

| 항목 | UI에 표시된 값 |
|---|---|
| Headers | `Accept`, `User-Agent` **둘뿐** |
| `Authorization` | **없음** |
| Query `token` | `[Filtered]` (보낸 값은 `DUMMY_NOT_REAL_123`) |
| Cookies / Body | 없음 |
| Users (이슈 집계) | **0명** |

**헤더가 두 개만 남은 것이 허용 목록이 동작했다는 증거입니다.**
Sentry 자체 스크러빙이었다면 `Authorization`이 `[Filtered]`로 *표시는* 됐을 것입니다.
아예 없다는 것은 **전송 전에 지워졌다**는 뜻입니다.

### 뜻밖의 수확 — MDC의 requestId가 이벤트에 실렸다

Contexts에 이 값이 들어 있었습니다.

```
MDC   requestId   a5b9ed15
```

의도하고 넣은 것이 아니라 Sentry SDK가 MDC를 자동으로 수집한 것입니다.
[CS 03](03-security-boundaries.md)에서 토큰 로그를 지우면서 넣었던 Request ID인데,
결과적으로 이렇게 이어집니다.

```
Sentry 이벤트 → requestId → 서버 로그 검색 → 그 요청의 전체 흐름
```

**오류 추적과 로그 추적이 한 값으로 연결됐습니다.**
토큰을 지운 자리를 메우려고 넣은 장치가 다른 도구에서 다시 쓰였습니다.

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

### 발견 ③ — breadcrumb은 스크러버를 거치지 않는다

UI에서 이벤트를 확인하다가 Breadcrumbs에 우리 로그가 그대로 실려 있는 것을 봤습니다.

```
[NAVER][EX][LOCAL] attempt 3/3 → 2000ms 대기 후 재시도.
uri=http://localhost:9/dead?query=...&display=5&start=1&sort=random
```

`SentryScrubber`는 `event.request`와 `event.user`만 손봅니다. **breadcrumb은 건드리지 않습니다.**
그런데 설정이 이렇습니다.

```yaml
sentry.logging.minimum-breadcrumb-level: info
```

**INFO 이상 로그가 전부 breadcrumb으로 Sentry에 실려 갑니다.**
누군가 `log.info("... token={}", token)` 한 줄을 추가하면, request 스크러빙을 아무리
촘촘히 해도 그 값은 그대로 나갑니다.

> 당시 실제로 새는 값은 없었습니다. 네이버 자격증명은 헤더로 나가고 URI엔 없으며,
> [CS 03](03-security-boundaries.md)에서 `Authorization` 로그를 이미 지웠기 때문입니다.
> **하지만 구조적으로 뚫려 있었습니다.**

이 구조는 헤더를 허용 목록으로 처리한 이유와 같습니다.
**새로 추가되는 것이 기본적으로 안전한 쪽에 서야 합니다.**
로그는 계속 추가되고, 추가하는 사람이 Sentry를 떠올릴 것이라고 기대할 수 없습니다.

`SentryBreadcrumbScrubber`를 추가했습니다. 로그 메시지는 구조가 없어 이름으로 판단할 수
없으므로 **모양으로** 찾습니다.

| 패턴 | 예 |
|---|---|
| 이름이 붙은 비밀값 | `token=...`, `password:...`, `client_secret=...` |
| 인증 스킴 | `Bearer ...`, `Basic ...` |
| JWT 모양 | `eyJ...` 세 토막 |
| data 맵 키 | 키 이름이 민감하면 값 전체 |

**실제 전송 payload로 확인**했습니다. 네이버 엔드포인트에 일부러 `token=LEAKED_VIA_BREADCRUMB_999`를
넣어 로그에 찍히게 만든 뒤 이벤트를 받아봤습니다.

```
uri=http://localhost:9/dead?token=[redacted]&query=%ED%8F%AC%ED%86%A0%EB%B6%80%EC%8A%A4&display=5
LEAKED 문자열 포함: False
```

진단에 필요한 `query=`, `display=`는 남고 비밀값만 가려졌습니다.

> **한계** — 모양이 특이하지 않은 비밀값(예: 짧은 인증코드)은 걸러내지 못합니다.
> 이건 **마지막 그물이지 첫 번째 방어선이 아닙니다.**
> 애초에 로그에 비밀값을 찍지 않는 것이 먼저입니다.

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

- **접속 IP로 역산한 위치가 저장됩니다.** UI의 User Context에 `Dobong-gu, South Korea (KR)`이
  붙어 있었습니다. 우리 스크러버는 `User.ipAddress`를 지우지만, **Sentry가 수집 시점의
  접속 IP로 지역을 역산**합니다. SDK 쪽에서는 막을 수 없습니다.
  → 필요하면 Sentry 프로젝트 설정의 **Security & Privacy → Prevent Storing of IP Addresses**를 켭니다.
- **머신 호스트명이 태그로 나갑니다.** `server_name: HWdesktop.localdomain`.
  운영에서는 컨테이너 ID라 위험이 작지만, 나가고 있다는 사실은 알고 있어야 합니다.
- **Event API로 이벤트를 조회하지는 못했습니다.** 온보딩에서 발급된 auth token이
  `project:releases` 범위라 403이었습니다. 확인은 UI에서 했습니다.
- **알림 규칙이 없습니다.** 어떤 이벤트에 누구에게 알릴지는 정하지 않았습니다.
- **breadcrumb 마스킹은 모양으로 찾습니다.** 이름표 없는 짧은 비밀값은 걸러내지 못합니다.
  로그에 비밀값을 찍지 않는 것이 먼저이고, 이건 마지막 그물입니다.
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
| 스크러빙 테스트 | `SentryScrubberTest` (7), `SentryBreadcrumbScrubberTest` (6) |
| breadcrumb 마스킹 | `SentryBreadcrumbScrubber`, `SensitiveTextRedactor` |
| 수집 서버 스텁 | `tools/observability/sentry-stub/stub.py` |
| 도메인 오류 코드 | `ErrorCode.FRIEND_REQUEST_ALREADY_EXISTS`, `FRIEND_REQUEST_FORBIDDEN` |

```bash
# 스텁 실행 후 DSN을 가리키고 앱 기동
python3 tools/observability/sentry-stub/stub.py --port 9998 &
SENTRY_DSN="http://stub@localhost:9998/1" ./gradlew bootRun

# 중복 친구 요청 → 409, Sentry 이벤트 없음
curl -s http://localhost:9998/__last | python3 -m json.tool
```
