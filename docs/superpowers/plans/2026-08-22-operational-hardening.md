# 운영 하드닝 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 배포를 사람 손에서 떼어내고(readiness 확인 + 자동 rollback), health 신호를 liveness/readiness로 분리하며, 문서가 코드보다 과거를 말하는 상태를 없앤다.

**Architecture:** Spring Boot 3.5.x health group(probes)으로 liveness/readiness를 분리한다. 배포는 EC2 호스트에 있는 셸 스크립트가 수행하고, GitHub Actions는 test → build → GHCR push 와 배포할 SHA 전달까지만 한다. 새 장기 자격증명·새 인바운드 포트·새 인프라를 만들지 않는다.

**Tech Stack:** Spring Boot Actuator health groups · Docker · bash · GitHub Actions

**Spec:** 사용자 요청(2026-08-22) + 분석 보고서

---

## 결정 사항 (사용자 승인)

| 항목 | 결정 | 근거 |
|---|---|---|
| 배포 방식 | **C안 — 호스트 배포 스크립트** | OIDC+SSM은 `IAMReadOnlyAccess`로 차단. SSH는 Elastic IP 부재로 재기동마다 깨짐 |
| EC2 | 켜서 실측 후 정지 | 섹션 6·7 검증에 필요 |
| 채용 프레이밍 | 저장소 전체 정리 | 기술적 맥락 표현으로 교체 |
| DB role 분리 | **분석만.** 운영 DB 권한 변경 금지 | 사용자 지시 |
| Redis/RabbitMQ/K8s/ALB | 도입 금지 | 조건 미성립 |

---

## Task 1 — readiness / liveness 분리

**파일:** `backend/src/main/resources/application.yml`, `application-prod.yml`,
`backend/src/test/java/com/nemo/backend/global/health/HealthProbesTest.java`(신규)

- [ ] `management.endpoint.health.probes.enabled: true` 를 공통에 켠다
- [ ] health group 정의: `liveness` = `ping` 만, `readiness` = `db`
- [ ] `management.endpoints.web.exposure.include` 에 health 하위 경로가 포함되는지 확인
- [ ] 실패하는 테스트 먼저: `/actuator/health/liveness` 가 200, `/actuator/health/readiness` 가 존재
- [ ] 테스트 실행 → 실패 확인
- [ ] 설정 적용 → 테스트 통과 확인
- [ ] commit

**핵심 제약:** DB 장애가 liveness 를 DOWN 시키면 안 된다. 그러면 기동 중(21초)인 앱을
오케스트레이터가 계속 죽여 무한 재시작에 빠진다. liveness 는 프로세스 생존만 본다.

## Task 2 — DB readiness 응답이 30초 걸리는 문제

**파일:** `application-prod.yml`

- [ ] Hikari `validation-timeout` / `connection-timeout` 이 health 응답 시간을 지배하는지 확인
- [ ] readiness 전용으로 짧은 타임아웃을 줄 수 있는지 확인
- [ ] 실측 전/후 비교. 측정하지 못하면 측정하지 못했다고 적는다

## Task 3 — 리미터 보장 범위 기록 테스트를 실제 조건으로 고친다

**파일:** `backend/src/test/java/.../NaverApiRateLimitTest.java`

- [ ] 현재 `Thread.sleep(0)` 이 아무 지연도 만들지 않음을 확인 (근거 확보)
- [ ] 지연을 **슬롯 확보 이후**에 주입하도록 고친다 (호출 직전 스텁에서 지연)
- [ ] 실행해 실제 출력값을 기록
- [ ] CS 11 · 코드 javadoc 의 "600ms / 9회" 를 **실측값으로 교체**
- [ ] commit

## Task 4 — 호스트 배포 스크립트 + rollback

**파일:** `infra/deploy/nemo-deploy.sh`(신규), `infra/deploy/README.md`(신규)

- [ ] 인자: 이미지 태그(SHA). 그 외 비밀값은 받지 않는다
- [ ] 현재 실행 중인 이미지를 rollback 후보로 기록 (`/home/ec2-user/.nemo-last-good`)
- [ ] 새 이미지 pull → 기존 컨테이너 교체 → readiness 폴링(상한 있음)
- [ ] readiness 실패 시: 새 컨테이너 로그 저장 → 제거 → 이전 이미지 재기동 → readiness 재확인
- [ ] rollback 까지 실패하면 exit 2 (사람 개입 필요 신호)
- [ ] 성공 시에만 last-good 갱신
- [ ] `set -euo pipefail`, 비밀값 echo 금지
- [ ] commit

