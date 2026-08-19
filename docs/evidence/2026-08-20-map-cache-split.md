# 2026-08-20 지도 캐시 분리와 TTL 정책 검증

> 상태: `Verified`
> 관련 문서: [CS 05 — 지도 API 캐시가 가리고 있던 것](../case-studies/05-map-api-cache.md)

이 문서는 **성능이 얼마나 빨라졌는가**를 다루지 않습니다.
확인한 것은 **정책이 의도대로 동작하는가** 세 가지입니다.

1. 두 종류의 데이터를 **독립된 정책**으로 관리할 수 있는가
2. 캐시가 **예상대로 hit/miss** 되는가
3. 한쪽 캐시를 꺼도 **다른 쪽이 살아 있는가**

---

## 1. Before — 하나의 캐시, 하나의 TTL

```java
// 예전 구조
private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

@Value("${naver.cache.ttl-seconds:120}")
private long cacheTtlSeconds;
```

| | Before |
|---|---|
| 구현 | `ConcurrentHashMap` + 저장 시각 수동 비교 |
| 담기는 것 | Local Search 응답 **과** Reverse Geocoding 응답 (같은 Map) |
| TTL | 둘 다 **120초** |
| 크기 상한 | **없음** |
| 만료 정리 | **읽을 때만.** 다시 읽히지 않는 항목은 계속 남음 |
| 통계 | 없음 (hit/miss를 셀 수 없음) |

> Caffeine은 `build.gradle`에 선언만 되어 있고 실제로는 쓰이지 않았습니다.

## 2. 문제 — 두 데이터의 변경 특성이 다른데 TTL이 같았다

| | Local Search | Reverse Geocoding |
|---|---|---|
| 담는 것 | 업체 검색 결과 (이름·주소·좌표·링크) | 좌표 → 행정구역 |
| 바뀌는 이유 | 폐업, 신규 오픈, 정보 수정 | 행정구역 개편 |
| 바뀌는 빈도 | 가끔 | 거의 없음 |
| 적절한 TTL | 짧게 | 길게 |

하나의 TTL로는 **한쪽에 맞추면 다른 쪽이 손해**입니다.
120초는 업체 정보 기준으로는 짧을 것도 없지만, 좌표→주소 변환에는 지나치게 짧습니다.
게다가 통계가 없어 **어느 쪽이 비용을 쓰는지 구분조차 되지 않았습니다.**

## 3. After — 용도별 캐시 2개

| | local-search | reverse-geocoding |
|---|---|---|
| 구현 | Caffeine | Caffeine |
| key | Local Search 요청 URI **(변경 없음)** | Reverse Geocode 요청 URI **(변경 없음)** |
| 만료 | `expireAfterWrite` **300초 (5분)** | `expireAfterWrite` **1800초 (30분)** |
| 크기 상한 | `maximumSize` 1000 entry | `maximumSize` 1000 entry |
| 통계 | `recordStats()` | `recordStats()` |
| 끄기 | `ttl-seconds: 0` → **이 캐시만** OFF | `ttl-seconds: 0` → **이 캐시만** OFF |

### `expireAfterAccess`를 쓰지 않은 이유

읽을 때마다 수명이 연장되면 **자주 보는 지역의 오래된 외부 응답이 계속 살아남습니다.**
버릴지 말지는 "얼마나 자주 읽히는가"가 아니라 **"얼마나 오래된 데이터인가"** 로 정해야 합니다.
이 성질은 테스트로 고정했습니다 (`readingDoesNotExtendLifetime`).

---

## 4. 측정 — 로컬 스텁, 동일 요청 10회

```
앱 ─────────────► naver-stub ─────► 호출 수를 직접 센다
   Local Search
   Reverse Geocode
```

- 스텁: `tools/performance/naver-stub/stub.py` (응답 지연 50ms 고정)
- 요청: **완전히 동일한 뷰포트 10회** (강남역, `neLat=37.5030 … swLng=127.0350`)
- 각 조건마다 **앱을 재시작**해 캐시를 비운 상태에서 시작

### 결과

| 조건 | local-search TTL | reverse TTL | 스텁이 받은 Local | 스텁이 받은 Reverse | **총 외부 호출** |
|---|---:|---:|---:|---:|---:|
| **A** | **0 (OFF)** | 1800 | **90** | **1** | **91** |
| **B** | 300 | 1800 | **9** | **1** | **10** |
| **C** | 300 | **0 (OFF)** | **9** | **10** | **19** |

