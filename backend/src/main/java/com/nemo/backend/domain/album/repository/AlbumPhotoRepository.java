package com.nemo.backend.domain.album.repository;

import com.nemo.backend.domain.album.entity.AlbumPhoto;
import com.nemo.backend.domain.album.entity.AlbumPhotoId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AlbumPhotoRepository extends JpaRepository<AlbumPhoto, AlbumPhotoId> {

    @Query("""
        SELECT CASE WHEN COUNT(ap) > 0 THEN true ELSE false END
        FROM AlbumPhoto ap
        JOIN AlbumShare share ON share.album = ap.album
        WHERE ap.photo.id = :photoId
          AND ap.photo.deleted = false
          AND share.user.id = :userId
          AND share.status = com.nemo.backend.domain.album.entity.AlbumShare.Status.ACCEPTED
          AND share.active = true
        """)
    boolean existsAccessiblePhoto(@Param("photoId") Long photoId, @Param("userId") Long userId);
}
