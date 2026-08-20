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
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PhotoTagService {

    private final PhotoRepository photoRepository;
    private final UserRepository userRepository;
    private final FriendRepository friendRepository;
    private final PhotoTagRepository photoTagRepository;
    private final AlbumPhotoRepository albumPhotoRepository;

    @Transactional
    public PhotoTagResponse create(Long requesterId, Long photoId, CreatePhotoTagRequest request) {
        Photo photo = photoRepository.findByIdAndUserIdAndDeletedIsFalse(photoId, requesterId)
                .orElseThrow(() -> new ApiException(ErrorCode.PHOTO_TAG_FORBIDDEN));

        if (requesterId.equals(request.taggedUserId())) {
            throw new ApiException(ErrorCode.PHOTO_TAG_SELF_NOT_ALLOWED);
        }

        User target = userRepository.findById(request.taggedUserId())
                .orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND));

        boolean acceptedFriend = friendRepository.existsByUserIdAndFriendIdAndStatus(
                requesterId, target.getId(), FriendStatus.ACCEPTED)
                || friendRepository.existsByUserIdAndFriendIdAndStatus(
                target.getId(), requesterId, FriendStatus.ACCEPTED);
        if (!acceptedFriend) {
            throw new ApiException(ErrorCode.PHOTO_TAG_TARGET_NOT_FRIEND);
        }

        validatePosition(request.positionX(), request.positionY());

        if (photoTagRepository.existsByPhotoIdAndTaggedUserId(photoId, target.getId())) {
            throw new ApiException(ErrorCode.PHOTO_TAG_ALREADY_EXISTS);
        }

        try {
            return PhotoTagResponse.from(photoTagRepository.save(
                    PhotoTag.create(photo, target, request.positionX(), request.positionY())));
        } catch (DataIntegrityViolationException exception) {
            throw new ApiException(ErrorCode.PHOTO_TAG_ALREADY_EXISTS);
        }
    }

    public List<PhotoTagResponse> list(Long requesterId, Long photoId) {
        Photo photo = photoRepository.findByIdAndDeletedIsFalse(photoId)
                .orElseThrow(() -> new ApiException(ErrorCode.PHOTO_TAG_FORBIDDEN));
        boolean owner = requesterId.equals(photo.getUserId());
        if (!owner && !albumPhotoRepository.existsAccessiblePhoto(photoId, requesterId)) {
            throw new ApiException(ErrorCode.PHOTO_TAG_FORBIDDEN);
        }
        return photoTagRepository.findAllByPhotoIdOrderByCreatedAtAsc(photoId).stream()
                .map(PhotoTagResponse::from)
                .toList();
    }

    @Transactional
    public void delete(Long requesterId, Long photoId, Long tagId) {
        Photo photo = photoRepository.findByIdAndDeletedIsFalse(photoId)
                .orElseThrow(() -> new ApiException(ErrorCode.PHOTO_TAG_FORBIDDEN));
        PhotoTag tag = photoTagRepository.findByIdAndPhotoId(tagId, photoId)
                .orElseThrow(() -> new ApiException(ErrorCode.PHOTO_TAG_NOT_FOUND));
        boolean owner = requesterId.equals(photo.getUserId());
        boolean taggedUser = requesterId.equals(tag.getTaggedUser().getId());
        if (!owner && !taggedUser) {
            throw new ApiException(ErrorCode.PHOTO_TAG_FORBIDDEN);
        }
        photoTagRepository.delete(tag);
    }

    private void validatePosition(Double positionX, Double positionY) {
        if (positionX == null || positionY == null
                || !Double.isFinite(positionX) || !Double.isFinite(positionY)
                || positionX < 0.0 || positionX > 1.0
                || positionY < 0.0 || positionY > 1.0) {
            throw new ApiException(ErrorCode.PHOTO_TAG_POSITION_INVALID);
        }
    }
}
