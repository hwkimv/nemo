# Grafana 대시보드 화면

**촬영일:** 2026-08-15
**대시보드:** `infra/monitoring/grafana/dashboards/nemo-backend.json`

Grafana Image Renderer로 자동 생성했습니다. 손으로 캡처한 것이 아니라
**대시보드 정의에서 그대로 렌더링**한 것이라, JSON을 고치면 같은 명령으로 다시 뽑을 수 있습니다.

---

## 전체 대시보드

![NEMO Backend 대시보드](grafana-dashboard.png)

부하를 준 구간(약 17:51~)과 잠깐 멈춘 구간(17:56)이 **모든 패널에 동시에** 나타납니다.
요청 수가 뛰고 → p95가 따라 오르고 → GC가 함께 튀는 것이 한 화면에서 보입니다.
이게 지표를 따로따로 `curl`로 읽는 것과 다른 점입니다.

읽을 거리:

- **초당 요청 수** — 엔드포인트 9개가 구분됩니다. 0 → 2.5 req/s
- **응답시간 p95** — `NaN` 없이 실제 값만. 트래픽 없는 엔드포인트는 자동으로 빠집니다
- **5xx 오류율** — `0%` 초록. **「No data」가 아닙니다** (아래 참고)
- **상태 코드별** — 200 / 201 / 401 / 404가 분리됩니다
- **GC 일시정지** — 부하 구간에만 톱니가 생깁니다

---

## 응답시간 p95 (엔드포인트별)

![p95](grafana-p95-by-endpoint.png)

느린 API를 찾는 핵심 패널입니다. 평균은 소수의 느린 요청을 감춥니다.

처음에는 트래픽이 없는 엔드포인트까지 전부 `NaN`으로 계산돼 범례를 채웠고,
**정작 느린 API가 그 안에 묻혔습니다.** 요청이 실제로 있는 것만 남기도록 고쳤습니다.

```promql
histogram_quantile(0.95, sum by (le, uri) (rate(http_server_requests_seconds_bucket[5m])))
  and on (uri) (sum by (uri) (rate(http_server_requests_seconds_count[5m])) > 0)
```

---

## JVM Heap 사용량

![JVM Heap](grafana-jvm-heap.png)

처음에는 `used`와 `max`를 같이 그렸습니다. `max`가 4 GiB인데 `used`는 150 MB라
**used가 바닥에 붙은 평평한 선**이 되어 메모리가 새는지 판단할 수 없었습니다.

`max` 대신 **`committed`**(JVM이 실제로 확보한 양)를 넣었습니다.
150MB vs 182MB로 같은 스케일이라 **GC 톱니와 확보량 증가가 함께 보입니다.**
한계 대비 여유는 옆의 `Heap 사용률` 패널(`used / max`)이 담당합니다.

---

## 다시 뽑는 방법

```bash
# 모니터링 스택
docker compose --profile monitoring up -d

# 이미지 렌더러 (스크린샷을 다시 만들 때만 필요)
docker run -d --name renderer --network <net> grafana/grafana-image-renderer
# Grafana에 GF_RENDERING_SERVER_URL=http://renderer:8081/render 지정

curl -u admin:admin -o grafana-dashboard.png \
  "http://localhost:3000/render/d/nemo-backend/nemo-backend?orgId=1&from=now-15m&to=now&width=1600&height=1000&kiosk=1"
```

패널 하나만 뽑을 때는 `/render/d-solo/...&panelId=<id>`를 씁니다.

---

## 이 화면의 한계

- **부하가 낮은 상태입니다.** 로컬에서 초당 2~3 요청 수준이고, 실제 트래픽 패턴이 아닙니다.
- 그래서 **임계값(p95 0.5s/2s, heap 70%/90%)이 적절한지는 아직 모릅니다.**
- 알림 규칙은 설정하지 않았습니다.

관련: [CS 06 — 지표를 붙이고 나서 알게 된 것](../../case-studies/06-monitoring.md)
