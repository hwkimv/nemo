package com.nemo.backend.domain.phototag.controller;

import com.nemo.backend.domain.auth.util.AuthExtractor;
import com.nemo.backend.domain.phototag.dto.CreatePhotoTagRequest;
import com.nemo.backend.domain.phototag.dto.PhotoTagResponse;
import com.nemo.backend.domain.phototag.service.PhotoTagService;
import com.nemo.backend.global.exception.ApiException;
import com.nemo.backend.global.exception.ErrorCode;
import com.nemo.backend.global.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PhotoTagControllerTest {

    @Mock PhotoTagService photoTagService;
    @Mock AuthExtractor authExtractor;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        PhotoTagController controller = new PhotoTagController(photoTagService, authExtractor);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        lenient().when(authExtractor.extractUserId("Bearer token")).thenReturn(1L);
    }

    @Test
    void postCreatesTagAndReturnsContractFields() throws Exception {
        when(photoTagService.create(any(), any(), any(CreatePhotoTagRequest.class)))
                .thenReturn(new PhotoTagResponse(100L, 2L, "friend", 0.42, 0.35,
                        LocalDateTime.of(2026, 8, 20, 12, 0)));

        mockMvc.perform(post("/api/photos/10/tags")
                        .header("Authorization", "Bearer token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taggedUserId":2,"positionX":0.42,"positionY":0.35}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tagId").value(100))
                .andExpect(jsonPath("$.taggedUserId").value(2))
                .andExpect(jsonPath("$.nickname").value("friend"))
                .andExpect(jsonPath("$.positionX").value(0.42))
                .andExpect(jsonPath("$.positionY").value(0.35));
    }

    @Test
    void getListsTagsAndDeleteReturnsNoContent() throws Exception {
        when(photoTagService.list(1L, 10L)).thenReturn(List.of(
                new PhotoTagResponse(100L, 2L, "friend", 0.42, 0.35, null)));

        mockMvc.perform(get("/api/photos/10/tags").header("Authorization", "Bearer token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].tagId").value(100));

        mockMvc.perform(delete("/api/photos/10/tags/100").header("Authorization", "Bearer token"))
                .andExpect(status().isNoContent());
    }

    @Test
    void missingCreateFieldReturnsValidationError() throws Exception {
        mockMvc.perform(post("/api/photos/10/tags")
                        .header("Authorization", "Bearer token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taggedUserId\":2,\"positionX\":0.42}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
    }

    @Test
    void serviceErrorUsesPhotoTagErrorCode() throws Exception {
        doThrow(new ApiException(ErrorCode.PHOTO_TAG_FORBIDDEN))
                .when(photoTagService).delete(1L, 10L, 100L);

        mockMvc.perform(delete("/api/photos/10/tags/100").header("Authorization", "Bearer token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("PHOTO_TAG_FORBIDDEN"));
    }
}
