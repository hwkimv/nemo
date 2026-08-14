# Case Study — JWT 인증 경로에 검증 가능한 경계 세우기

> 상태: `Verified` · 2026-08-09 · 검증 명령과 결과를 본문에 함께 적었습니다.

## Problem

NEMO의 인증은 `JwtUtil`(발급·파싱)과 `JwtAuthenticationFilter`(요청 단위 검사) 두 클래스에 걸쳐 있습니다. 두 클래스를 합쳐 340줄이고 모든 보호 API가 이 경로를 지나가지만, **테스트가 한 줄도 없었습니다.**

테스트가 없다는 것은 단순히 "커버리지가 낮다"가 아니라 다음을 아무도 확인한 적이 없다는 뜻입니다.

- 만료된 토큰이 정말 거절되는가
- 다른 키로 서명한 토큰이 통과하지는 않는가
- 어떤 경로가 토큰 없이 열려 있는가 (공개 경로 목록이 필터 안에 하드코딩돼 있습니다)
- 401을 돌려줄 때 필터 체인이 실제로 끊기는가

인증은 "돌아가는 것처럼 보이는" 상태와 "안전한" 상태의 차이가 눈에 보이지 않는 영역입니다. 로그인이 되고 API가 응답하면 정상처럼 보이지만, 위조 토큰이 통과해도 똑같이 정상처럼 보입니다.

## Analyze / Constraints

코드를 읽으며 확인이 필요한 지점을 먼저 목록화했습니다.

| 지점 | 확인이 필요한 이유 |
|---|---|
| `Keys.hmacShaKeyFor(secret)` | 32바이트 미만 시크릿은 HS256에서 안전하지 않음. 생성자에 방어 코드가 있으나 검증된 적 없음 |
| `setAllowedClockSkewSeconds(180)` | 만료 판정에 3분 관용이 있음. 의도인지 사고인지 코드만 봐서는 불명 |
| `requireIssuer(issuer)` | issuer 검사가 실제로 걸리는지 |
| `getUserId()`의 Integer/Long 분기 | JJWT가 작은 수를 Integer로 역직렬화함. 방어가 없으면 `ClassCastException` |
| `PUBLIC_PATTERNS` 11개 | 하드코딩된 목록. 누가 항목을 추가·삭제해도 아무 신호가 없음 |
| 401 응답 후 `return` | 체인을 끊지 않으면 인증 실패한 요청이 컨트롤러에 도달함 |

**제약**: 프로덕션 코드는 건드리지 않기로 했습니다. 3인 팀의 공유 브랜치이고, 다른 사람이 같은 파일을 작업 중일 수 있습니다. 먼저 **현재 동작을 사실대로 고정**하고, 바꿀 것이 있으면 별도 PR로 분리합니다.

## Options

| 대안 | 검토 결과 |
|---|---|
| `@SpringBootTest` 통합 테스트 | 컨텍스트 기동에 H2·S3 목이 필요해 느리고, 실패 시 원인이 인증인지 배선인지 흐려짐 |
| `@WebMvcTest` + `MockMvc` | Security 설정 전체를 끌어와야 하고, 필터 단독 동작을 격리하기 어려움 |
| **생성자 주입 + `MockFilterChain` 단위 테스트** | `JwtUtil`은 `@Value` 주입이라 생성자로 직접 설정을 넣을 수 있음. TTL을 케이스마다 바꿔 만료 시나리오를 실시간 대기 없이 만들 수 있음 |

세 번째를 골랐습니다. 결정적인 이유는 **만료 테스트**입니다. Spring 컨텍스트를 쓰면 `application-dev.yml`의 1시간 TTL이 고정되어, 만료 토큰을 만들려면 시계를 조작하거나 실제로 기다려야 합니다. 생성자로 음수 TTL을 주면 이미 만료된 토큰을 즉시 만들 수 있습니다.

