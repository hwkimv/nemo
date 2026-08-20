package com.nemo.backend.domain.album.service;

import com.nemo.backend.domain.album.dto.AlbumDetailResponse;
import com.nemo.backend.domain.album.dto.AlbumDownloadUrlsResponse;
import com.nemo.backend.domain.album.dto.CreateAlbumRequest;
import com.nemo.backend.domain.album.entity.Album;
import com.nemo.backend.domain.album.entity.AlbumPhoto;
import com.nemo.backend.domain.album.repository.AlbumRepository;
import com.nemo.backend.domain.map.util.NaverApiClient;
import com.nemo.backend.domain.photo.entity.Photo;
import com.nemo.backend.domain.photo.repository.PhotoRepository;
import com.nemo.backend.domain.user.entity.User;
import com.nemo.backend.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.S3Client;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("dev")
class AlbumPhotoSequenceIntegrationTest {

    @MockitoBean
    private S3Client s3Client;
    @MockitoBean
    private NaverApiClient naverApiClient;

    @Autowired
    private AlbumService albumService;
    @Autowired
    private AlbumRepository albumRepository;
    @Autowired
    private PhotoRepository photoRepository;
    @Autowired
    private UserRepository userRepository;

    private User owner;
    private Photo first;
    private Photo second;
    private Photo third;

    @BeforeEach
    void setUp() {
        owner = new User();
        owner.setEmail(UUID.randomUUID() + "@nemo.test");
        owner.setPassword("{noop}irrelevant");
        owner.setNickname("owner");
        owner.setProvider("local");
        owner = userRepository.save(owner);
        first = photo("first");
        second = photo("second");
        third = photo("third");
    }

    @Test
    @Transactional
    void createAndDetailPreserveRequestOrderInStoredSequence() {
        AlbumDetailResponse created = albumService.createAlbum(owner.getId(), CreateAlbumRequest.builder()
                .title("ordered")
                .photoIdList(List.of(second.getId(), first.getId()))
                .build());

        Album stored = albumRepository.findById(created.getAlbumId()).orElseThrow();
        assertThat(stored.getAlbumPhotos())
                .extracting(ap -> ap.getPhoto().getId(), AlbumPhoto::getSequence)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(second.getId(), 0),
                        org.assertj.core.groups.Tuple.tuple(first.getId(), 1)
                );
        assertThat(albumService.getAlbum(owner.getId(), created.getAlbumId()).getPhotoList())
                .extracting(AlbumDetailResponse.PhotoSummary::getPhotoId)
                .containsExactly(second.getId(), first.getId());
    }

    @Test
    void addAppendsAndRemoveCompactsDownloadSequence() {
        AlbumDetailResponse created = albumService.createAlbum(owner.getId(), CreateAlbumRequest.builder()
                .title("mutated")
                .photoIdList(List.of(first.getId(), second.getId()))
                .build());

        assertThat(albumService.addPhotos(owner.getId(), created.getAlbumId(),
                List.of(third.getId(), third.getId()))).isEqualTo(1);
        assertThat(albumService.removePhotos(owner.getId(), created.getAlbumId(),
                List.of(second.getId()))).isEqualTo(1);

        AlbumDownloadUrlsResponse response = albumService.getAlbumDownloadUrls(owner.getId(), created.getAlbumId());
        assertThat(response.getPhotos())
                .extracting(p -> p.getPhotoId(), p -> p.getSequence())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(first.getId(), 0),
                        org.assertj.core.groups.Tuple.tuple(third.getId(), 1)
                );
    }

    private Photo photo(String name) {
        Photo photo = new Photo();
        photo.setUserId(owner.getId());
        photo.setImageUrl("https://example.test/" + name + ".jpg");
        photo.setThumbnailUrl("https://example.test/" + name + "-thumb.jpg");
        photo.setDeleted(false);
        return photoRepository.save(photo);
    }
}
