package com.nemo.backend.domain.phototag.service;

import com.nemo.backend.domain.album.entity.Album;
import com.nemo.backend.domain.album.entity.AlbumShare;
import com.nemo.backend.domain.album.repository.AlbumRepository;
import com.nemo.backend.domain.album.repository.AlbumShareRepository;
import com.nemo.backend.domain.friend.entity.Friend;
import com.nemo.backend.domain.friend.entity.FriendStatus;
import com.nemo.backend.domain.friend.repository.FriendRepository;
import com.nemo.backend.domain.map.util.NaverApiClient;
import com.nemo.backend.domain.photo.entity.Photo;
import com.nemo.backend.domain.photo.repository.PhotoRepository;
import com.nemo.backend.domain.phototag.dto.CreatePhotoTagRequest;
import com.nemo.backend.domain.phototag.dto.PhotoTagResponse;
import com.nemo.backend.domain.phototag.repository.PhotoTagRepository;
import com.nemo.backend.domain.user.entity.User;
import com.nemo.backend.domain.user.repository.UserRepository;
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
@Transactional
class PhotoTagIntegrationTest {

    @MockitoBean S3Client s3Client;
    @MockitoBean NaverApiClient naverApiClient;

    @Autowired PhotoTagService photoTagService;
    @Autowired PhotoTagRepository photoTagRepository;
    @Autowired PhotoRepository photoRepository;
    @Autowired UserRepository userRepository;
    @Autowired FriendRepository friendRepository;
    @Autowired AlbumRepository albumRepository;
    @Autowired AlbumShareRepository albumShareRepository;

    @Test
    void persistedTagIsVisibleToAcceptedSharedMemberAndRemovableByTaggedUser() {
        User owner = user("owner");
        User tagged = user("tagged");
        User viewer = user("viewer");
        friendRepository.save(Friend.builder()
                .user(owner).friend(tagged).status(FriendStatus.ACCEPTED).build());

        Photo photo = new Photo();
        photo.setUserId(owner.getId());
        photo.setImageUrl("https://example.test/photo.jpg");
        photo.setDeleted(false);
        photo = photoRepository.save(photo);

        Album album = new Album();
        album.setName("shared");
        album.setUser(owner);
        album.addPhoto(photo, 0);
        album = albumRepository.save(album);
        albumShareRepository.save(AlbumShare.builder()
                .album(album)
                .user(viewer)
                .role(AlbumShare.Role.VIEWER)
                .status(AlbumShare.Status.ACCEPTED)
                .active(true)
                .build());

        PhotoTagResponse created = photoTagService.create(owner.getId(), photo.getId(),
                new CreatePhotoTagRequest(tagged.getId(), 0.2, 0.8));
        List<PhotoTagResponse> visible = photoTagService.list(viewer.getId(), photo.getId());
        photoTagService.delete(tagged.getId(), photo.getId(), created.tagId());

        assertThat(visible).extracting(PhotoTagResponse::tagId).containsExactly(created.tagId());
        assertThat(photoTagRepository.existsById(created.tagId())).isFalse();
    }

    private User user(String nickname) {
        User user = new User();
        user.setEmail(nickname + "-" + UUID.randomUUID() + "@nemo.test");
        user.setPassword("{noop}irrelevant");
        user.setNickname(nickname);
        user.setProvider("local");
        return userRepository.save(user);
    }
}
