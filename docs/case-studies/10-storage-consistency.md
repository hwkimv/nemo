---
title: DB 트랜잭션이 지켜주지 못하는 경계 — S3와 DB 사이
status: Verified
date: 2026-08-21
---

# Case Study — DB 트랜잭션이 지켜주지 못하는 경계

> **한 줄 요약** — `@Transactional`이 붙어 있으니 안전하다고 생각했던 사진 업로드·삭제에서, **트랜잭션이 롤백해도 되돌아가지 않는 상태**를 3가지 찾아 테스트로 재현했습니다. 가장 나쁜 것은 "DB에는 사진이 있는데 파일이 없는" 되돌릴 수 없는 상태였습니다. 순서를 뒤집고 보상 처리 + DB 기반 재시도를 넣어 **모든 실패가 되돌릴 수 있는 쪽으로 기울게** 만들었습니다.

| | |
|---|---|
| **기간** | 2026-08-21 |
| **범위** | Backend — 사진 업로드/삭제, S3 ↔ PostgreSQL 경계 |
| **결과** | 고아 객체 3가지 경로 차단, 되돌릴 수 없는 상태 제거 |
| **증거** | `PhotoStorageConsistencyTest` (6), `StorageCleanupRecoveryTest` (11) — 전체 156 → 173개 |
| **추가 인프라** | **없음** (기존 PostgreSQL + Micrometer 재사용) |

---

## Problem

사진 업로드·삭제 코드에는 `@Transactional`이 붙어 있었습니다.
그래서 "실패하면 롤백되니까 괜찮다"고 생각하기 쉬운 구조였습니다.

그런데 **트랜잭션은 DB 안에서만 유효합니다.** S3는 그 밖에 있습니다.
`s3Client.deleteObject()`는 롤백되지 않고, `putObject()`도 마찬가지입니다.

즉 다음 두 줄 사이는 **트랜잭션이 지켜주지 못하는 경계**입니다.

```java
storage.store(image);        // ← S3. 롤백 안 됨
photoRepository.save(photo); // ← DB. 롤백 됨
```

---

## 어떻게 발견했는가

앞선 작업([CS 09](09-concurrency.md))에서 사진 저장 한도의 동시성을 고치면서
`uploadHybrid()`의 순서를 자세히 읽게 됐습니다. 그때 눈에 걸린 것이 이 부분이었습니다.

```java
// PhotoServiceImpl.java (개선 전)
@Transactional                                  // ← 클래스 레벨
public PhotoResponseDto uploadHybrid(...) {
    storageService.checkPhotoLimitOrThrow(userId);
    String key = storage.store(image);          // ← S3 업로드가 트랜잭션 '안'
    ...
    storageService.reserveQuotaOrThrow(userId);
    Photo saved = photoRepository.save(photo);   // ← 여기서 실패하면?
}
```

`reserveQuotaOrThrow()`는 **한도가 찼으면 예외를 던집니다.**
동시 업로드로 한도가 차는 순간 이 예외가 실제로 발생합니다 —
그리고 그 시점에 S3에는 이미 파일이 올라가 있습니다.

삭제 쪽은 더 나빴습니다.

```java
// PhotoServiceImpl.java (개선 전)
public void delete(Long userId, Long photoId) {
    try {
        storage.delete(imageKey);               // ← S3를 '먼저' 지운다
        storage.delete(thumbKey);
    } catch (Exception e) {
        log.warn("[PHOTO][delete] S3 삭제 실패 ...");   // ← 삼킨다
    }
    photo.setDeleted(true);                     // ← DB는 '나중'
    photoRepository.save(photo);
}
```

두 군데가 걸립니다.

1. **S3가 먼저**입니다. 그 뒤 DB가 실패하면 파일은 이미 없는데 DB에는 사진이 남습니다.
2. **예외를 삼킵니다.** S3 삭제가 실패해도 DB는 그대로 삭제됩니다.
   그러면 그 파일의 키를 아는 코드가 세상에서 사라집니다.

---

## 장애 재현

추측으로 끝내지 않고 **깨진 상태를 눈으로 확인**했습니다.
`PhotoStorageConsistencyTest`를 개선 전 코드에 대고 돌린 결과입니다.

