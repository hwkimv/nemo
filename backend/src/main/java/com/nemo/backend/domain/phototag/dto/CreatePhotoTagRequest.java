package com.nemo.backend.domain.phototag.dto;

import jakarta.validation.constraints.NotNull;

public record CreatePhotoTagRequest(
        @NotNull Long taggedUserId,
        @NotNull Double positionX,
        @NotNull Double positionY
) {
}
