package com.nemo.backend.domain.phototag.service;

import com.nemo.backend.domain.album.repository.AlbumPhotoRepository;
import com.nemo.backend.domain.friend.entity.FriendStatus;
import com.nemo.backend.domain.friend.repository.FriendRepository;
import com.nemo.backend.domain.photo.entity.Photo;
import com.nemo.backend.domain.photo.repository.PhotoRepository;
import com.nemo.backend.domain.phototag.dto.CreatePhotoTagRequest;
import com.nemo.backend.domain.phototag.dto.PhotoTagResponse;
import com.nemo.backend.domain.phototag.entity.PhotoTag;
import com.nemo.backend.domain.phototag.repository.PhotoTagRepository;
import com.nemo.backend.domain.user.entity.User;
import com.nemo.backend.domain.user.repository.UserRepository;
import com.nemo.backend.global.exception.ApiException;
import com.nemo.backend.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PhotoTagServiceTest {

    @Mock PhotoRepository photoRepository;
    @Mock UserRepository userRepository;
    @Mock FriendRepository friendRepository;
    @Mock PhotoTagRepository photoTagRepository;
    @Mock AlbumPhotoRepository albumPhotoRepository;

    private PhotoTagService service;
    private User owner;
    private User friend;
    private Photo photo;

    @BeforeEach
    void setUp() {
        service = new PhotoTagService(photoRepository, userRepository, friendRepository,
                photoTagRepository, albumPhotoRepository);
        owner = user(1L, "owner");
        friend = user(2L, "friend");
        photo = photo(10L, owner.getId(), false);
    }

    @Test
    void ownerCanTagAcceptedFriendAtNormalizedPosition() {
        when(photoRepository.findByIdAndUserIdAndDeletedIsFalse(photo.getId(), owner.getId()))
                .thenReturn(Optional.of(photo));
        when(userRepository.findById(friend.getId())).thenReturn(Optional.of(friend));
        when(friendRepository.existsByUserIdAndFriendIdAndStatus(owner.getId(), friend.getId(), FriendStatus.ACCEPTED))
                .thenReturn(true);
        when(photoTagRepository.existsByPhotoIdAndTaggedUserId(photo.getId(), friend.getId())).thenReturn(false);
        when(photoTagRepository.save(any(PhotoTag.class))).thenAnswer(invocation -> {
            PhotoTag saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 100L);
            return saved;
        });

        PhotoTagResponse response = service.create(owner.getId(), photo.getId(),
                new CreatePhotoTagRequest(friend.getId(), 0.42, 0.35));

        assertThat(response.tagId()).isEqualTo(100L);
        assertThat(response.taggedUserId()).isEqualTo(friend.getId());
        assertThat(response.nickname()).isEqualTo("friend");
        assertThat(response.positionX()).isEqualTo(0.42);
        assertThat(response.positionY()).isEqualTo(0.35);
    }

    @Test
    void nonOwnerCannotProbeTagTargetThroughCreate() {
        when(photoRepository.findByIdAndUserIdAndDeletedIsFalse(photo.getId(), friend.getId()))
                .thenReturn(Optional.empty());

        assertError(() -> service.create(friend.getId(), photo.getId(),
                        new CreatePhotoTagRequest(999L, 0.5, 0.5)),
                ErrorCode.PHOTO_TAG_FORBIDDEN);
    }

    @Test
    void ownerCannotTagSelf() {
        allowOwnerCreate();

        assertError(() -> service.create(owner.getId(), photo.getId(),
                        new CreatePhotoTagRequest(owner.getId(), 0.5, 0.5)),
                ErrorCode.PHOTO_TAG_SELF_NOT_ALLOWED);
    }

    @Test
    void targetMustBeAcceptedFriendInEitherDirection() {
        allowOwnerCreate();
        when(userRepository.findById(friend.getId())).thenReturn(Optional.of(friend));

        assertError(() -> service.create(owner.getId(), photo.getId(),
                        new CreatePhotoTagRequest(friend.getId(), 0.5, 0.5)),
                ErrorCode.PHOTO_TAG_TARGET_NOT_FRIEND);
    }

    @Test
    void positionOutsideImageIsRejected() {
        allowOwnerAndFriend();

        assertError(() -> service.create(owner.getId(), photo.getId(),
                        new CreatePhotoTagRequest(friend.getId(), 1.01, 0.5)),
                ErrorCode.PHOTO_TAG_POSITION_INVALID);
    }

    @Test
    void duplicatePersonOnSamePhotoIsRejected() {
        allowOwnerAndFriend();
        when(photoTagRepository.existsByPhotoIdAndTaggedUserId(photo.getId(), friend.getId())).thenReturn(true);

        assertError(() -> service.create(owner.getId(), photo.getId(),
                        new CreatePhotoTagRequest(friend.getId(), 0.5, 0.5)),
                ErrorCode.PHOTO_TAG_ALREADY_EXISTS);
    }

    @Test
    void acceptedSharedAlbumMemberCanListTags() {
        PhotoTag tag = PhotoTag.create(photo, friend, 0.25, 0.75);
        ReflectionTestUtils.setField(tag, "id", 100L);
        when(photoRepository.findByIdAndDeletedIsFalse(photo.getId())).thenReturn(Optional.of(photo));
        when(albumPhotoRepository.existsAccessiblePhoto(photo.getId(), 3L)).thenReturn(true);
        when(photoTagRepository.findAllByPhotoIdOrderByCreatedAtAsc(photo.getId())).thenReturn(List.of(tag));

        assertThat(service.list(3L, photo.getId()))
                .extracting(PhotoTagResponse::taggedUserId)
                .containsExactly(friend.getId());
    }

    @Test
    void taggedUserCanRemoveOwnTagButUnrelatedUserCannot() {
        PhotoTag tag = PhotoTag.create(photo, friend, 0.25, 0.75);
        ReflectionTestUtils.setField(tag, "id", 100L);
        when(photoRepository.findByIdAndDeletedIsFalse(photo.getId())).thenReturn(Optional.of(photo));
        when(photoTagRepository.findByIdAndPhotoId(tag.getId(), photo.getId())).thenReturn(Optional.of(tag));

        service.delete(friend.getId(), photo.getId(), tag.getId());
        assertError(() -> service.delete(3L, photo.getId(), tag.getId()), ErrorCode.PHOTO_TAG_FORBIDDEN);
    }

    private void allowOwnerCreate() {
        when(photoRepository.findByIdAndUserIdAndDeletedIsFalse(photo.getId(), owner.getId()))
                .thenReturn(Optional.of(photo));
    }

    private void allowOwnerAndFriend() {
        allowOwnerCreate();
        when(userRepository.findById(friend.getId())).thenReturn(Optional.of(friend));
        when(friendRepository.existsByUserIdAndFriendIdAndStatus(owner.getId(), friend.getId(), FriendStatus.ACCEPTED))
                .thenReturn(true);
    }

    private void assertError(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).getErrorCode())
                .isEqualTo(expected);
    }

    private User user(Long id, String nickname) {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", id);
        user.setNickname(nickname);
        return user;
    }

    private Photo photo(Long id, Long ownerId, boolean deleted) {
        Photo photo = new Photo();
        ReflectionTestUtils.setField(photo, "id", id);
        photo.setUserId(ownerId);
        photo.setDeleted(deleted);
        return photo;
    }
}