```java
// 3분 클럭 스큐를 확실히 넘기는 만료 폭
private static final long EXPIRED_BEYOND_SKEW_MS = -300_000L;

JwtUtil sut = new JwtUtil(SECRET, ISSUER, EXPIRED_BEYOND_SKEW_MS);
String expired = sut.createAccessToken(USER_ID, EMAIL);
```

## Action

테스트를 두 파일로 나눴습니다. **실행되는 테스트는 37개**입니다.

| 파일 | 테스트 메서드 | 실행 개수 |
|---|---:|---:|
| `JwtUtilTest` | 19 | 19 |
| `JwtAuthenticationFilterTest` | 8 | **18** |
| 합계 | 27 | **37** |

필터 쪽 메서드가 8개인데 실행이 18개인 이유는, 공개 경로 11개를 하나의
`@ParameterizedTest`로 전수 확인하기 때문입니다. 경로가 늘어나면 값 하나만 추가하면 됩니다.

**`JwtUtilTest`** — 토큰 그 자체의 계약

- 정상: 발급/검증 왕복, `Bearer` 접두사, 공백 처리, email 클레임 결손 시 `null`
- 거절: 위조 서명, issuer 불일치, 만료, 구조 파손, 빈 문자열, `null`, userId 결손, userId 비숫자
- 설정: 32자 미만 시크릿, `null` 시크릿
- 경계: 클럭 스큐, Integer→Long 축소, `isExpired()` 실제 동작

**`JwtAuthenticationFilterTest`** — 요청 단위 계약

- 공개 경로 11개를 파라미터화 테스트로 전수 확인
- 헤더 없음 / 위조 / 만료 → 401, `Content-Type: application/json`, **체인 끊김**(`chain.getRequest() == null`)
- 유효 토큰 → `SecurityContext`에 `UserPrincipal(userId)` 적재
- 이미 인증된 컨텍스트는 토큰을 다시 보지 않고 통과
- `/api` 밖 경로는 보호 대상이 아님

위조 서명 케이스는 "공격자" 관점으로 썼습니다.

```java
JwtUtil attacker = new JwtUtil(OTHER_SECRET, ISSUER, ONE_HOUR_MS);
request.addHeader("Authorization", "Bearer " + attacker.createAccessToken(USER_ID, EMAIL));

filter.doFilter(request, response, chain);

assertThat(response.getStatus()).isEqualTo(401);
assertThat(chain.getRequest()).isNull();                                  // 체인이 끊겼는가
assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull(); // 인증이 새지 않았는가
```

401을 반환했는지만 보는 것으로는 부족합니다. 상태 코드를 쓰고도 `return`을 빠뜨리면 요청은 그대로 컨트롤러에 도달합니다. `chain.getRequest()`가 `null`인지를 함께 확인해야 실제로 막혔다고 말할 수 있습니다.

## Result

```
$ cd backend && ./gradlew test
BUILD SUCCESSFUL in 42s

48 tests, 0 failures, 0 errors   (기존 11 + 신규 37)
```

> 이 시점 기준 48개입니다. 이후 [CS 03](03-security-boundaries.md)·[CS 04](04-query-performance.md)에서
> 37개가 더해져 현재는 **86개**입니다.

프로덕션 코드는 한 줄도 바꾸지 않았습니다. 전부 기존 동작을 그대로 고정한 것입니다.

테스트를 쓰는 과정에서 **문서화할 가치가 있는 동작 두 가지**를 찾았습니다.

### 1. 만료 판정에 3분 관용이 실재한다

`setAllowedClockSkewSeconds(180)` 때문에, 만료된 지 3분 이내인 토큰은 **아직 통과합니다.**

```java
@Test
@DisplayName("만료된 지 3분 이내인 토큰은 클럭 스큐 허용치 안이라 통과한다")
void acceptsTokenWithinClockSkew() {
    JwtUtil sut = new JwtUtil(SECRET, ISSUER, -60_000L);  // 1분 전에 만료
    assertThatCode(() -> sut.validateToken(sut.createAccessToken(USER_ID, EMAIL)))
            .doesNotThrowAnyException();
}
```

