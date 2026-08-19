# 2026-08-20 지도 캐시 분리와 TTL 정책 검증

> 상태: `Verified`
> 관련 문서: [CS 05 — 지도 API 캐시가 가리고 있던 것](../case-studies/05-map-api-cache.md)

이 문서는 **성능이 얼마나 빨라졌는가**를 다루지 않습니다.
확인한 것은 **정책이 의도대로 동작하는가** 세 가지입니다.

1. 두 종류의 데이터를 **독립된 정책**으로 관리할 수 있는가
2. 캐시가 **예상대로 hit/miss** 되는가
3. 한쪽 캐시를 꺼도 **다른 쪽이 살아 있는가**

---

## 1. Before — Caffeine 캐시 **하나**

바로 앞 작업([2026-08-19 Caffeine 로컬 캐시 교체](2026-08-19-caffeine-local-cache.md))에서
직접 구현하던 `ConcurrentHashMap` 캐시를 Caffeine으로 갈아탔습니다.
그 결과 크기 상한과 통계는 생겼지만, **캐시는 여전히 하나**였습니다.

```java
// 이번 작업 직전 상태
private final Cache<String, Map<String, Object>> cache;   // 하나

@Value("${naver.cache.ttl-seconds:120}")   long cacheTtlSeconds;
@Value("${naver.cache.maximum-size:1000}") long cacheMaximumSize;
```

| | Before |
|---|---|
| 구현 | Caffeine (`expireAfterWrite` + `maximumSize` + `recordStats`) |
| 담기는 것 | Local Search 응답 **과** Reverse Geocoding 응답 (**같은 캐시**) |
| TTL | 둘 다 **120초** |
| 크기 상한 | 1000 entry (공용) |
| 통계 | 있지만 **둘이 합쳐진 하나의 값** |

메모리 문제는 해결됐습니다. **남은 문제는 정책과 관측이었습니다.**

## 2. 문제 — 두 데이터의 변경 특성이 다른데 정책이 하나였다

| | Local Search | Reverse Geocoding |
|---|---|---|
| 담는 것 | 업체 검색 결과 (이름·주소·좌표·링크) | 좌표 → 행정구역 |
| 바뀌는 이유 | 폐업, 신규 오픈, 정보 수정 | 행정구역 개편 |
| 바뀌는 빈도 | 가끔 | 거의 없음 |
| 적절한 TTL | 짧게 | 길게 |

하나의 TTL로는 **한쪽에 맞추면 다른 쪽이 손해**입니다.
120초는 업체 정보 기준으로는 짧을 것도 없지만, 좌표→주소 변환에는 지나치게 짧습니다.

그리고 `recordStats()`가 켜져 있어도 **통계가 하나로 합쳐져** 나옵니다.
적중률이 85%라고 해도 그게 어느 쪽 덕분인지, 어느 쪽이 발목을 잡는지 알 수 없습니다.
**정책을 조정하려면 먼저 구분이 돼야 합니다.**

## 3. After — 용도별 캐시 2개

| | local-search | reverse-geocoding |
|---|---|---|
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

## 4. 측정 — **실제 NAVER API**, 동일 요청 10회

스텁이 아니라 **실제 네이버 API에 키를 붙여** 측정했습니다.
스텁은 제가 정한 대로 응답하므로 "정책이 실제 트래픽에서 도는가"를 답하지 못합니다.

- 요청: **완전히 동일한 뷰포트 10회** (강남역, `neLat=37.5030 … swLng=127.0350`)
- 조건마다 **앱을 재시작**해 캐시가 빈 상태에서 시작
- **첫 요청도 측정 안에 포함**합니다. 따로 빼면 그 요청이 캐시를 데워 조건 간 비교가 깨집니다
- 외부 호출 수는 `naver_api_calls_total` 지표로 셉니다 (호출하는 자리에서 직접 카운트)
- API 키는 환경변수로만 주입했고 저장소·문서 어디에도 쓰지 않았습니다

### 결과

| 조건 | local TTL | reverse TTL | 외부 Local | 외부 Reverse | **총 외부 호출** | 10회 소요 | **뷰포트 내 결과** |
|---|---:|---:|---:|---:|---:|---:|---:|
| **A** | **0 (OFF)** | 1800 | **90** | 1 | **91** | 18,907ms | **3건** |
| **B** | 300 | 1800 | **9** | 1 | **10** | 2,058ms | **3건** |
| **C** | 300 | **0 (OFF)** | 9 | **10** | **19** | 3,705ms | **3건** |

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

4. **결과가 바뀌지 않는다.**
   세 조건 모두 뷰포트 내 결과가 **3건으로 동일**합니다.
   캐시는 성능 장치이지 기능이 아니라는 것이 실제 응답으로 확인됩니다.

### 응답 시간을 어디까지 말할 수 있나

| 비교 | 말할 수 있는 것 |
|---|---|
| A(18.9초) vs B(2.1초) | **캐시가 있고 없고의 차이**입니다. 같은 요청 10회 중 9회가 네트워크를 타지 않습니다 |
| B(2.1초) vs C(3.7초) | Reverse Geocoding 캐시가 있고 없고의 차이입니다 |
| TTL 120초 vs 300초 | **측정하지 않았습니다. 말할 수 없습니다** |

> ⚠️ **"TTL을 120초에서 300초로 늘려 빨라졌다"는 이 측정으로 뒷받침되지 않습니다.**
> 세 조건 모두 캐시 ON/OFF를 비교한 것이지 TTL 값을 비교한 것이 아닙니다.
> 5분/30분은 데이터 특성으로 정한 초기값이고, 조정 근거는 운영 지표에서 나와야 합니다.

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

### 실제 화면 (실제 NAVER API 기준)

