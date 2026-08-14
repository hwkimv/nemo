---
title: 지표를 붙이고 나서 알게 된 것
status: Verified
date: 2026-08-14
---

# Case Study — 지표를 붙이고 나서 알게 된 것

> **한 줄 요약** — Actuator → Prometheus → Grafana를 연결했습니다. 대시보드를 만드는 것이 목적이 아니라 **지표가 문제를 찾아주는지** 확인하는 것이 목적이었고, 실제로 하나 찾았습니다. 지도 API의 레이트 리미터가 **동시 요청에는 전혀 동작하지 않고 있었습니다.**

| | |
|---|---|
| **기간** | 2026-08-14 |
| **범위** | Backend — Actuator/Micrometer, Prometheus, Grafana |
| **발견** | 외부 API 호출률이 동시 요청 수에 정비례 (의도 5회/초 → 실제 40회/초) |
| **증거** | 측정표 아래 · `promtool` 검증 · 대시보드 JSON |

---

## Problem

성능을 두 번 개선했지만(N+1, 캐시), **문제를 찾는 방식이 매번 수동**이었습니다.

- 코드를 읽다가 이상한 곳을 발견
- 벤치마크를 돌려서 확인
- 고치고 다시 벤치마크

이 방식은 **내가 의심한 곳만** 봅니다. 의심하지 않은 곳은 영원히 안 보입니다.

필요한 것은 "무엇이 느린지 물어보면 답해주는 것"이었습니다.

---

## Analyze / Constraints

**제약 1 — 지표를 공개하면 안 됩니다.**

`/actuator/prometheus`는 사용 중인 API 경로, 응답시간, 오류율, JVM 상태를 그대로 담습니다.
공개 인터넷에 열면 서비스 내부 구조를 알려주는 것과 같습니다.
[CS 03](03-security-boundaries.md)에서 토큰 로그를 지운 것과 같은 기준이 필요했습니다.

**제약 2 — p95를 구하려면 히스토그램이 필요합니다.**

Micrometer는 기본적으로 count와 sum만 내보냅니다. 그러면 평균은 구해도 **p95는 구할 수 없습니다.**
평균은 소수의 느린 요청을 감추기 때문에, 느린 API를 찾는 데는 p95가 필요합니다.

**제약 3 — 경로 변수가 그대로 라벨이 되면 안 됩니다.**

`/api/albums/1`, `/api/albums/2`, … 가 각각 다른 시계열이 되면 Prometheus 메모리가 터집니다.
(cardinality explosion) 이건 붙이기 전에 확인해야 하는 항목입니다.

---

## Options

**지표를 어떻게 안전하게 노출할 것인가**가 핵심 선택이었습니다.

| 방법 | 장점 | 단점 | 선택 |
|---|---|---|---|
| 서비스 포트에 열고 Nginx에서 차단 | 설정이 적음 | Nginx가 없는 배포(Railway 등)에서는 **그대로 노출** | ❌ |
| Spring Security로 인증 요구 | 앱 안에서 해결 | Prometheus가 인증을 들고 다녀야 함 | ❌ |
| **관리 포트 분리 + 루프백 바인딩** | 프록시 유무와 무관하게 안전 | 헬스체크 경로가 함께 이동함 | ✅ |

세 번째를 골랐습니다. **앞단 구성에 의존하지 않는 것**이 중요했습니다.

대신 감수한 것: `management.server.port`를 설정하면 `/actuator/health`도 함께 관리 포트로 옮겨갑니다.
플랫폼 헬스체크는 서비스 포트의 `/` 또는 `/health/db`를 쓰면 됩니다. (둘 다 이미 있습니다)

---

## Action

### 1. 지표 노출

```gradle
runtimeOnly 'io.micrometer:micrometer-registry-prometheus'
```

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus   # env·beans·heapdump는 닫아둔다
  metrics:
    distribution:
      percentiles-histogram:
        http.server.requests: true        # 이게 없으면 p95를 못 구한다
      slo:
        http.server.requests: 50ms, 100ms, 200ms, 500ms, 1s, 2s, 5s
```

운영에서는 관리 포트를 분리합니다.

```yaml
# application-prod.yml
management:
  server:
    port: ${MANAGEMENT_PORT:9090}
    address: ${MANAGEMENT_ADDRESS:127.0.0.1}   # 루프백에만 바인딩
