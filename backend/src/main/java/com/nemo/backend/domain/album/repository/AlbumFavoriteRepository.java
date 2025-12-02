package com.nemo.backend.domain.album.repository;

import com.nemo.backend.domain.album.entity.AlbumFavorite;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AlbumFavoriteRepository extends JpaRepository<AlbumFavorite, Long> {

    boolean existsByAlbumIdAndUserId(Long albumId, Long userId);

    void deleteByAlbumIdAndUserId(Long albumId, Long userId);

    List<AlbumFavorite> findByUserId(Long userId);

    // ✅ 앨범 삭제 시, 해당 앨범에 걸린 즐겨찾기 전부 제거
    void deleteByAlbumId(Long albumId);
}
