package com.nemo.backend.domain.album.entity;

import com.nemo.backend.domain.photo.entity.Photo;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "album_photos")
@Getter
@NoArgsConstructor
public class AlbumPhoto {

    @EmbeddedId
    private AlbumPhotoId id;

    @MapsId("albumId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "album_id", nullable = false)
    private Album album;

    @MapsId("photoId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "photo_id", nullable = false)
    private Photo photo;

    @Column(name = "sequence", nullable = false)
    private int sequence;

    AlbumPhoto(Album album, Photo photo, int sequence) {
        this.id = new AlbumPhotoId(album.getId(), photo.getId());
        this.album = album;
        this.photo = photo;
        this.sequence = sequence;
    }

    void updateSequence(int sequence) {
        this.sequence = sequence;
    }
}
