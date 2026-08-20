# PostgreSQL 사진 저장 한도 동시성 측정

사진 저장 한도 로직이 H2에서만 통과한 상태를 넘어서, 실제 PostgreSQL의 행 잠금에서
한도 보장·잠금 대기·타임아웃·데드락 감지가 어떻게 동작하는지 확인했다.

## 측정 환경

| 항목 | 조건 |
|---|---|
| 측정일 | 2026-08-20 |
| 실행 환경 | Windows Docker Desktop 4.86.0, WSL2에서 Gradle 실행 |
| DB | Docker `postgres:17.10-alpine`, 전용 로컬 DB `nemo_concurrency_measurement` |
| 애플리케이션 | Java 21.0.11, Spring Boot 3.5.3, Hibernate 6.6.18 |
| 연결 | `127.0.0.1:55433`, 측정 전용 `nemo_concurrency` 계정 |
| 데이터 | 테스트마다 UUID 사용자 생성, 실제 운영 데이터 미사용 |

기존 성능 측정 DB `nemo_benchmark`에는 손대지 않는다. Compose가 별도 컨테이너·볼륨·DB를
생성하며, 테스트의 접속 주소·DB 이름·계정은 로컬 측정 전용 값으로 고정했다. 애플리케이션의
실제 `PhotoService.uploadHybrid()` 흐름과 PostgreSQL JDBC의 행 잠금 통제 실험을 같은 테스트
클래스에서 분리했다.

## 실행 명령

```bash
docker compose --profile concurrency up -d --wait postgres-concurrency
cd backend
./gradlew postgresConcurrencyMeasurement --rerun-tasks
```

기본 `test`에서는 `postgres-concurrency` 태그를 제외한다. Docker PostgreSQL을 사용할 수
있는 환경에서 위 작업을 명시적으로 실행해야 한다.

## 결과

| 검증 | 설정 | 결과 |
|---|---|---|
| 실제 `PhotoService.uploadHybrid()` 흐름 | 한도 20장, 기존 19장, 동시 요청 8건 | 성공 1건, 한도 거절 7건, 최종 20장, 631ms |
| 행 잠금 대기 | 트랜잭션 A가 사용자 행을 잠근 뒤 B 대기, 대기 확인 후 300ms 뒤 A 해제 | B가 306ms 뒤 정상 획득 |
| 잠금 타임아웃 | 같은 경합에서 B의 `lock_timeout=250ms` | 289ms 뒤 SQLSTATE `55P03` |
| 데드락 감지 | A는 사용자 1→2, B는 사용자 2→1 순으로 의도적 교차 잠금 | 1,001ms 뒤 한쪽 `40P01`, 다른 쪽 정상 완료 |

측정 출력:

```text
POSTGRES_LOCK_WAIT elapsedMs=306 sqlState=null
POSTGRES_LOCK_TIMEOUT elapsedMs=289 sqlState=55P03
POSTGRES_DEADLOCK_DETECTION abortedElapsedMs=1001 results=[DatabaseAttempt[elapsedMs=1001, sqlState=40P01], DatabaseAttempt[elapsedMs=1002, sqlState=null]]
POSTGRES_QUOTA accepted=1 rejected=7 finalCount=20 elapsedMs=631
BUILD SUCCESSFUL
```

## 해석 경계

- **증명된 것:** 한 사용자 행을 `SELECT ... FOR UPDATE`로 잠그고 같은 트랜잭션에서
  `COUNT → INSERT`를 수행하면, PostgreSQL에서도 동시 8건이 한도를 20장보다 넘기지 않는다.
- **타임아웃:** PostgreSQL은 이 측정에서 설정한 250ms 뒤 `55P03`으로 잠금 대기를 중단했다.
  현재 운영 코드에 250ms 타임아웃을 적용했다는 뜻은 아니다.
- **데드락:** 두 사용자 행을 반대 순서로 잡는 통제 실험에서 PostgreSQL의 `40P01` 처리를
  확인했다. 실제 사진 업로드는 사용자 한 행만 잠그므로 서비스 데드락을 재현한 결과가 아니다.
- **일반화 금지:** 로컬 단일 PostgreSQL 컨테이너 결과다. 운영 Supabase의 연결 풀,
  네트워크 지연, 실제 트래픽에서의 처리량·p95를 나타내지 않는다.

## 자동 검증 위치

- `PostgresPhotoQuotaConcurrencyMeasurementTest`
- `StorageService.reserveQuotaOrThrow`
- `UserRepository.findByIdForUpdate`
