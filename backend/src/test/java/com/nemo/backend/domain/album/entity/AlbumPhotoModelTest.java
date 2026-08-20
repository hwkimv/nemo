package com.nemo.backend.domain.album.entity;

import com.nemo.backend.domain.photo.entity.Photo;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AlbumPhotoModelTest {

    @Test
    void requestedOrderIsPreservedByStoredSequence() {
        Album album = new Album();
        Photo second = photo(2L, false);
        Photo first = photo(1L, false);

        album.addPhoto(second, 0);
        album.addPhoto(first, 1);

        assertThat(album.getAlbumPhotos())
                .extracting(AlbumPhoto::getPhoto, AlbumPhoto::getSequence)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(second, 0),
                        org.assertj.core.groups.Tuple.tuple(first, 1)
                );
        assertThat(album.orderedAlivePhotos()).containsExactly(second, first);
    }

    @Test
    void duplicatePhotoIdIsNotAddedTwice() {
        Album album = new Album();
        Photo original = photo(1L, false);
        Photo sameId = photo(1L, false);

        album.addPhoto(original, 0);
        album.addPhoto(sameId, 1);

        assertThat(album.getAlbumPhotos()).hasSize(1);
        assertThat(album.orderedAlivePhotos()).containsExactly(original);
    }

    @Test
    void removingPhotosCompactsRemainingSequences() {
        Album album = new Album();
        Photo first = photo(1L, false);
        Photo second = photo(2L, false);
        Photo third = photo(3L, false);
        album.addPhoto(first, 0);
        album.addPhoto(second, 1);
        album.addPhoto(third, 2);

        int removed = album.removePhotos(Set.of(2L));

        assertThat(removed).isEqualTo(1);
        assertThat(album.getAlbumPhotos())
                .extracting(AlbumPhoto::getSequence)
                .containsExactly(0, 1);
        assertThat(album.orderedAlivePhotos()).containsExactly(first, third);
    }

    @Test
    void softDeletedPhotosAreExcludedWithoutChangingStoredMembership() {
        Album album = new Album();
        Photo alive = photo(1L, false);
        Photo deleted = photo(2L, true);
        album.addPhoto(alive, 0);
        album.addPhoto(deleted, 1);

        assertThat(album.orderedAlivePhotos()).containsExactly(alive);
        assertThat(album.getAlbumPhotos()).hasSize(2);
    }

    private Photo photo(Long id, boolean deleted) {
        Photo photo = new Photo();
        ReflectionTestUtils.setField(photo, "id", id);
        photo.setDeleted(deleted);
        return photo;
    }
}
