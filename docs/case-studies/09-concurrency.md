---
title: unique 제약이 지켜주지 않는 조건 하나
status: Verified
date: 2026-08-14
---

# Case Study — unique 제약이 지켜주지 않는 조건 하나

> **한 줄 요약** — 동시성 문제를 일부러 만들지 않고, 실제로 깨질 수 있는 곳을 먼저 찾았습니다. 후보 대부분은 DB unique 제약이 막아주고 있었고, **딱 하나가 뚫려 있었습니다.** 한도 20장에 한 자리 남은 상태에서 동시 8건을 보내니 **26장**이 됐습니다.

| | |
|---|---|
| **기간** | 2026-08-14 |
| **범위** | Backend — 사진 저장 한도(`maxPhotoCount`) |
| **결과** | 동시 8건 → 최종 **26장에서 20장으로** |
| **증거** | `PhotoQuotaConcurrencyTest` (2) — 수정 전 실패, 수정 후 통과 |

---

## Problem

> **동시성 문제를 일부러 만든 뒤 기술을 넣는 방식은 금지한다.**
> 실제 invariant가 깨질 가능성을 먼저 증명한다.

이 기준이 이번 작업의 출발점이었습니다. 낙관적 락이나 분산 락을 "써봤다"고 말하려고
없는 문제를 만들어 넣으면 포트폴리오로서 가치가 없습니다.

그래서 **먼저 후보를 훑고, 이미 보호되고 있는 것과 아닌 것을 구분**했습니다.

---

## Analyze / Constraints

동시 요청에서 깨질 수 있는 흐름을 찾아 각각 **마지막 방어선이 있는지** 확인했습니다.

| 흐름 | 패턴 | DB 방어선 | 판정 |
|---|---|---|---|
| 친구 요청 중복 | 존재 확인 → INSERT | `UNIQUE(user_id, friend_id)` | ✅ 보호됨 |
| 앨범 즐겨찾기 | 존재 확인 → INSERT | `UNIQUE(album_id, user_id)` | ✅ 보호됨 |
| 앨범 공유 초대 | 존재 확인 → INSERT | `UNIQUE(album_id, user_id)` | ✅ 보호됨 |
| **사진 저장 한도** | **COUNT → 비교 → INSERT** | **없음** | ❌ **뚫림** |

앞의 셋은 check-then-act이지만 **DB가 마지막에 잡아줍니다.**
경쟁에서 진 요청은 `DataIntegrityViolationException`을 받고 409로 변환됩니다.
바람직한 오류 메시지는 아니지만 **데이터는 깨지지 않습니다.**

**저장 한도만 다릅니다.**

```java
// PhotoServiceImpl.uploadHybrid()
storageService.checkPhotoLimitOrThrow(userId);   // COUNT(*) 후 비교
...
String key = storage.store(image);               // 느린 외부 저장
...
photoRepository.save(photo);                     // INSERT
```

세는 시점과 넣는 시점이 떨어져 있고, 그 사이에 **느린 S3 업로드까지 끼어 있습니다.**
그리고 이 조건은 **"행 개수"에 대한 것이라 unique 제약으로 표현할 수 없습니다.**
애플리케이션이 지키지 못하면 아무도 지켜주지 않습니다.

---

## Options

증명을 먼저 하고, 그 다음에 방법을 골랐습니다.

| 방법 | 장점 | 단점 | 선택 |
|---|---|---|---|
| 낙관적 락 (`@Version`) | 잠금 없음 | 충돌 시 재시도 필요. 애초에 갱신할 행이 없다(INSERT다) | ❌ |
| User에 카운터 컬럼 + 조건부 UPDATE | 가장 빠름 | 실제 행 수와 어긋날 수 있는 값이 하나 더 생김 | ❌ |
| **User 행 비관적 락** | 정확하고 단순. 추가 상태 없음 | 같은 사용자의 업로드가 줄을 섬 | ✅ |

**세 번째를 골랐습니다.**

한 사용자가 동시에 여러 장을 올리는 것은 드문 일이고, 그 경우에 줄을 서는 비용보다
**한도가 깨지는 비용이 큽니다.** 잠금은 사용자별이라 다른 사용자끼리는 서로 영향이 없습니다.

카운터 컬럼(2번)은 더 빠르지만 **진실이 두 곳에 생깁니다.** 사진 행과 카운터가 어긋나면
어느 쪽이 맞는지 알 수 없습니다. 지금 규모에서 그 위험을 살 이유가 없습니다.

---

## Action

### 1. 먼저 깨지는 것을 증명

한도 20장에 **한 자리만 남긴 상태**에서 동시 8건을 보냈습니다.

```
저장 한도를 넘었다. 동시 요청 8건 중 7건이 통과했고 최종 26장이 됐다.
한도는 20장이다.
```

