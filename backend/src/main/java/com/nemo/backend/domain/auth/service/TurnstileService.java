package com.nemo.backend.domain.auth.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.nemo.backend.global.exception.ApiException;
import com.nemo.backend.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.List;

/**
 * Cloudflare Turnstile 검증 전용 서비스.
 *
 * - 프론트에서 전달한 captchaToken(= Turnstile response)을
 *   Cloudflare 검증 API(/siteverify)에 보내서 확인한다.
 * - AuthService.login()에서 "캡챠가 필요한 상태"일 때만 호출된다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TurnstileService {

    private static final String VERIFY_URL =
            "https://challenges.cloudflare.com/turnstile/v0/siteverify";

    @Value("${turnstile.secretKey}")
    private String secretKey;

    @Value("${turnstile.enabled:true}")
    private boolean enabled;

    private final RestTemplate restTemplate;

    /**
     * Turnstile 토큰 검증.
     *
     * @param token    프론트에서 전달한 captchaToken
     * @param remoteIp (선택) 클라이언트 IP. 굳이 안 보내도 되면 null 가능.
     *
     * @throws ApiException INVALID_CAPTCHA  - 토큰이 유효하지 않을 때
     * @throws ApiException INTERNAL_ERROR   - Turnstile 서버 통신 실패 등
     */
    public void verifyToken(String token, String remoteIp) {

        // 개발 중에 임시로 끄고 싶을 때를 위한 토글
        if (!enabled) {
            log.debug("[Turnstile] disabled - skip verification");
            return;
        }

        if (token == null || token.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_CAPTCHA, "캡챠 토큰이 비어 있습니다.");
        }

        try {
            // 1) form-data body 구성
            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("secret", secretKey);
            body.add("response", token);
            if (remoteIp != null && !remoteIp.isBlank()) {
                body.add("remoteip", remoteIp);
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            HttpEntity<MultiValueMap<String, String>> request =
                    new HttpEntity<>(body, headers);

            // 2) Turnstile /siteverify 호출
            ResponseEntity<TurnstileResponse> response =
                    restTemplate.postForEntity(VERIFY_URL, request, TurnstileResponse.class);

            TurnstileResponse res = response.getBody();

            // 3) 응답 검증
            if (res == null || !res.isSuccess()) {
                log.warn("[Turnstile] verification failed. res={}", res);
                throw new ApiException(
                        ErrorCode.INVALID_CAPTCHA,
                        "캡챠 검증에 실패했습니다."
                );
            }

        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("[Turnstile] verification error", e);
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "캡챠 서버 통신 중 오류가 발생했습니다.");
        }
    }

    /**
     * Cloudflare Turnstile /siteverify 응답 DTO
     */
    @Getter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TurnstileResponse {
        private boolean success;

        @JsonProperty("error-codes")
        private List<String> errorCodes;

        @Override
        public String toString() {
            return "TurnstileResponse{" +
                    "success=" + success +
                    ", errorCodes=" + errorCodes +
                    '}';
        }
    }
}