### Case A — S3 업로드 성공 → DB 저장 실패

```
photoRepository.save() 에서 예외
→ DB 사진 수        : 0건   (롤백됨)
→ S3에 남은 객체    : 1개   ← 고아
→ 보상 삭제 호출    : 0회   ← 아무도 치우지 않는다
```

> 처음에는 저장 한도를 0으로 두고 재현하려 했는데 **재현되지 않았습니다.**
> `uploadHybrid()` 맨 앞의 `checkPhotoLimitOrThrow()`가 S3 업로드보다 먼저 막기 때문입니다.
> 즉 "한도 초과"는 대개 파일을 올리기 전에 걸러집니다.
> **위험한 구간은 이미 S3에 올려놓고 DB를 건드리는 그 뒤쪽**이라는 것을 이때 알았습니다.

### Case B — S3 삭제 성공 → DB 트랜잭션 실패

```
storage.delete() 성공 후 DB 실패
→ S3 파일          : 삭제됨  ← 되돌릴 수 없다
→ DB photo.deleted : false   ← 사진은 살아 있다고 적혀 있다
```

**셋 중 가장 나쁩니다.** 사용자에게는 목록에는 보이는데 열면 없는 사진이 됩니다.
그리고 사라진 파일은 **어떤 방법으로도 되살릴 수 없습니다.**

### Case C — S3 삭제 실패

```
storage.delete() 예외 → catch에서 로그만
→ DB photo.deleted : true    ← 삭제 처리됨
→ S3 파일          : 남아 있음 ← 고아
→ 키 기록          : 없음     ← 무엇을 지워야 하는지 알 수 없다
```

DB에서 사진 행이 삭제 처리되면 그 키를 참조하는 코드가 없어집니다.
**영원히 지울 수 없는 파일**이 됩니다.

---

## 원인

세 경우 모두 원인은 하나입니다.

> **트랜잭션 경계와 부작용 경계가 다르다.**

`@Transactional`은 DB 변경만 원자적으로 묶습니다.
S3 호출은 그 안에 있어도 밖에 있어도 똑같이 **즉시 확정**됩니다.
그래서 "트랜잭션 안에 넣었으니 안전하다"는 감각이 오히려 위험했습니다.

여기에 두 가지가 겹쳤습니다.

- **순서가 잘못됐다** — 삭제에서 되돌릴 수 없는 쪽(S3)을 먼저 건드렸습니다.
- **실패를 기록하지 않았다** — 로그는 사람이 보지 않으면 아무 일도 하지 않습니다.

---

## 고려한 대안

| | 구현 복잡도 | 서버 재시작 후 복구 | 중복 실행 대응 | 인프라 증가 | NEMO에 적절한가 |
|---|---|---|---|---|---|
| **1. 즉시 보상 처리** | 매우 낮음 | ❌ 불가 | 해당 없음 | 없음 | 부분적 — 이것만으로는 부족 |
| **2. DB에 작업 영속화 + 재시도** | 낮음 | ✅ 가능 | 상태 + 행 잠금 | **없음** | ✅ **적절** |
| **3. Transactional Outbox** | 중간 | ✅ 가능 | 소비자 멱등성 필요 | 릴레이/CDC | 과함 |
| **4. RabbitMQ 등 MQ** | 높음 | ✅ 가능 | 소비자 멱등성 필요 | **브로커 운영** | 과함 |

### 왜 1번만으로는 안 되는가

즉시 보상은 "S3에 올렸는데 DB가 실패했으니 S3를 다시 지운다"입니다.
대부분의 경우 여기서 끝납니다. 하지만 **그 보상 삭제마저 실패하면** 다시 원점입니다.
그 사이에 서버가 죽어도 마찬가지입니다.

`@Async`나 메모리 큐도 같은 한계입니다. **프로세스와 함께 사라집니다.**

### 왜 3번이 과한가

Outbox는 "DB 변경과 메시지 발행을 원자적으로" 묶는 패턴입니다.
그런데 지금 필요한 것은 외부에 알리는 것이 아니라 **우리가 나중에 지우는 것**뿐입니다.
릴레이나 CDC를 붙일 이유가 없습니다.

