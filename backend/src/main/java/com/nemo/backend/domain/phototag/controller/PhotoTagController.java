package com.nemo.backend.domain.phototag.controller;

import com.nemo.backend.domain.auth.util.AuthExtractor;
import com.nemo.backend.domain.phototag.dto.CreatePhotoTagRequest;
import com.nemo.backend.domain.phototag.dto.PhotoTagResponse;
import com.nemo.backend.domain.phototag.service.PhotoTagService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping(value = "/api/photos/{photoId}/tags", produces = "application/json; charset=UTF-8")
@RequiredArgsConstructor
public class PhotoTagController {

    private final PhotoTagService photoTagService;
    private final AuthExtractor authExtractor;

    @PostMapping
    public ResponseEntity<PhotoTagResponse> create(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @PathVariable Long photoId,
            @Valid @RequestBody CreatePhotoTagRequest request
    ) {
        Long requesterId = authExtractor.extractUserId(authorizationHeader);
        return ResponseEntity.status(201).body(photoTagService.create(requesterId, photoId, request));
    }

    @GetMapping
    public ResponseEntity<List<PhotoTagResponse>> list(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @PathVariable Long photoId
    ) {
        Long requesterId = authExtractor.extractUserId(authorizationHeader);
        return ResponseEntity.ok(photoTagService.list(requesterId, photoId));
    }

    @DeleteMapping("/{tagId}")
    public ResponseEntity<Void> delete(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @PathVariable Long photoId,
            @PathVariable Long tagId
    ) {
        Long requesterId = authExtractor.extractUserId(authorizationHeader);
        photoTagService.delete(requesterId, photoId, tagId);
        return ResponseEntity.noContent().build();
    }
}
