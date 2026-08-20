package com.nemo.backend.domain.phototag.entity;

import com.nemo.backend.domain.photo.entity.Photo;
import com.nemo.backend.domain.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "photo_tag", uniqueConstraints = @UniqueConstraint(
        name = "uk_photo_tag_photo_user", columnNames = {"photo_id", "tagged_user_id"}))
@Getter
@NoArgsConstructor
public class PhotoTag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "photo_id", nullable = false)
    private Photo photo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tagged_user_id", nullable = false)
    private User taggedUser;

    @Column(name = "position_x", nullable = false)
    private double positionX;

    @Column(name = "position_y", nullable = false)
    private double positionY;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public static PhotoTag create(Photo photo, User taggedUser, double positionX, double positionY) {
        PhotoTag tag = new PhotoTag();
        tag.photo = photo;
        tag.taggedUser = taggedUser;
        tag.positionX = positionX;
        tag.positionY = positionY;
        return tag;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
