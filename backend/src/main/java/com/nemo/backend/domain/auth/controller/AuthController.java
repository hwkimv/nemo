// backend/src/main/java/com/nemo/backend/domain/auth/controller/AuthController.java
package com.nemo.backend.domain.auth.controller;

import com.nemo.backend.domain.auth.dto.CaptchaIssueResponse;
import com.nemo.backend.domain.auth.dto.RefreshRequest;
import com.nemo.backend.domain.auth.dto.RefreshResponse;
import com.nemo.backend.domain.auth.service.AuthService;
import com.nemo.backend.domain.auth.service.CaptchaService;
import com.nemo.backend.global.exception.ApiException;
import com.nemo.backend.global.exception.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(
        value = "/api/auth",
        produces = "application/json; charset=UTF-8"
)
@RequiredArgsConstructor
@Tag(name = "Auth", description = "인증 관련 API")
public class AuthController {

    private final AuthService authService;

    /** 🔐 로그인 캡챠 발급 서비스 */
    private final CaptchaService captchaService;

    // =========================================================
    // 1) JWT Refresh Token 재발급
    // =========================================================
    @Operation(
            summary = "JWT 재발급",
            description = "리프레시 토큰을 사용해 새로운 액세스 토큰을 발급합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "재발급 성공",
                    content = @Content(schema = @Schema(implementation = RefreshResponse.class))),
            @ApiResponse(responseCode = "400", description = "TOKEN_REQUIRED / 잘못된 요청",
                    content = @Content(schema = @Schema(implementation = ErrorBody.class))),
            @ApiResponse(responseCode = "401", description = "INVALID_TOKEN",
                    content = @Content(schema = @Schema(implementation = ErrorBody.class)))
    })
    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@RequestBody RefreshRequest request) {
        try {
            RefreshResponse res = authService.refresh(request);
            return ResponseEntity.ok(res);
        } catch (ApiException e) {
            ErrorCode code = e.getErrorCode();
            return ResponseEntity
                    .status(code.getStatus())
                    .body(new ErrorBody(code.getCode(), e.getMessage()));
        }
    }

    // =========================================================
    // 2) 로그인용 캡챠 발급
    // =========================================================
    @Operation(
            summary = "로그인용 캡챠 발급",
            description = """
                    로그인 실패 횟수가 기준(예: 2회) 이상이면 서버가 NEED_CAPTCHA 에러를 반환하고,
                    프론트는 이 API를 호출해서 captchaId + imageUrl을 발급받습니다.
                    이후 로그인 시에는 email/password와 함께
                    captchaId + captchaAnswer를 같이 보내야 합니다.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "캡챠 발급 성공",
                    content = @Content(schema = @Schema(implementation = CaptchaIssueResponse.class))),
            @ApiResponse(responseCode = "429", description = "TOO_MANY_CAPTCHA_REQUESTS",
                    content = @Content(schema = @Schema(implementation = ErrorBody.class))),
            @ApiResponse(responseCode = "500", description = "서버 오류",
                    content = @Content(schema = @Schema(implementation = ErrorBody.class)))
    })
    @GetMapping("/captcha")
    public ResponseEntity<?> issueCaptcha() {
        try {
            CaptchaIssueResponse res = captchaService.issueCaptcha();
            return ResponseEntity.ok(res);
        } catch (ApiException e) {
            ErrorCode code = e.getErrorCode();
            return ResponseEntity
                    .status(code.getStatus())
                    .body(new ErrorBody(code.getCode(), e.getMessage()));
        }
    }

    /** 공통 에러 응답 형태 */
    @Schema(description = "에러 응답 바디")
    private record ErrorBody(
            @Schema(description = "에러 코드", example = "INVALID_TOKEN")
            String error,
            @Schema(description = "에러 메시지", example = "리프레시 토큰이 유효하지 않습니다.")
            String message
    ) {}
}
