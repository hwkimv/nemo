# NEMO 문서

> 처음 오셨다면 [Case Studies](#case-studies)부터 보시면 됩니다.
> 각 문서는 `문제 → 분석 → 선택지 → 실행 → 결과 → 한계` 순서로 되어 있습니다.

---

## Case Studies

문제 하나를 어떻게 발견하고, 무엇을 비교했고, 왜 그 방법을 골랐는지 적은 문서입니다.

| # | 주제 | 핵심 결과 | 상태 |
|---|---|---|---|
| 01 | [JWT 인증 경로에 검증 가능한 경계 세우기](case-studies/01-jwt-authentication.md) | 테스트 37개, 클럭 스큐·`isExpired()` 실제 동작 규명 | `Verified` |
| 02 | [Supabase PostgreSQL 전환 후 런타임 하드닝](case-studies/02-postgres-runtime-hardening.md) | 프로필 분리, 운영 smoke test, 개발용 표면 차단 | `Verified` |
| 03 | [인증·권한 경계의 구멍 4개 막기](case-studies/03-security-boundaries.md) | 타인 사진 접근 차단, 토큰 로그 제거, 회귀 테스트 17개 | `Verified` |
| 04 | [앨범 목록 N+1 제거와 측정](case-studies/04-query-performance.md) | **DB 쿼리 202 → 4, 응답 99ms → 11ms** | `Verified` |
| 05 | [지도 API 캐시가 가리고 있던 것](case-studies/05-map-api-cache.md) | 외부 호출 820 → 0회, 뷰포트 **25 → 10회**. 캐시를 데이터 성격별로 분리 | `Verified` |
| 06 | [지표를 붙이고 나서 알게 된 것](case-studies/06-monitoring.md) | 레이트 리미터가 동시 요청에 무력. 5회/초 의도 → 40회/초 | `Verified` |
| 07 | [테스트를 통과하지 않은 코드가 못 지나가게 막기](case-studies/07-ci-cd.md) | CI 관문 구축. 설정 누락·S3 기동 결합도 수정 | `Verified` |
| 08 | [Sentry를 붙였는데 이벤트가 0건이었다](case-studies/08-sentry.md) | 중복 친구 요청 500 → 409. 토큰 스크러빙 검증 | `Verified` |
| 09 | [unique 제약이 지켜주지 않는 조건 하나](case-studies/09-concurrency.md) | 동시 업로드로 저장 한도 초과 (26장 → 20장) | `Verified` |
| 10 | [DB 트랜잭션이 지켜주지 못하는 경계](case-studies/10-storage-consistency.md) | S3↔DB 불일치 3가지 재현. 보상 처리 + DB 기반 재시도 | `Verified` |
| 11 | [AtomicLong을 썼는데 동시 요청에서 막지 못한 리미터](case-studies/11-rate-limiter-concurrency.md) | 동시 16건 **74.0 → 5.0 req/s**. AI 보조 워크플로우 적용 | `Verified` |

---

## Evidence

측정 원자료입니다. Case Study의 숫자가 어디서 나왔는지 확인할 때 봅니다.

| 문서 | 내용 |
|---|---|
| [2026-08-05 성능 기준선](evidence/2026-08-05-baseline.md) | 개선 전 Before. 앨범 202 쿼리 / 121.21ms |
| [2026-08-14 After 측정과 인덱스 판단](evidence/2026-08-14-after-and-index.md) | 같은 환경 Before/After, 실행 계획, 인덱스 비교 |
| [2026-08-14 지도 API 캐시 측정](evidence/2026-08-14-map-cache.md) | 캐시 OFF/ON, 외부 호출 수, TTL 만료, 메모리 한계 |
| [2026-08-15 지도 실제 API 측정](evidence/2026-08-15-map-real-api.md) | API HUB 이관 확인, 페이지네이션 기여도 0%, 25 → 10회 |
| [2026-08-20 지도 캐시 분리와 TTL 정책 검증](evidence/2026-08-20-map-cache-split.md) | 실제 NAVER API로 캐시 2개 분리·독립 OFF 검증. 적중률 98.9% / 69.9% |
| [2026-08-20 PostgreSQL 사진 한도 동시성 측정](evidence/2026-08-20-postgresql-photo-quota-concurrency.md) | 동시 8건 한도 보장, 잠금 대기·타임아웃·데드락 감지 통제 실험 |
| [Grafana 대시보드 화면](evidence/screenshots/) | 실제 렌더링된 대시보드와 패널별 설명 |

**재현 도구**는 `tools/`에 있습니다 — k6 스크립트, 시드 SQL, 실행 계획 SQL, 인덱스 SQL,
네이버 API 스텁·실제 API 프로브(`tools/performance/`), Sentry 수집 스텁(`tools/observability/`),
S3 정리 테이블 DDL(`tools/storage/sql/`).

---

## AI 보조 개발 워크플로우

| 문서 | 내용 |
|---|---|
| [AI 보조 개발 워크플로우](ai-development-workflow.md) | 분석 → 재현 → 대안 → **사람의 결정** → 구현 → 측정 → 독립 리뷰 |
| [재사용 프롬프트](../prompts/engineering/) | `analyze-problem` / `implement-fix` / `review-pr` |

---

## Reference

| 문서 | 내용 |
|---|---|
| [기술 변경 기록 템플릿](reference/technical-change-record-template.md) | 새 변경을 기록할 때 복사해 씁니다 |
| [설계·계획 기록](reference/design-records/) | 작업 전에 세운 설계와 계획. 결과와 다를 수 있는 과거 기록입니다 |

---

## 문서 규칙

### 상태 표시

| 상태 | 뜻 |
|---|---|
| `Verified` | 기록된 명령과 실행 결과로 현재 저장소에서 확인됨 |
| `Draft` | 설계 또는 구현이 진행 중이며 검증 전 |
| `Historical` | 과거 의사결정 기록이며 현재 구현과 다를 수 있음 |

### 작성 원칙

1. 구현 완료, 실행 확인, 추정 또는 계획을 **구분해서** 적습니다.
2. 검증 명령과 관찰 결과를 **함께** 남깁니다.
3. 팀 기여와 개인 기여를 분리합니다.
4. 비밀값, 개인 데이터, 운영 데이터는 기록하지 않습니다.
5. **성능 수치는 실제 측정 조건과 결과가 있을 때만 사용합니다.**
6. 각 문서 끝에 **"아직 하지 않은 것"** 을 적습니다. 문서가 실제보다 앞서 나가지 않게 하는 장치입니다.