다만 **핵심 아이디어는 그대로 가져왔습니다** — 할 일을 DB에 적고, 그 기록을
원래 변경과 **같은 트랜잭션에서** 커밋하는 것. 그 뒤에 붙는 전송 계층만 뺐습니다.

### 왜 RabbitMQ를 넣지 않았는가

- 지금 필요한 것은 "실패한 삭제를 잊지 않고 다시 하기"뿐입니다. 라우팅도, 팬아웃도 없습니다.
- **소비자가 하나**입니다. 발생량은 S3가 정상일 때 **0**입니다.
- 브로커를 하나 더 운영해야 합니다 — 배포, 모니터링, 장애 대응이 늘어납니다.
- 결정적으로, **큐에 넣는 것과 DB 커밋 사이에 지금 없애려는 것과 똑같은 종류의 불일치**가
  새로 생깁니다. DB는 커밋됐는데 큐 발행이 실패하면 어떻게 할 것인가? 다시 같은 문제입니다.

이름값 때문에 넣으면, 나중에 "왜 필요했나"에 답할 수 없습니다.

---

## 선택한 해결책 — 2번 (즉시 보상 + DB 기반 재시도)

**두 겹으로 막습니다.**

```
1층: 즉시 보상   문제가 난 그 자리에서 바로 S3 삭제 시도
                → S3가 멀쩡한 대부분의 경우 여기서 끝. DB에 아무것도 안 남는다.

2층: 영속 작업   1층이 실패하면 지워야 할 키를 DB에 기록
                → 워커가 backoff를 두고 재시도. 서버가 죽어도 행은 남는다.
```

1층만 있으면 "보상마저 실패"에서 정보가 사라지고,
2층만 있으면 정상적인 경우에도 워커 주기만큼 파일이 남습니다. **둘 다 필요합니다.**

---

## 구현

### ① 업로드 — 트랜잭션 밖으로 빼고 보상

```java
@Transactional(propagation = Propagation.NOT_SUPPORTED)   // ← 트랜잭션 없음
public PhotoResponseDto uploadHybrid(...) {
    String key = storage.store(image);        // S3 (트랜잭션 밖)
    uploadedKey = key;
    try {
        Photo saved = photoPersistence.save(userId, photo);   // 짧은 트랜잭션
        return new PhotoResponseDto(saved);
    } catch (RuntimeException e) {
        cleanupService.deleteNowOrScheduleRetry(uploadedKey, UPLOAD_ROLLBACK);
        throw e;
    }
}
```

트랜잭션을 **뺀** 이유가 두 가지입니다.

1. S3 업로드와 QR 크롤링은 느린 네트워크 호출입니다.
   트랜잭션이 이걸 감싸면 **DB 커넥션과 사용자 행 잠금을 쥔 채로** 기다립니다.
   업로드가 몰리면 커넥션 풀이 먼저 마릅니다.
2. 어차피 S3는 롤백되지 않습니다. 감싸 봐야 **보호받는다는 착각**만 줍니다.

### ② 삭제 — 순서를 뒤집었다

```java
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public void delete(Long userId, Long photoId) {
    Photo photo = photoPersistence.findOwnedPhotoOrThrow(userId, photoId);
    List<String> keys = /* imageUrl, thumbnailUrl에서 추출 */;

    // DB 삭제 + "이 키를 지워야 한다" 기록을 한 트랜잭션에서 커밋
    List<Long> taskIds = photoPersistence.markDeletedAndScheduleCleanup(userId, photoId, keys);

    // 커밋된 뒤에 실제 파일 삭제. 실패해도 위 기록이 남아 워커가 이어받는다.
    for (Long taskId : taskIds) cleanupService.runNow(taskId);
}
```

**핵심은 "되돌릴 수 있는 쪽으로 실패가 기울게 한다"입니다.**

| | 개선 전 | 개선 후 |
|---|---|---|
| DB 실패 시 남는 상태 | 파일이 사라짐 (**복구 불가**) | 파일이 남아 있음 (나중에 지울 수 있음) |
| S3 실패 시 남는 상태 | 키를 잃은 고아 객체 | 키가 DB에 기록됨 → 워커가 재시도 |

