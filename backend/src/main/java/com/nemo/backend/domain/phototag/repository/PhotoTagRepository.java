package com.nemo.backend.domain.phototag.repository;

import com.nemo.backend.domain.phototag.entity.PhotoTag;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PhotoTagRepository extends JpaRepository<PhotoTag, Long> {

    boolean existsByPhotoIdAndTaggedUserId(Long photoId, Long taggedUserId);

    List<PhotoTag> findAllByPhotoIdOrderByCreatedAtAsc(Long photoId);

    Optional<PhotoTag> findByIdAndPhotoId(Long id, Long photoId);
}