### 같은 시점의 캐시 지표 (`/actuator/prometheus`)

| 조건 | local hit | local miss | reverse hit | reverse miss | 등록된 캐시 |
|---|---:|---:|---:|---:|---|
| A | — | — | 9 | 1 | **reverse-geocoding만** |
| B | 81 | 9 | 9 | 1 | 둘 다 |
| C | 81 | 9 | — | — | **local-search만** |

### 이 표에서 확인되는 것

1. **캐시가 예상대로 동작한다.**
   조건 B에서 10번 요청했지만 Local Search 외부 호출은 9번(키워드 9개 × 1회)뿐입니다.
   나머지 9번의 요청은 전부 캐시에서 나왔습니다 (hit 81 = 9키워드 × 9회).

2. **한쪽을 꺼도 다른 쪽은 산다.**
   A는 Local Search만 껐는데 Reverse Geocoding은 여전히 외부 호출 1번 + hit 9번입니다.
   C는 그 반대입니다. **두 캐시가 서로 간섭하지 않습니다.**

3. **통계가 섞이지 않는다.**
   꺼진 캐시는 지표 자체가 등록되지 않습니다.
   Grafana에서 시계열이 사라지는 것이 곧 "이 캐시는 꺼져 있다"는 신호가 됩니다.

> ⚠️ **응답 시간은 비교 근거로 쓰지 않습니다.**
> 이 측정의 시간 차이는 스텁의 고정 지연 50ms와 클라이언트 레이트 리미터(200ms 간격)가
> 대부분을 만듭니다. 실제 네이버 API가 아닙니다.
> **TTL 120초와 300초의 성능을 비교한 적이 없으므로 "TTL을 늘려 빨라졌다"고 말할 수 없습니다.**
> 이 측정이 말하는 것은 **호출 수와 hit/miss가 정책대로 나온다**는 것뿐입니다.

---

## 5. 모니터링 — Grafana에서 실제로 보이는가

`recordStats()`만으로는 테스트에서만 값을 볼 수 있습니다.
그러면 **"캐시를 넣었다"까지만 증명되고 "운영에서 맞는 정책인지"는 알 수 없습니다.**
TTL 5분/30분은 데이터 특성으로 정한 초기값이므로, 조정하려면 실제 지표가 필요합니다.

Micrometer에 바인딩해 Prometheus → Grafana로 내보냈습니다.

| 지표 | 뜻 |
|---|---|
| `cache_gets_total{cache, result="hit"/"miss"}` | 적중률 계산의 재료 |
| `cache_evictions_total{cache}` | maximumSize에 눌려 밀려난 수 |
| `cache_size{cache}` | 현재 항목 수 (바이트 아님) |

캐시 키(요청 URI)는 **태그로 쓰지 않았습니다.** 검색어마다 시계열이 생겨 지표가 폭발합니다.
태그는 `cache` 하나뿐이고 값은 2개라 카디널리티가 안전합니다.

### 실제 화면

![캐시 패널이 포함된 대시보드](screenshots/grafana-dashboard-with-cache.png)

「지도 외부 API 캐시」 행이 아래쪽에 새로 붙었습니다.

![캐시 적중률](screenshots/grafana-cache-hit-ratio.png)

**두 캐시가 다르게 동작하는 것이 한눈에 보입니다.**

- `local-search` **100%** — 같은 지역이면 검색어가 같아 항상 적중합니다.
- `reverse-geocoding` **70%** — 좌표가 곧 키라서, 지도를 움직이면 매번 새 키가 됩니다.

이 차이가 **캐시를 나눈 이유를 그대로 보여줍니다.** 하나의 Map이었다면 이 두 숫자는
섞여 하나의 값이 되고, 어느 쪽이 문제인지 알 수 없었습니다.

![캐시 축출](screenshots/grafana-cache-eviction.png)

`reverse-geocoding`만 축출이 발생합니다.
이 화면은 **`maximumSize`를 일부러 5로 좁혀** 축출을 눈에 보이게 만든 것입니다
(기본값 1000에서는 이 부하로 축출이 나지 않습니다).

