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
 * 3) captchaToken (선택, Turnstile 토큰)
 *
 * 🔐 캡챠(Cloudflare Turnstile) 흐름:
 *
 *  - 초기 로그인(실패 0~1회) →
 *      email / password 만 전송
 *
 *  - 같은 계정 기준으로 비밀번호를 여러 번 틀리면
 *      서버에서 NEED_CAPTCHA 에러를 반환
 *
 *  - 프론트는 로그인 화면에 Cloudflare Turnstile 위젯을 띄우고
 *      위젯에서 받은 토큰을 captchaToken 에 넣어서 다시 로그인 요청
 *
 *  - 서버(AuthService.login)는 captchaToken 을 Turnstile 검증 API
 *      (/siteverify)에 보내서 유효한 토큰인지 확인한 뒤
 *      비밀번호 검증을 진행한다.
 */
@Getter
@Setter
public class LoginRequest {

    @Schema(
            description = "사용자 이메일",
            example = "user@example.com",
            requiredMode = Schema.RequiredMode.REQUIRED
    )
    private String email;

    @Schema(
            description = "사용자 비밀번호",
            example = "SecurePass123!",
            requiredMode = Schema.RequiredMode.REQUIRED
    )
    private String password;

    @Schema(
            description = "Cloudflare Turnstile 위젯에서 발급받은 캡챠 토큰 (여러 번 실패 시에만 전송)",
            example = "1x0000000000000000000000000000000AA",
            nullable = true
    )
    private String captchaToken;
}
