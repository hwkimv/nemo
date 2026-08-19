# 2026-08-15 지도 외부 호출 실측 (실제 NAVER API)

> 상태: `Verified`
> 관련 문서: [CS 05 — 지도 API 캐시가 가리고 있던 것](../case-studies/05-map-api-cache.md)

스텁으로는 "페이지 2~4가 실제로 결과를 더 주는가"를 답할 수 없어
**실제 NAVER API 키를 붙여** 측정했습니다.

## 측정 환경

| 항목 | 값 |
|---|---|
| 일시 | 2026-08-15 |
| 대상 | `GET /api/map/photobooths/viewport` |
| 뷰포트 | 강남역 일대 (`swLat=37.494, swLng=127.024, neLat=37.503, neLng=127.034`) |
| 캐시 | **OFF** (`naver.cache.ttl-seconds=0`) — 첫 조회 비용을 보기 위함 |
| 반복 | Before/After 각 3회, 평균 |
| Before/After | **같은 세션**에서 git worktree로 되돌린 코드와 현재 코드를 번갈아 실행 |
| 호출 수 집계 | 애플리케이션 로그의 외부 요청 라인 카운트 |

> 키는 환경변수로만 주입했습니다. 저장소에 쓰지 않았습니다.

## 관찰 ① — 구 경로는 인증을 통과하지 못한다

```
POST openapi.naver.com/v1/search/local.json  + X-Naver-Client-Id/Secret
  → 401  {"errorMessage":"NID AUTH Result Invalid ...","errorCode":"024"}

GET  naverapihub.apigw.ntruss.com/search/v1/local + X-NCP-APIGW-API-KEY-ID/KEY
  → 200  {"total":5,"start":1,"display":5,"items":[...]}
```

역지오코딩(`maps.apigw.ntruss.com/map-reversegeocode/v2/gc`)은 이미 NCP 방식이라 200이었습니다.
**지역 검색만 API HUB 이관을 따라가지 못한 상태**였습니다.

## 관찰 ② — `start` / `display` 가 무시된다

같은 검색어(`강남역 포토부스`)로 `start`만 바꿔 호출:

| 요청 `start` | 응답 `start` | 응답 건수 | 첫 페이지와 동일? |
|---:|---:|---:|---|
| 1 | 1 | 5 | — |
| 6 | 1 | 5 | **동일 (5/5 일치)** |
| 11 | 1 | 5 | **동일 (5/5 일치)** |
| 16 | 1 | 5 | **동일 (5/5 일치)** |

`display=5 / 10 / 30` 도 모두 5건을 반환했습니다. 한 번에 더 받을 방법이 없습니다.

키워드 9개 전체에 대해 4페이지씩 돌린 결과 (`tools/performance/naver-probe/probe.py`):

| 페이지 | 신규 장소(전체) | 신규 장소(뷰포트 내) |
|---:|---:|---:|
| 1 | 30곳 | 1곳 |
| 2 | 0곳 | 0곳 |
| 3 | 0곳 | 0곳 |
| 4 | 0곳 | 0곳 |

→ **페이지 2~4의 기여도 0.0%.**

## 관찰 ③ — Before / After

| 항목 | Before | After | 변화 |
|---|---:|---:|---|
| 외부 API 호출 (뷰포트 1회) | 25회 | 10회 | **-60.0%** |
| ├ Local Search | 24회 | 9회 | -62.5% |
| └ Reverse Geocode | 1회 | 1회 | 동일 |
| 수집한 raw 항목 | 107건 | 32건 | -70.1% |
| **뷰포트 내 최종 결과** | **1건** | **1건** | **동일** |
| 응답시간 평균 | 4,770ms | 1,857ms | **-2,913ms (-61.1%)** |

**최종 결과가 같다는 점이 핵심입니다.** 줄인 15회는 전부 같은 응답을 다시 받던 호출이었습니다.

### Before가 41이 아니라 25인 이유

`PhotoboothService`의 페이지 루프에는 조기 종료 조건이 있었습니다.

```java
if (items.size() < PAGE_SIZE) break;   // 5건 미만이면 다음 페이지 안 감
```

스텁은 항상 5건을 돌려줘 루프가 끝까지 돌았고(9 × 4 + 1 = 37~41),
실제 API는 결과가 5건 미만인 키워드가 있어 조기 종료했습니다.
**41은 최대치이고, 실측 최댓값은 25였습니다.**

## 재현

```bash
NAVER_LOCAL_CLIENT_ID=... NAVER_LOCAL_CLIENT_SECRET=... \
  python3 tools/performance/naver-probe/probe.py
```

## 아직 하지 않은 것

- 여러 지역·시간대 반복 측정. 이번 측정은 강남역 1개 뷰포트 3회입니다.
- 키워드 9개 축소. 이번엔 `포토부스` 하나만 뷰포트 내 결과를 줬지만,
  지역에 따라 다를 수 있어 반복 측정 전에는 자르지 않았습니다.
- 캐시 ON 상태의 실제 API 측정. 이 문서는 첫 조회(캐시 미스) 비용만 다룹니다.
