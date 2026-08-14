package com.nemo.backend.domain.album.repository;

import com.nemo.backend.domain.album.entity.AlbumShare;
import com.nemo.backend.domain.album.entity.AlbumShare.Status;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AlbumShareRepository extends JpaRepository<AlbumShare, Long> {

    List<AlbumShare> findByAlbumIdAndActiveTrue(Long albumId);

    Optional<AlbumShare> findByAlbumIdAndUserIdAndStatusAndActiveTrue(
            Long albumId, Long userId, Status status
    );

    List<AlbumShare> findByUserIdAndStatusAndActiveTrue(Long userId, Status status);

    boolean existsByAlbumIdAndUserIdAndActiveTrue(Long albumId, Long userId);

    // ✅ 강퇴된 사용자 재초대/재활성화를 위해 active 여부와 상관없이 조회
    Optional<AlbumShare> findByAlbumIdAndUserId(Long albumId, Long userId);

    Optional<AlbumShare> findByAlbumIdAndUserIdAndActiveTrue(Long albumId, Long userId);

    // ✅ 앨범별 ACCEPTED 멤버만 조회 (공유 멤버 목록용)
    List<AlbumShare> findByAlbumIdAndStatusAndActiveTrue(Long albumId, Status status);

    // ✅ 앨범에 공유 멤버가 존재하는지 여부 (shared 플래그 계산용)
    boolean existsByAlbumIdAndStatusAndActiveTrue(Long albumId, Status status);

    /**
     * 목록 화면용: 주어진 앨범들 중 "현재 다른 사용자와 공유 중"인 앨범 id만 한 번에 가져온다.
     * 예전에는 앨범마다 existsByAlbumIdAndStatusAndActiveTrue()를 호출해 앨범 개수만큼 SELECT가 나갔다.
     */
    @Query("""
        SELECT DISTINCT s.album.id
        FROM AlbumShare s
        WHERE s.album.id IN :albumIds
          AND s.status = :status
          AND s.active = true
        """)
    List<Long> findSharedAlbumIds(@Param("albumIds") Collection<Long> albumIds,
                                  @Param("status") Status status);

    /**
     * 목록 화면용: 내가 공유받은 앨범을 Album까지 한 번에 가져온다(fetch join).
     * fetch join이 없으면 share.getAlbum() 접근 때마다 SELECT가 추가로 나간다.
     */
    @Query("""
        SELECT s
        FROM AlbumShare s
        JOIN FETCH s.album
        WHERE s.user.id = :userId
          AND s.status = :status
          AND s.active = true
        """)
    List<AlbumShare> findAcceptedSharesWithAlbum(@Param("userId") Long userId,
                                                 @Param("status") Status status);

    // ✅ 앨범 삭제 시, 해당 앨범의 공유 정보 전부 제거
    void deleteByAlbumId(Long albumId);

    List<AlbumShare> findAllByUserId(Long userId);

    void deleteAllByAlbumId(Long albumId);
}

