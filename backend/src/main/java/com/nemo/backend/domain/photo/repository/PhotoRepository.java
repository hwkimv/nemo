// backend/src/main/java/com/nemo/backend/domain/photo/repository/PhotoRepository.java
package com.nemo.backend.domain.photo.repository;

import com.nemo.backend.domain.photo.entity.Photo;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PhotoRepository extends JpaRepository<Photo, Long> {

    // ✅ 사진 목록 조회용 동적 필터 (favorite / brand / tag)
    @Query("""
        SELECT p
        FROM Photo p
        WHERE p.userId = :userId
          AND p.deleted = false
          AND (:favorite IS NULL OR p.favorite = :favorite)
          AND (:brand IS NULL OR p.brand = :brand)
          AND (:tag IS NULL OR p.memo LIKE %:tag%)
        """)
    Page<Photo> findForList(
            @Param("userId") Long userId,
            @Param("favorite") Boolean favorite,
            @Param("brand") String brand,
            @Param("tag") String tag,
            Pageable pageable
    );

    // ✅ 특정 사진이 살아있는지 검사할 때 사용
    Optional<Photo> findByIdAndDeletedIsFalse(Long id);

    Optional<Photo> findByIdAndUserIdAndDeletedIsFalse(Long id, Long userId);

    // ✅ 앨범에 넣어도 되는 사진만 조회 (요청자 소유 + 미삭제)
    //    findAllById()와 달리 userId 조건이 들어 있어, 남의 사진 ID는 애초에 결과에 나오지 않는다.
    List<Photo> findAllByIdInAndUserIdAndDeletedIsFalse(Collection<Long> ids, Long userId);

    // ✅ 타임라인용: 촬영일시 기준 내림차순 전체 조회 (그대로 유지)
    List<Photo> findByUserIdAndDeletedIsFalseOrderByTakenAtDesc(Long userId);

    /**
     * 타임라인용: 특정 기간의 사진만 DB에서 걸러 가져온다. [start, end) 반열림 구간.
     *
     * 예전에는 사용자의 사진을 전부 읽어온 뒤 Java에서 year/month를 비교해 버렸다.
     * 8월 타임라인을 보려고 3년치 사진을 전부 JVM에 올리는 구조였다.
     *
     * COALESCE(takenAt, createdAt)를 쓰는 이유:
     * 기존 resolveDate()가 "촬영일이 없으면 업로드일을 쓴다"였다. 같은 규칙을 DB에서도 써야
     * 촬영일 없는 사진이 목록에서 조용히 사라지지 않는다.
     *
     * 정렬은 기존과 동일하게 takenAt DESC로 유지해 날짜 그룹 순서가 바뀌지 않게 한다.
     */
    @Query("""
        SELECT p
        FROM Photo p
        WHERE p.userId = :userId
          AND p.deleted = false
          AND COALESCE(p.takenAt, p.createdAt) >= :start
          AND COALESCE(p.takenAt, p.createdAt) < :end
        ORDER BY p.takenAt DESC
        """)
    List<Photo> findForPeriod(@Param("userId") Long userId,
                              @Param("start") LocalDateTime start,
                              @Param("end") LocalDateTime end);

    // ✅ 유저의 전체 사진 개수 조회 (삭제되지 않은 것만)
    int countByUserIdAndDeletedIsFalse(Long userId);

    // ✅ 특정 사용자의 모든 사진 (deleted 여부 상관없이) 조회
    List<Photo> findAllByUserId(Long userId);
}
