package com.nemo.backend.domain.phototag.dto;

import com.nemo.backend.domain.phototag.entity.PhotoTag;

import java.time.LocalDateTime;

public record PhotoTagResponse(
        Long tagId,
        Long taggedUserId,
        String nickname,
        double positionX,
        double positionY,
        LocalDateTime createdAt
) {
    public static PhotoTagResponse from(PhotoTag tag) {
        return new PhotoTagResponse(
                tag.getId(),
                tag.getTaggedUser().getId(),
                tag.getTaggedUser().getNickname(),
                tag.getPositionX(),
                tag.getPositionY(),
                tag.getCreatedAt()
        );
    }
}