![캐시 패널이 포함된 대시보드](screenshots/grafana-dashboard-with-cache.png)

「지도 외부 API 캐시」 행이 아래쪽에 새로 붙었습니다.

![캐시 적중률](screenshots/grafana-cache-hit-ratio.png)

**두 캐시가 다르게 동작하는 것이 한눈에 보입니다.**

- `local-search` **98.9%** — 지도를 옮겨도 지역명이 같으면 검색어가 같아 적중합니다
- `reverse-geocoding` **69.9%** — 좌표가 곧 키라서, 지도를 움직이면 매번 새 키가 됩니다

이 차이가 **캐시를 나눈 이유를 그대로 보여줍니다.** 하나의 캐시였다면 이 두 숫자는
하나로 합쳐지고, 어느 쪽이 발목을 잡는지 알 수 없었습니다.

![네이버로 실제 나간 외부 호출](screenshots/grafana-naver-api-calls.png)

적중률만으로는 부족합니다. **비용은 "밖으로 몇 번 나갔는가"** 로 발생합니다.
적중률이 98.9%인 `local-search`가 실제 호출은 더 적고,
69.9%인 `reverse-geocoding`이 더 많이 나가는 것이 이 패널에서 드러납니다.

![캐시에서 빠진 항목](screenshots/grafana-cache-eviction.png)

> **여기서 하나 배웠습니다.** 처음에는 이 패널을 "maximumSize에 눌려 밀려난 수"로 설명했는데,
> 실제로 돌려보니 상한에 한참 못 미치는 `local-search`에도 값이 잡혔습니다.
> Caffeine의 `evictionCount()`는 **TTL 만료로 빠진 것도 함께 셉니다.**
> 그래서 패널 이름을 「캐시에서 빠진 항목 (eviction + 만료)」로 고쳤습니다.
> 대시보드를 실제로 띄워보지 않았으면 틀린 설명을 그대로 뒀을 것입니다.

「캐시 항목 수」 패널에서 `local-search`가 한 번 0으로 떨어졌다가 다시 차오릅니다.
**TTL 300초가 지나 한꺼번에 만료된 것**입니다. `expireAfterWrite`가 눈에 보이는 순간입니다.

### 부하 조건

지도를 쓰는 상황을 흉내냈습니다 — **10번 중 7번은 같은 화면을 다시 보고, 3번은 지도를 옮깁니다.**
지도를 옮기면 좌표가 달라져 Reverse Geocoding 캐시 키가 새로 생깁니다.
Local Search는 같은 지역명으로 수렴하므로 적중이 쌓입니다.

**실제 네이버 API를 씁니다.** 쿼터를 아끼려고 요청 사이에 150ms 간격을 뒀고,
약 12분 동안 2,100여 건을 보냈습니다.
축출을 눈에 보이게 하려고 `reverse-geocoding`의 `maximum-size`만 **5로 좁혔습니다**
(기본값 1000에서는 이 부하로 상한에 닿지 않습니다).

---

## 6. 재현

```bash
# 조건별로 앱 실행 (캐시를 비우기 위해 조건마다 재시작)
cd backend && SPRING_PROFILES_ACTIVE=dev \
  NAVER_LOCAL_CLIENT_ID=... NAVER_LOCAL_CLIENT_SECRET=... \
  NAVER_MAP_CLIENT_ID=...   NAVER_MAP_CLIENT_SECRET=... \
  NAVER_LOCAL_SEARCH_CACHE_TTL_SECONDS=300 \
  NAVER_REVERSE_GEOCODING_CACHE_TTL_SECONDS=1800 \
  APP_S3_CREATEBUCKETIFMISSING=false ./gradlew bootRun

# 동일 요청 10회 (첫 요청도 포함해서 센다)
TOKEN=$(curl -s -X POST "http://localhost:8080/api/auth/dev/seed?email=cache@nemo.test" | jq -r .accessToken)
for i in $(seq 1 10); do
  curl -s -o /dev/null -H "Authorization: Bearer $TOKEN" \
    "http://localhost:8080/api/map/photobooths/viewport?neLat=37.5030&neLng=127.0450&swLat=37.4930&swLng=127.0350"
done

# 실제로 네이버로 나간 호출 수 + 캐시 적중/축출
curl -s http://localhost:8080/actuator/prometheus | grep -E '^(naver_api_calls|cache_)'
```

조건을 바꿀 때는 `NAVER_LOCAL_SEARCH_CACHE_TTL_SECONDS=0` (Local만 OFF) 또는
`NAVER_REVERSE_GEOCODING_CACHE_TTL_SECONDS=0` (Reverse만 OFF)으로 다시 띄웁니다.

키 없이 캐시 동작만 확인하려면 스텁을 씁니다.

```bash
python3 tools/performance/naver-stub/stub.py --port 9999 --latency-ms 50 &
cd backend && ./gradlew mapCacheMeasurement     # 스텁 기반 OFF/ON 비교
```

캐시 동작 자체는 테스트로도 고정돼 있습니다.

```bash
cd backend && ./gradlew test --tests '*NaverApiCacheTest*' --tests '*PhotoboothCacheRegressionTest*'
```

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
- **부하 패턴이 인위적입니다.** 실제 네이버 API를 쓰긴 했지만 요청 패턴은 제가 만든 것이고, 실사용자 트래픽이 아닙니다.
- 캐시 지표에 대한 **알림 규칙은 만들지 않았습니다.**

## 8. 아직 하지 않은 것

- **여러 지역·시간대 반복 측정.** 이번 측정은 강남역 한 곳 기준입니다
- `maximum-size` 기본값 1000의 근거 확보 (현재는 임의값)
- 캐시 메모리 사용량(바이트) 측정
- hit ratio 저하 / eviction 급증에 대한 알림
