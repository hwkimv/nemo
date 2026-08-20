// backend/src/main/java/com/nemo/backend/domain/storage/repository/StorageCleanupTaskRepository.java
package com.nemo.backend.domain.storage.repository;

import com.nemo.backend.domain.storage.entity.StorageCleanupTask;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface StorageCleanupTaskRepository extends JpaRepository<StorageCleanupTask, Long> {

    /**
     * 지금 처리할 수 있는 작업을 가져온다.
     *
     * <p><b>PESSIMISTIC_WRITE인 이유</b> — 워커가 둘 이상이면(인스턴스가 늘거나 스케줄이 겹치면)
     * 같은 행을 같이 집어 S3 삭제를 두 번 부를 수 있다. 삭제 자체는 멱등이라 데이터가
     * 깨지지는 않지만, 재시도 횟수가 두 배로 오르고 한쪽이 COMPLETED로 바꾼 행을
     * 다른 쪽이 PENDING으로 되돌릴 수 있다. 행을 잠가서 한 번에 하나만 보게 한다.
     *
     * <p>오래된 것부터 처리한다. 늦게 들어온 작업이 앞지르면
     * 실패가 반복되는 작업이 영원히 뒤로 밀린다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT t FROM StorageCleanupTask t
            WHERE t.status = com.nemo.backend.domain.storage.entity.StorageCleanupTask$Status.PENDING
              AND t.nextAttemptAt <= :now
            ORDER BY t.nextAttemptAt ASC, t.id ASC
            """)
    List<StorageCleanupTask> findClaimable(@Param("now") LocalDateTime now, Pageable pageable);

    /**
     * 죽은 워커가 PROCESSING으로 잡아 둔 채 남긴 작업.
     *
     * <p>워커가 작업 중에 서버가 내려가면 행은 PROCESSING에 멈춘 채로 남는다.
     * 이걸 회수하지 않으면 그 파일은 영원히 지워지지 않는다.
     * "서버가 죽어도 다시 처리 가능"은 이 조회가 있어야 성립한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT t FROM StorageCleanupTask t
            WHERE t.status = com.nemo.backend.domain.storage.entity.StorageCleanupTask$Status.PROCESSING
              AND t.updatedAt < :staleBefore
            """)
    List<StorageCleanupTask> findStaleProcessing(@Param("staleBefore") LocalDateTime staleBefore);

    /** 특정 작업 하나를 잠그고 읽는다. 즉시 처리와 워커가 같은 행을 동시에 잡는 것을 막는다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM StorageCleanupTask t WHERE t.id = :id")
    java.util.Optional<StorageCleanupTask> findByIdForUpdate(@Param("id") Long id);

    long countByStatus(StorageCleanupTask.Status status);
}