**7건이 통과했습니다.** 모두 "아직 19장이니 여유 있음"을 보고 각자 저장했습니다.

### 2. 저장 직전에 잠그고 다시 센다

```java
@Transactional(propagation = Propagation.MANDATORY)
public void reserveQuotaOrThrow(Long userId) {
    User user = userRepository.findByIdForUpdate(userId)   // SELECT ... FOR UPDATE
            .orElseThrow(...);
    int used = photoRepository.countByUserIdAndDeletedIsFalse(userId);
    if (used >= user.getMaxPhotoCount()) {
        throw new PhotoLimitExceededException(...);
    }
}
```

호출 위치가 중요합니다.

```java
// PhotoServiceImpl.uploadHybrid()
storageService.checkPhotoLimitOrThrow(userId);   // ① 미리 거절 (느린 저장 전에)
String key = storage.store(image);               // ② 느린 외부 저장
storageService.reserveQuotaOrThrow(userId);      // ③ 잠그고 재확인  ← 틈을 닫는 곳
photoRepository.save(photo);                     // ④ INSERT
```

**①을 남겨둔 이유** — 한도가 이미 찬 사용자에게 S3 업로드를 시키고 나서 거절하는 것은 낭비입니다.
빠른 실패는 그대로 두고, **정확성은 ③이 책임집니다.**

**③을 ②보다 뒤에 둔 이유** — 잠금을 ① 자리에서 잡으면 느린 S3 업로드가 끝날 때까지
사용자 행이 잠깁니다. 잠금 구간은 짧을수록 좋습니다.

### 3. 잠금이 조용히 무의미해지는 것을 막는다

`MANDATORY`를 쓴 이유가 있습니다. 별도 트랜잭션에서 돌면 **잠금이 즉시 풀려**
INSERT 시점에는 아무 보호가 없습니다. 그런데 코드는 멀쩡해 보입니다.

```java
assertThatThrownBy(() -> storageService.reserveQuotaOrThrow(user.getId()))
        .isInstanceOf(IllegalTransactionStateException.class);
```

호출자 트랜잭션이 없으면 아예 실패하게 못을 박았습니다.

---

## Result

| | 수정 전 | 수정 후 |
|---|---:|---:|
| 동시 8건 중 통과 | **7건** | **1건** |
| 최종 사진 수 | **26장** | **20장** |
| 한도 | 20장 | 20장 |

**같은 테스트가 수정 전에는 실패하고 수정 후에 통과합니다.**
테스트가 공허하지 않다는 근거입니다.

---

## Limit / Next Condition

- **H2에서 검증했습니다.** `SELECT ... FOR UPDATE`는 PostgreSQL에서도 같은 의미지만,
  잠금 대기 시간과 데드락 감지 동작은 다릅니다. PostgreSQL에서 재확인이 필요합니다.
  (CI에 PostgreSQL 서비스 컨테이너를 붙이면 함께 해결됩니다 — [CS 07](07-ci-cd.md))
- **잠금 타임아웃을 지정하지 않았습니다.** 기본값에 맡겨져 있습니다.
  업로드가 몰릴 때 대기가 길어지면 타임아웃과 재시도 정책이 필요합니다.
- **S3 업로드가 트랜잭션 안에 있습니다.** 잠금 구간은 짧게 뒀지만 트랜잭션 자체는
  여전히 외부 저장을 포함합니다. 이건 별도 문제이며 [P2-1(보상 삭제)](../README.md)과 함께 다뤄야 합니다.
- **친구·즐겨찾기는 DB 제약에 기대고 있습니다.** 데이터는 안전하지만 사용자에게 가는 메시지는
  `CONSTRAINT_VIOLATION`입니다. 도메인 오류로 바꾸면 더 낫습니다.
  ([CS 08](08-sentry.md)에서 친구 요청 중복을 409로 바꾼 것과 같은 방향)
- **다른 집계 조건이 더 있을 수 있습니다.** 이번엔 사진 한도만 확인했습니다.
  "행 개수"나 "합계"에 대한 조건은 전부 같은 위험을 갖습니다.

---

## Evidence

| 항목 | 위치 |
|---|---|
| 재현·검증 테스트 | `PhotoQuotaConcurrencyTest` |
| 잠금 조회 | `UserRepository.findByIdForUpdate` |
| 최종 확인 | `StorageService.reserveQuotaOrThrow` |
| 호출 위치 | `PhotoServiceImpl.uploadHybrid` (저장 직전) |

```bash
cd backend && ./gradlew test --tests '*PhotoQuotaConcurrencyTest*'
```

수정 전 상태를 보려면 `reserveQuotaOrThrow` 호출을 지우고 같은 테스트를 돌리면 됩니다.