그리고 DB 삭제와 정리 작업 기록이 **같은 트랜잭션**이라 둘은 같이 커밋되거나 같이 롤백됩니다.
하나만 남는 상태가 생기지 않습니다.

### ③ 정리 작업 테이블

```sql
storage_cleanup_task
  id, object_key, status, reason, retry_count, next_attempt_at, last_error, created_at, updated_at
```

설계 초안과 다르게 한 부분을 적어 둡니다.

- **`RETRY_WAIT` 상태를 만들지 않았습니다.** "대기 중"은
  `status = PENDING` + `next_attempt_at > now`로 이미 표현됩니다.
  같은 뜻을 두 곳에 적으면 둘이 어긋날 수 있습니다.
- **`operation` 컬럼을 넣지 않았습니다.** 지금 필요한 동작은 삭제 하나뿐이라
  값이 하나뿐인 컬럼이 됩니다. 업로드 재시도 같은 다른 동작이 실제로 생기면 그때 추가합니다.
- 대신 **`reason`**(`UPLOAD_ROLLBACK` / `PHOTO_DELETED`)을 넣었습니다.
  장애를 되짚을 때 "어느 흐름에서 생긴 건가"를 알아야 합니다.

### ④ 클래스를 나눈 이유

| 클래스 | 역할 | 트랜잭션 |
|---|---|---|
| `PhotoServiceImpl` | 흐름 조립, S3 호출 | **없음** |
| `PhotoPersistence` | 사진 DB 작업 | 있음 (짧게) |
| `StorageCleanupService` | 정리 오케스트레이션, S3 호출 | **없음** |
| `StorageCleanupTaskStore` | 정리 작업 DB 조작 | 있음 (짧게) |
| `StorageCleanupWorker` | `@Scheduled` 주기 실행 | 없음 |

**같은 빈 안에서 `@Transactional` 메서드를 호출하면 프록시를 타지 않아 트랜잭션이 걸리지 않습니다.**
S3 호출은 트랜잭션 밖에, DB 조작은 안에 있어야 하는데 두 성질이 한 클래스에 있으면
이 함정에 빠집니다. 그래서 나눴습니다.
(같은 이유로 이미 `RefreshTokenMaintenance`가 분리돼 있습니다 — [CS 03](03-security-boundaries.md))

덤으로 **트랜잭션이 S3 호출을 감싸지 않는다**는 것이 구조로 드러납니다.

---

## 재시도와 멱등성

| 요구 | 어떻게 보장했나 |
|---|---|
| 같은 작업 두 번 실행해도 안전 | S3 `DeleteObject`는 **없는 키를 지워도 성공**합니다. 그래서 재시도가 안전합니다 |
| 이미 없는 객체 삭제 | 위와 같음. "이미 지워졌는지" 먼저 확인할 필요가 없습니다 |
| 여러 워커의 동시 처리 방지 | 조회 시 `PESSIMISTIC_WRITE` + `PENDING → PROCESSING` 상태 전이 |
| 작업 중 서버 사망 | `PROCESSING`에 오래 머문 행을 주기적으로 `PENDING`으로 **회수** |
| 무한 재시도 방지 | `max-retries`(기본 5) 초과 시 `FAILED`로 확정, 더 집어가지 않음 |
| backoff | `30s × 2^재시도횟수` — 죽은 저장소를 쉼 없이 두드리지 않음 |
| 실패 원인 기록 | `last_error` — 없으면 `FAILED`가 돼도 손쓸 수 없음 |

**선점과 처리를 분리한 이유**: S3 호출은 느립니다.
그 시간 동안 DB 행 잠금을 쥐고 있으면 다른 워커와 조회가 함께 막힙니다.
잠금은 짧게 잡고 바로 놓습니다.

---

## 테스트

### `PhotoStorageConsistencyTest` (6)