축출이 계속 오른다는 것은 **상한이 작아 캐시가 제 역할을 못 하고 있다**는 신호입니다.
운영에서 이 그래프가 이렇게 보이면 `maximum-size`를 올려야 합니다.

### 부하 조건

지도를 쓰는 상황을 흉내냈습니다 — **10번 중 7번은 같은 화면을 다시 보고, 3번은 지도를 옮깁니다.**
지도를 옮기면 좌표가 달라져 Reverse Geocoding 캐시 키가 새로 생깁니다.
Local Search는 같은 지역명으로 수렴하므로 적중이 쌓입니다.

---

## 6. 재현

```bash
# 1) 스텁
python3 tools/performance/naver-stub/stub.py --port 9999 --latency-ms 50 &

# 2) 조건별로 앱 실행 (캐시를 비우기 위해 조건마다 재시작)
cd backend && SPRING_PROFILES_ACTIVE=dev \
  NAVER_LOCAL_CLIENT_ID=stub NAVER_LOCAL_CLIENT_SECRET=stub \
  NAVER_MAP_CLIENT_ID=stub NAVER_MAP_CLIENT_SECRET=stub \
  NAVER_OPENAPI_LOCAL_ENDPOINT=http://localhost:9999/v1/search/local.json \
  NAVER_OPENAPI_REVERSE_ENDPOINT=http://localhost:9999/map-reversegeocode/v2/gc \
  NAVER_LOCAL_SEARCH_CACHE_TTL_SECONDS=300 \
  NAVER_REVERSE_GEOCODING_CACHE_TTL_SECONDS=1800 \
  APP_S3_CREATEBUCKETIFMISSING=false ./gradlew bootRun

# 3) 동일 요청 10회 + 계측
TOKEN=$(curl -s -X POST "http://localhost:8080/api/auth/dev/seed?email=cache@nemo.test" | jq -r .accessToken)
curl -s -X POST http://localhost:9999/__reset
for i in $(seq 1 10); do
  curl -s -o /dev/null -H "Authorization: Bearer $TOKEN" \
    "http://localhost:8080/api/map/photobooths/viewport?neLat=37.5030&neLng=127.0450&swLat=37.4930&swLng=127.0350"
done
curl -s http://localhost:9999/__stats                                      # 실제 외부 호출 수
curl -s http://localhost:8080/actuator/prometheus | grep '^cache_'         # hit/miss/eviction/size
```

캐시 동작 자체는 테스트로도 고정돼 있습니다.

```bash
cd backend && ./gradlew test --tests '*NaverApiCacheTest*' --tests '*PhotoboothCacheRegressionTest*'
```

---

## 7. Trade-off와 한계

- **5분 / 30분은 최적값이 아닙니다.** 데이터 변경 특성으로 정한 **초기값**입니다.
  운영에서 hit ratio · 외부 호출 수 · 실제 데이터 변경 빈도를 보고 조정할 값입니다.
- **TTL별 성능 비교를 하지 않았습니다.** 이 문서의 어떤 숫자도 "TTL을 늘려서 빨라졌다"를
  뒷받침하지 않습니다.
- **여전히 JVM 프로세스별 로컬 캐시입니다.**
  - 인스턴스가 여러 개면 캐시가 공유되지 않습니다. 인스턴스 수만큼 외부 호출이 늡니다.
  - 서버를 재시작하면 캐시가 사라집니다.
  - 공유가 필요해지면 Redis를 봐야 하지만, **지금은 인스턴스가 1개라 근거가 없습니다.**
- **`maximumSize`는 바이트가 아니라 항목(entry) 개수입니다.**
  응답 크기가 큰 항목이 많으면 1000개라도 메모리를 많이 쓸 수 있습니다. 아직 측정하지 않았습니다.
- **부하가 낮습니다.** 로컬 스텁 기준이고 실제 트래픽 패턴이 아닙니다.
- 캐시 지표에 대한 **알림 규칙은 만들지 않았습니다.**

## 8. 아직 하지 않은 것

- 실제 네이버 API로 hit ratio 관찰 (이 측정은 전부 스텁입니다)
- `maximum-size` 기본값 1000의 근거 확보 (현재는 임의값)
- 캐시 메모리 사용량(바이트) 측정
- hit ratio 저하 / eviction 급증에 대한 알림