```

이 계약은 테스트로 고정했습니다 (`PostgresProductionProfileTest`).

### 2. Prometheus + Grafana

```bash
docker compose --profile monitoring up -d
```

- `infra/monitoring/prometheus.yml` — 15초마다 `/actuator/prometheus` 수집
- `infra/monitoring/grafana/provisioning/` — 데이터소스·대시보드 **자동 등록**
- `infra/monitoring/grafana/dashboards/nemo-backend.json` — 대시보드를 파일로 관리

UI에서 손으로 만든 대시보드는 컨테이너를 지우면 사라집니다. **저장소에 JSON으로 둡니다.**

### 3. 대시보드 패널

| 패널 | 쿼리 의도 |
|---|---|
| 초당 요청 수 (엔드포인트별) | 실제로 쓰이는 API 파악 |
| **응답시간 p95 (엔드포인트별)** | 느린 API 특정 — 핵심 패널 |
| 5xx 오류율 | 0이 아니면 즉시 확인 |
| 상태 코드별 요청 | 4xx 급증 = 클라이언트 계약 파손 신호 |
| JVM Heap | 계속 차오르면 누수·캐시 무한 증가 의심 |
| DB Connection Pool | `pending > 0` = 커넥션 대기 발생 |
| GC 일시정지 | 응답시간 튐의 원인 확인 |

---

## Result

### 붙이기 전 확인한 것

**경로 변수는 템플릿화됩니다.** 카디널리티 폭발 없음.

```
/api/albums/{albumId}   status=200
/api/albums/{albumId}   status=404
```

**설정과 쿼리는 문법 검증을 통과합니다.**

```
promtool check config infra/monitoring/prometheus.yml   → SUCCESS
대시보드 PromQL 10개                                    → 전부 유효
```

### 지표가 즉시 보여준 것

혼합 부하를 준 뒤 엔드포인트별 평균:

| URI | 요청 수 | 평균 |
|---|---:|---:|
| **`/api/map/photobooths/viewport`** | 1 | **7,285ms** |
| `/api/albums` | 13 | 20.1ms |
| `/actuator/prometheus` | 7 | 18.6ms |
| `/api/photos` | 10 | 8.1ms |
| `/api/timeline` | 10 | 7.1ms |

지도 API가 **다음으로 느린 것의 360배**입니다. 나란히 놓으니 한눈에 보입니다.

> 이 느림 자체는 [CS 05](05-map-api-cache.md)에서 이미 알고 있었습니다.
> 지표가 새로 준 것은 **다른 API와의 비교**입니다. 7초가 "원래 그런 것"이 아니라
> 이 서비스에서 완전히 예외적이라는 사실이 수치로 드러납니다.

### 그리고 몰랐던 것을 찾았습니다 ⚠️

"왜 7초인가"를 따라가니 코드에 하드코딩된 레이트 리미터가 나왔습니다.
41회 × 200ms = 8,200ms. **그럼 동시 요청이 오면 어떻게 되는가?**

동시 요청 수를 늘리며 외부 API 실제 호출률을 쟀습니다.

| 동시 요청 | 벽시계 | 외부 호출 | **실제 호출률** | 의도한 상한 |
|---:|---:|---:|---:|---:|
| 1건 | 8.1s | 41회 | **5.1회/초** | 5회/초 ✅ |
| 4건 | 8.2s | 164회 | **20.0회/초** | 5회/초 ❌ |
| 8건 | 8.2s | 328회 | **40.2회/초** | 5회/초 ❌ |

**호출률이 동시 요청 수에 정비례합니다. 레이트 리미터가 전혀 제한하지 않습니다.**

원인은 코드 세 줄에 있습니다.

```java
long last = lastCallAt.get();                  // ① 읽고
if (elapsed < MIN_INTERVAL_MS) sleepSilently(...);  // ② 자고
lastCallAt.set(System.currentTimeMillis());    // ③ 쓴다
```

`AtomicLong`을 쓰지만 **읽기와 쓰기가 따로**입니다.
동시 스레드가 ①에서 **같은 값**을 읽고, ②에서 **같이 자고**, 동시에 깨어나 한꺼번에 나갑니다.
한 스레드 안에서 연속 호출은 막지만, 스레드가 늘면 그 수만큼 배로 나갑니다.

**왜 심각한가** — 지도를 동시에 보는 사용자가 8명이면 네이버 API에 초당 40회를 던집니다.
쿼터를 넘겨 429가 돌아오면 지도 기능 전체가 멈춥니다. 그리고 이건
**부하가 걸릴 때만 나타나므로 개발 중에는 절대 보이지 않습니다.**

---

## Limit / Next Condition

**고치지 않았습니다.** 이 PR은 관측 수단을 붙이고 문제를 찾는 것까지입니다.
발견한 문제를 같은 PR에서 고치면 "지표가 문제를 찾아줬다"는 인과가 흐려집니다.

수정 방향은 정해뒀습니다.

| 순위 | 할 일 | 이유 |
|---|---|---|
| 1 | 호출 수 자체를 줄이기 (41회 → ?) | 근본 원인. [CS 05](05-map-api-cache.md) 참고 |
| 2 | 레이트 리미터를 세마포어/토큰 버킷으로 교체 | 동시성에서 실제로 동작하게 |
| 3 | 외부 API 호출을 Micrometer `@Timed`로 계측 | 지금은 내부 호출이 지표에 안 잡힘 |

**아직 확인하지 않은 것**

- **Grafana 화면을 실제로 띄워보지 못했습니다.** 작업 환경의 Docker Desktop이 반복해서 죽어
  `promtool`로 설정·쿼리 문법만 검증했습니다. 컨테이너 기동 검증은 남아 있습니다.
- **알림(Alert)은 설정하지 않았습니다.** p95나 5xx가 임계치를 넘을 때 알려주는 규칙이 필요합니다.
- **외부 API 호출은 지표에 안 잡힙니다.** `http_server_requests`는 들어오는 요청만 봅니다.
  지도 API가 7초인 것은 보이지만 그중 8.2초가 외부 호출 대기라는 것은 지표만으로는 모릅니다.

---

## Evidence

| 항목 | 위치 |
|---|---|
| 지표 설정 | `backend/src/main/resources/application.yml`, `application-prod.yml` |
| 운영 노출 계약 테스트 | `PostgresProductionProfileTest.productionKeepsMetricsOffThePublicPort` |
| Prometheus 설정 | `infra/monitoring/prometheus.yml` |
| Grafana 프로비저닝·대시보드 | `infra/monitoring/grafana/` |
| compose 서비스 | `compose.yaml` (`--profile monitoring`) |

```bash
# 앱 실행 후
curl -s localhost:8080/actuator/prometheus | head

# 모니터링 스택
docker compose --profile monitoring up -d
# Grafana http://localhost:3000 (admin/admin) → NEMO 폴더 → NEMO Backend

# 설정 검증 (Docker 없이)
promtool check config infra/monitoring/prometheus.yml
```