| 테스트 | 확인하는 것 |
|---|---|
| Case A | S3 업로드 후 DB 실패 → **보상 삭제 발생, 고아 없음, 작업도 안 남음** |
| Case B | DB 실패 시 **S3를 손대지 않음** — 파일이 사라지지 않는다 |
| Case C | S3 삭제 실패 시 **지울 키가 DB에 남고 `last_error` 기록** |
| 정상 업로드 | DB·S3 모두 존재, 정리 작업 0건 |
| 정상 삭제 | DB·S3 모두 정리, 작업은 `COMPLETED` |
| 권한 검사 | 타인 사진 삭제 시 S3도 작업도 건드리지 않음 |

### `StorageCleanupRecoveryTest` (11)

| 묶음 | 테스트 |
|---|---|
| 재시도 | 1회 실패 후 성공 / backoff 증가 / 최대 재시도 초과 후 중단 |
| 멱등성 | 두 번 실행 안전 / 없는 객체 삭제 / **워커 4개 동시 실행 시 1개만 선점** |
| 서버 사망 | 재시작 후 `PENDING` 재처리 / `PROCESSING` 멈춤 회수 |
| 지표 | `result` 태그 분리 / 대기 수 게이지 |
| 독립성 | 여러 작업이 서로 영향 없이 완료 |

**실패를 의도적으로 주입할 수 있게** `FakePhotoStorage`를 만들었습니다.
`failDelete()`, `failDeleteTimes(n)`, `failStore()`로 원하는 지점에서 원하는 횟수만큼
실패시킬 수 있습니다. 실제 S3나 LocalStack으로는 이렇게 정확히 넣을 수 없습니다.

```bash
cd backend && ./gradlew clean test
```

---

## 결과

### 실패 시나리오별 최종 상태

| 장애 | 개선 전 | 개선 후 |
|---|---|---|
| S3 업로드 성공 → DB 실패 | S3 고아 객체 1개, 아무도 모름 | **즉시 보상 삭제** → 고아 없음 |
| 위 + 보상 삭제도 실패 | (보상 자체가 없었음) | **DB에 기록** → 워커가 재시도 |
| 위 + 서버 재시작 | 작업 유실 | **행이 남아 다음 기동에 처리** |
| DB 삭제 실패 | **파일이 사라짐 (복구 불가)** | S3 손대지 않음 — 일관된 상태 |
| S3 삭제 실패 | 키를 잃은 고아 객체 | **키가 DB에 남아 재시도** |
| 워커가 작업 중 사망 | (해당 없음) | `PROCESSING` 회수 → 재처리 |
| 재시도 계속 실패 | (해당 없음) | `FAILED` + 지표 상승 → 사람이 확인 |

**개선 후 어떤 경로에서도 "되돌릴 수 없는 상태"가 남지 않습니다.**
최악의 경우는 "파일이 잠깐 더 남아 있는 것"이고, 그건 나중에 지울 수 있습니다.

### 측정한 것

| 항목 | 값 |
|---|---|
| 재현한 정합성 파괴 경로 | **3가지** (모두 테스트로 확인) |
| 추가한 테스트 | **17개** |
| 전체 테스트 | 156 → **173개, 전체 통과** |
| 추가 인프라 | **0** (기존 PostgreSQL·Micrometer 재사용) |
| 새 지표 | 카운터 6종(`result` 태그) + 게이지 2종 — 실행 중인 앱의 `/actuator/prometheus`에서 확인 |
| 새 테이블 | `storage_cleanup_task` + 인덱스 1개 (Hibernate DDL 로그로 생성 확인) |

### 측정하지 않은 것 (숫자를 만들지 않기 위해)

- **실제 S3/LocalStack에 대고 재현하지 않았습니다.** 모든 재현은 `FakePhotoStorage` 기준입니다.
  확인하려던 것이 AWS SDK의 동작이 아니라 **두 저장소를 건드리는 순서**였기 때문입니다.
- **트랜잭션 밖으로 뺀 효과를 부하로 측정하지 않았습니다.**
  커넥션 점유 시간이 줄어드는 것은 구조상 분명하지만, "커넥션 풀 대기가 몇 % 줄었다"는
  부하 테스트 없이 말할 수 없습니다.
- **운영에서 실제로 발생한 고아 객체 수를 모릅니다.** 이 코드는 아직 운영에 나가지 않았고,
  기존에 쌓인 고아 객체를 찾는 작업(S3 목록 ↔ DB 대조)은 하지 않았습니다.

