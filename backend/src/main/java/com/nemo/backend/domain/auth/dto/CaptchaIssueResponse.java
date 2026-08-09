// backend/src/main/java/com/nemo/backend/domain/auth/dto/CaptchaIssueResponse.java
package com.nemo.backend.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

/**
 * 캡챠 발급 API 응답 DTO.
 *
 * 예시 응답:
 * {
 *   "captchaId": "uuid-1234",
 *   "imageUrl": "https://cdn.nemo.app/captcha/uuid-1234.png",
 *   "expiresIn": 120,
 *   "maxAttempts": 5
 * }
 */
@Getter
public class CaptchaIssueResponse {

    @Schema(description = "캡챠 ID (로그인 시 captchaId로 다시 전송해야 함)",
            example = "8c1f09bb-16bd-42e6-bb4a-394c31c877d2")
    private final String captchaId;

    @Schema(description = "캡챠 이미지 URL",
            example = "https://cdn.nemo.app/captcha/8c1f09bb-16bd-42e6-bb4a-394c31c877d2.png")
    private final String imageUrl;

    @Schema(description = "캡챠 유효 시간(초 단위)", example = "120")
    private final int expiresIn;

    @Schema(description = "해당 캡챠 입력 가능한 최대 시도 횟수", example = "5")
    private final int maxAttempts;

    public CaptchaIssueResponse(String captchaId, String imageUrl, int expiresIn, int maxAttempts) {
        this.captchaId = captchaId;
        this.imageUrl = imageUrl;
        this.expiresIn = expiresIn;
        this.maxAttempts = maxAttempts;
    }
}
