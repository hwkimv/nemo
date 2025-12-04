// backend/src/main/java/com/nemo/backend/domain/auth/dto/LoginRequest.java
package com.nemo.backend.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

/**
 * 로그인 요청 DTO
 *
 * 네컷모아 로그인 정책:
 * 1) email (필수)
 * 2) password (필수)
 * 3) captchaId (선택)
 * 4) captchaAnswer (선택)
 *
 * 캡챠 흐름:
 *  - 초기 로그인 → email/password만 전송
 *  - 실패 2회 이상 → 서버가 NEED_CAPTCHA 에러 반환
 *  - 프론트는 /api/auth/captcha 호출 → captchaId + imageUrl 발급
 *  - 이후 로그인 시 captchaId + captchaAnswer 함께 전송
 */
@Getter
@Setter
public class LoginRequest {

    @Schema(description = "사용자 이메일", example = "user@example.com", requiredMode = Schema.RequiredMode.REQUIRED)
    private String email;

    @Schema(description = "사용자 비밀번호", example = "SecurePass123!", requiredMode = Schema.RequiredMode.REQUIRED)
    private String password;

    @Schema(
            description = "발급받은 캡챠 ID (캡챠 필요 시에만 전송)",
            example = "8c1f09bb-16bd-42e6-bb4a-394c31c877d2",
            nullable = true
    )
    private String captchaId;

    @Schema(
            description = "사용자가 캡챠 이미지를 보고 입력한 문자 (캡챠 필요 시에만 전송)",
            example = "A93KF",
            nullable = true
    )
    private String captchaAnswer;
}
