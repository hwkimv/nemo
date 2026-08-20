package com.nemo.backend.domain.album.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Embeddable
@Getter
@EqualsAndHashCode
@NoArgsConstructor
@AllArgsConstructor
public class AlbumPhotoId implements Serializable {

    @Column(name = "album_id")
    private Long albumId;

    @Column(name = "photo_id")
    private Long photoId;
}