## Task 5 — deploy.yml 을 실제 상태에 맞춘다

**파일:** `.github/workflows/deploy.yml`

- [ ] Railway 언급 제거
- [ ] 주석만 남은 꼬리(60-79행) 정리
- [ ] 배포 대상 안내를 "이 SHA 로 호스트 스크립트를 실행하라"로 교체
- [ ] "continuous deployment" 라고 쓰지 않는다. deployment automation 으로 표기
- [ ] commit

## Task 6 — Dockerfile HEALTHCHECK

**파일:** `backend/Dockerfile`

- [ ] readiness 를 보는 HEALTHCHECK 추가
- [ ] **문서에 `--restart unless-stopped` 가 unhealthy 로 재시작한다고 쓰지 않는다.**
      Docker HEALTHCHECK 는 관측·배포 판정용이다
- [ ] 로컬 docker build + run 으로 healthy 전이 확인
- [ ] commit

## Task 7 — 실제 검증 (EC2)

- [ ] EC2 start, 현재 이미지 배포
- [ ] Before: 기존 `/actuator/health` 만 있는 상태의 DB 장애 응답시간 (CS 12 기록 재사용 여부 판단)
- [ ] After: liveness / readiness 각각 측정
  - [ ] 정상 기동 → liveness UP, readiness UP
  - [ ] DB 연결 차단 → liveness ?, readiness ?
  - [ ] DB 복구 → readiness 회복 시간
- [ ] rollback 실험
  - [ ] 정상 이미지 deploy → readiness 성공
  - [ ] 의도적으로 실패하는 배포 → 감지 → 이전 이미지 rollback → readiness 회복
  - [ ] rollback 후 API 정상 확인
- [ ] **실패 주입에 운영 데이터·비밀값을 훼손하지 않는 방법 선택**
- [ ] EC2 stop, 실지출 확인

## Task 8 — 문서 정합성

- [ ] README 재구성 (성능 / 정합성 / 동시성·외부 API / 운영·AWS 4개 + AI 워크플로 짧게)
- [ ] 테스트 수 **192** 로 통일
- [ ] Railway · "배포 스텝 없음" · "리미터 아직 안 고침" · "마이그레이션 도구 없음" 제거
- [ ] `AWS_ACCESS_KEY_ID` → 실제 변수명, 그리고 "넣으면 안 된다"로 정정
- [ ] CS 11 sliding window 모순 해소, 라인 참조 갱신, 테스트 수 12 로 통일
- [ ] CS 12 라인 참조 갱신, "Docker 2개" → "JVM 2개", 반자동 서술 갱신
- [ ] ai-development-workflow: "최소 1건" 규칙 제거, updateAndGet → CAS, 독립 리뷰 결과 반영
- [ ] docs/README.md 프롬프트 4개로
- [ ] 채용 프레이밍 전체 정리
- [ ] commit

## Task 9 — DB 최소 권한 role 분석 (보고서만)

**파일:** `docs/reference/db-least-privilege-analysis.md`(신규)

- [ ] 현재 운영이 어떤 롤로 붙는지 **확인**
- [ ] A(동일 계정) / B(runtime·migration 분리) / C(Supabase 제약) 비교
- [ ] **DB 권한을 실제로 바꾸지 않는다**

## Task 10 — HTTPS 판단 (마지막)

- [ ] 도메인 보유 여부 확인
- [ ] 없으면 self-signed 를 "HTTPS 구축"이라 부르지 않고 조건부 계획만 남긴다
- [ ] ALB/Route53 추가 금지

## Task 11 — PR 본문

- [ ] PR #17 에 "Final state after review" 추가 ($10.22/$1.51 → $13.87/$1.85). 과거 서술은 지우지 않는다
- [ ] PR #16 최종 상태 명시 (조건부 CAS, 장기 평균 보장)
- [ ] 채용 목적 언급 금지

## Task 12 — 최종 검증

- [ ] `./gradlew clean test` 전체
- [ ] `git diff --check`
- [ ] README 링크 검사
- [ ] Docker build
- [ ] prod 프로필 기동 가능 여부
- [ ] secret 유출 검사
- [ ] README 숫자 ↔ 코드/테스트 대조
- [ ] PR ↔ CS ↔ README 주장 대조
