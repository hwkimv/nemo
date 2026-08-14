// backend/src/main/java/com/nemo/backend/domain/album/repository/AlbumRepository.java
package com.nemo.backend.domain.album.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.nemo.backend.domain.album.dto.AlbumPhotoRow;
import com.nemo.backend.domain.album.entity.Album;

import java.util.Collection;
import java.util.List;

public interface AlbumRepository extends JpaRepository<Album, Long> {

    // ✅ 사용자가 소유한 앨범만 조회
    List<Album> findByUserId(Long userId);

    /**
     * 앨범 목록 화면용: 여러 앨범의 "살아있는 사진" 정보를 한 번의 SELECT로 가져온다.
     *
     * 예전에는 앨범마다 album.getPhotos()를 건드려 앨범 개수만큼 SELECT가 나갔다(N+1).
     * 앨범 100개면 사진 조회만 100번이었고, 여기에 앨범별 공유여부 조회 100번이 더해져 총 202개였다.
     * 이 쿼리는 앨범이 몇 개든 1번이다.
     *
     * 반환 행 수는 사진 수에 비례한다. 그건 목록에 필요한 정보(장수·커버) 때문에 불가피하지만,
     * Photo 엔티티 전체가 아니라 컬럼 4개만 가져오므로 메모리 비용은 훨씬 작다.
     */
    @Query("""
        SELECT new com.nemo.backend.domain.album.dto.AlbumPhotoRow(
                   a.id, p.thumbnailUrl, p.imageUrl, p.createdAt)
        FROM Album a
        JOIN a.photos p
        WHERE a.id IN :albumIds
          AND p.deleted = false
        """)
    List<AlbumPhotoRow> findAlivePhotoRows(@Param("albumIds") Collection<Long> albumIds);
}