---

## 관측

기존 Micrometer/Prometheus/Grafana를 그대로 씁니다. **새 모니터링 스택은 없습니다.**

| 지표 | 뜻 |
|---|---|
| `storage_cleanup_tasks_total{result}` | `compensated_inline` / `enqueued` / `completed` / `retried` / `failed` / `reclaimed` |
| `storage_cleanup_tasks_pending` | 지금 밀려 있는 작업 수 — 계속 오르면 S3 장애 또는 워커 지체 |
| `storage_cleanup_tasks_permanently_failed` | **0이 아니면 사람이 봐야 합니다** |

카운터는 "얼마나 일어났는가"만 알려줍니다. **"지금 얼마나 밀려 있는가"는 게이지여야 합니다.**
Grafana 대시보드에 「S3 파일 정리」 행으로 3패널을 추가했습니다.

---

## 남은 한계

- **기존에 쌓인 고아 객체는 그대로입니다.** 이번 변경은 앞으로 생기는 것을 막을 뿐입니다.
  과거 것을 찾으려면 S3 객체 목록과 DB를 대조하는 별도 작업이 필요합니다.
- **`storage_cleanup_task`가 무한히 커집니다.** `COMPLETED` 행을 지우는 정리가 없습니다.
  SQL 파일에 삭제 쿼리를 주석으로 남겼지만 자동화하지 않았습니다.
- **마이그레이션 도구가 없습니다.** prod는 `ddl-auto=validate`라 배포 전에
  `tools/schema/sql/schema-postgres.sql`을 **수동으로** 적용해야 합니다.
  적용하지 않으면 애플리케이션이 기동하지 않습니다.
- **워커가 인스턴스마다 돕니다.** 지금은 인스턴스가 1개라 문제없고, 여러 개가 돼도
  행 잠금이 중복 처리를 막습니다. 다만 모든 인스턴스가 같은 테이블을 폴링합니다.
- **`FAILED` 작업에 대한 알림이 없습니다.** 지표는 올라가지만 알림 규칙은 만들지 않았습니다.
- **앨범 커버·프로필 이미지 등 다른 S3 사용처는 손대지 않았습니다.** 같은 문제가 있을 수 있습니다.

---

## RabbitMQ는 언제 고려하는가

지금 구조로 충분한 이유는 **작업량이 적고 소비자가 하나**이기 때문입니다.
다음 중 하나라도 생기면 DB 폴링이 불리해집니다.

| 조건 | 왜 MQ가 나은가 |
|---|---|
| 작업 발생량이 초당 수백 건 이상 | 폴링 주기와 DB 부하가 병목이 됩니다. 큐는 푸시라 지연이 없습니다 |
| 소비자를 독립적으로 늘려야 함 | 워커를 API 서버와 분리해 따로 스케일하고 싶을 때 |
| 처리 지연이 초 단위여야 함 | 폴링 주기(현재 60초)가 곧 지연입니다 |
| 이벤트를 여러 곳이 받아야 함 | "사진 삭제"를 검색 색인·통계 등이 함께 구독해야 할 때 |
| 이미 MQ를 운영 중 | 추가 운영 비용이 0이면 판단이 달라집니다 |

**그때도 정합성 문제는 사라지지 않습니다.** DB 커밋과 큐 발행 사이의 불일치는
여전히 Outbox 같은 장치로 막아야 합니다. 지금 만든 `storage_cleanup_task`가
그 Outbox의 자리에 그대로 들어갑니다 — **버리는 구조가 아니라 이어지는 구조**입니다.

---

## 참고

- 재현·검증 테스트: `PhotoStorageConsistencyTest`, `StorageCleanupRecoveryTest`
- 실패 주입 도구: `FakePhotoStorage`
- 운영 DDL: `tools/schema/sql/schema-postgres.sql` (자동 생성) · 점검 쿼리: `tools/storage/sql/storage-cleanup-task.sql`
- 관련: [CS 09 — unique 제약이 지켜주지 않는 조건 하나](09-concurrency.md),
  [CS 06 — 지표를 붙이고 나서 알게 된 것](06-monitoring.md)