서버 간 시계 오차를 흡수하려는 의도로 보이고, 액세스 토큰 TTL이 1시간인 현재 규모에서 3분은 합리적입니다. 다만 **이것이 의도라는 사실이 코드 어디에도 적혀 있지 않았습니다.** 테스트로 고정해 두면 누군가 스큐를 줄이거나 없앨 때 이 테스트가 깨지면서 "이건 의도된 관용이었다"는 신호를 줍니다.

### 2. `isExpired()`는 boolean을 돌려주지 않는다

이름은 boolean 질의처럼 보이지만, 내부에서 `parseClaims()`를 거치기 때문에 **만료 토큰에서는 `true`가 아니라 `ExpiredJwtException`이 던져집니다.**

```java
public boolean isExpired(String token) {
    Claims claims = parseClaims(token);   // ← 만료면 여기서 예외
    Date exp = claims.getExpiration();
    return exp != null && exp.before(new Date());   // ← 도달하지 않음
}
```

즉 `if (jwtUtil.isExpired(token))` 형태로 쓰면 만료 토큰을 영원히 잡지 못하고 예외가 밖으로 새어 나갑니다. 현재 호출부가 없어서 사고로 이어지지는 않았습니다.

고치지 않고 테스트로 고정한 이유는, **호출부가 생기기 전에 시그니처를 정하는 편이 낫고** 그 결정은 팀과 함께 해야 하기 때문입니다. `Optional<Boolean>`으로 바꿀지, 예외를 잡아 `true`를 반환할지, 메서드를 지울지는 별도 PR로 분리해 [PR #74](https://github.com/KDUcapstone/nemo-app/pull/74)에서 제안했습니다.

## Limit / Next Condition

이 작업이 **증명하지 않는** 것을 명확히 해 둡니다.

- **리프레시 토큰 회전은 다루지 않았습니다.** `AuthService.refresh()`는 만료 임박 시에만 토큰을 회전시키는 정책(`refresh-rotate-threshold-sec`)을 갖고 있는데, DB(`RefreshTokenRepository`)가 필요해 이번 단위 테스트 범위 밖입니다. 다음 작업 대상입니다.
- **리프레시 토큰이 RDB에 평문으로 저장됩니다.** 현재 단일 인스턴스·졸업작품 규모에서는 운영 요구를 충족한다고 판단했습니다. 다중 인스턴스에서 즉시 무효화가 필요해지거나 조회량이 늘면 Redis 등 별도 저장소와 해시 저장을 검토해야 합니다.
- **401 응답 본문에 예외 메시지가 그대로 들어갑니다.** `writeUnauthorized(res, e.getMessage())`가 JJWT 예외 문자열을 문자열 연결로 JSON에 넣습니다. 메시지에 따옴표가 들어가면 JSON이 깨지고, 내부 구현이 노출될 여지도 있습니다. 고정 코드 + 서버 로그 분리로 바꾸는 것이 맞다고 보지만, 응답 형식 변경은 Flutter 클라이언트와 함께 정해야 해서 이번 범위에서 제외했습니다.
- **성능은 측정하지 않았습니다.** 이 작업은 정확성에 대한 것입니다.

## Evidence

| 항목 | 위치 |
|---|---|
| 테스트 코드 | [`JwtUtilTest.java`](../../backend/src/test/java/com/nemo/backend/domain/auth/jwt/JwtUtilTest.java) · [`JwtAuthenticationFilterTest.java`](../../backend/src/test/java/com/nemo/backend/domain/auth/jwt/JwtAuthenticationFilterTest.java) |
| 대상 코드 | [`JwtUtil.java`](../../backend/src/main/java/com/nemo/backend/domain/auth/jwt/JwtUtil.java) · [`JwtAuthenticationFilter.java`](../../backend/src/main/java/com/nemo/backend/domain/auth/jwt/JwtAuthenticationFilter.java) |
| 원본 저장소 PR | [KDUcapstone/nemo-app#74](https://github.com/KDUcapstone/nemo-app/pull/74) |
| 재현 | `cd backend && ./gradlew test` (Java 21 필요) |
