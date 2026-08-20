package com.nemo.backend.domain.album.entity;

import com.nemo.backend.domain.user.entity.User;
import com.nemo.backend.domain.photo.entity.Photo;
import com.nemo.backend.global.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

@Entity
@Table(name = "album")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class Album extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    private String description;

    // ✅ 앨범 썸네일 URL (명세의 coverPhotoUrl)
    @Column(name = "cover_photo_url")
    private String coverPhotoUrl;

    // 소유자 (User)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @OneToMany(mappedBy = "album", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sequence ASC")
    @Builder.Default
    private List<AlbumPhoto> albumPhotos = new ArrayList<>();

    public void addPhoto(Photo photo, int sequence) {
        if (photo == null || containsPhoto(photo.getId())) {
            return;
        }
        albumPhotos.add(new AlbumPhoto(this, photo, sequence));
        albumPhotos.sort(Comparator.comparingInt(AlbumPhoto::getSequence));
    }

    public boolean containsPhoto(Long photoId) {
        return photoId != null && albumPhotos.stream()
                .anyMatch(albumPhoto -> photoId.equals(albumPhoto.getPhoto().getId()));
    }

    public int removePhotos(Set<Long> photoIds) {
        int before = albumPhotos.size();
        albumPhotos.removeIf(albumPhoto -> photoIds.contains(albumPhoto.getPhoto().getId()));
        compactSequences();
        return before - albumPhotos.size();
    }

    public void compactSequences() {
        albumPhotos.sort(Comparator.comparingInt(AlbumPhoto::getSequence));
        for (int index = 0; index < albumPhotos.size(); index++) {
            albumPhotos.get(index).updateSequence(index);
        }
    }

    public List<Photo> orderedAlivePhotos() {
        return albumPhotos.stream()
                .sorted(Comparator.comparingInt(AlbumPhoto::getSequence))
                .map(AlbumPhoto::getPhoto)
                .filter(photo -> !Boolean.TRUE.equals(photo.getDeleted()))
                .toList();
    }

    public void clearPhotos() {
        albumPhotos.clear();
    }

}
