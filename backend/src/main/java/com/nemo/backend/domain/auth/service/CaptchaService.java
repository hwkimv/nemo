package com.nemo.backend.domain.auth.service;

import com.nemo.backend.domain.auth.dto.CaptchaIssueResponse;
import com.nemo.backend.global.exception.ApiException;
import com.nemo.backend.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 로그인 캡챠 발급/검증을 담당하는 서비스.
 *
 * - GET /api/auth/captcha 에서 issueCaptcha() 호출
 * - POST /api/users/login 에서 validate(captchaId, answer) 호출 예정
 *
 * 지금은 인메모리 Map 기반으로 구현해두고,
 * 나중에 Redis 같은 외부 캐시로 옮기기 쉽게 설계함.
 */
@Service
@RequiredArgsConstructor
public class CaptchaService {

    /**
     * 내부 캡챠 저장소
     *  - key: captchaId
     *  - value: CaptchaInfo (정답, 만료시간, 남은 시도 수)
     *
     *  실제 서비스에서는 Redis로 교체하는 것을 추천.
     */
    private final Map<String, CaptchaInfo> store = new ConcurrentHashMap<>();

    /** 캡챠 유효 시간 (초) - 예: 120초 = 2분 */
    private static final int EXPIRE_SECONDS = 120;

    /** 한 캡챠에 대해 사용자가 시도할 수 있는 최대 횟수 */
    private static final int MAX_ATTEMPTS = 5;

    /**
     * 로그인 시도 횟수 제한 정책과는 별개로,
     * 캡챠 자체의 요청/사용에 대한 보안 정책을 나누기 쉽도록 분리.
     */

    // =========================================================
    // 1) 캡챠 발급
    // =========================================================

    /**
     * 새로운 캡챠를 발급합니다.
     *
     * - 랜덤 captchaId 생성 (UUID)
     * - 랜덤 정답 문자열 생성 (영문 대문자+숫자 조합)
     * - 만료 시간, 남은 시도 수 설정
     * - 이미지 URL은 실제 구현에 따라 S3/CDN 경로로 변경 가능
     */
    public CaptchaIssueResponse issueCaptcha() {
        // 1) 캡챠 ID & 정답 생성
        String captchaId = UUID.randomUUID().toString();
        String answer = generateRandomAnswer(5); // 5글자 캡챠

        // 2) 만료 시각 계산
        LocalDateTime expiresAt = LocalDateTime.now().plusSeconds(EXPIRE_SECONDS);

        // 3) 이미지 URL 구성 (실제 구현에서는 캡챠 이미지를 만들어 업로드해야 함)
        //    여기서는 예시로만 작성. 프론트/인프라에 맞게 수정 필요.
        String imageUrl = "https://cdn.nemo.app/captcha/" + captchaId + ".png";

        // 4) 메모리에 저장
        CaptchaInfo info = new CaptchaInfo(answer, expiresAt, MAX_ATTEMPTS);
        store.put(captchaId, info);

        // 5) 프론트로 내려줄 응답 DTO
        return new CaptchaIssueResponse(
                captchaId,
                imageUrl,
                EXPIRE_SECONDS,
                MAX_ATTEMPTS
        );
    }

    // =========================================================
    // 2) 캡챠 검증
    // =========================================================

    /**
     * 로그인 시 사용자가 입력한 캡챠를 검증합니다.
     *
     * @param captchaId    발급받았던 캡챠 ID
     * @param userAnswer   사용자가 입력한 문자열
     *
     * 검증 실패 시:
     * - 만료됨 → CODE_EXPIRED
     * - 시도 초과 → ATTEMPTS_EXCEEDED
     * - 정답 불일치 → INVALID_CAPTCHA
     *
     * 검증 성공 시:
     * - store에서 해당 captchaId 제거 (1회성 사용)
     */
    public void validate(String captchaId, String userAnswer) {
        if (captchaId == null || captchaId.isBlank()
                || userAnswer == null || userAnswer.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_CAPTCHA, "캡챠 ID 또는 캡챠 값이 비어 있습니다.");
        }

        CaptchaInfo info = store.get(captchaId);

        // 존재하지 않거나 이미 삭제된 경우
        if (info == null) {
            throw new ApiException(ErrorCode.INVALID_CAPTCHA, "유효하지 않은 캡챠입니다.");
        }

        // 만료 시간 체크
        if (info.isExpired()) {
            store.remove(captchaId); // 만료되었으므로 정리
            throw new ApiException(ErrorCode.CODE_EXPIRED, "캡챠가 만료되었습니다. 새로 받아주세요.");
        }

        // 시도 횟수 초과 체크
        if (info.getRemainingAttempts() <= 0) {
            store.remove(captchaId);
            throw new ApiException(ErrorCode.ATTEMPTS_EXCEEDED, "캡챠 입력 시도 횟수를 초과했습니다.");
        }

        // 정답 비교 (대소문자 구분 X)
        if (!info.matches(userAnswer)) {
            info.decreaseAttempts();
            // 아직 시도가 남았어도 "틀렸다"는 의미로 INVALID_CAPTCHA 던짐
            throw new ApiException(ErrorCode.INVALID_CAPTCHA, "캡챠 문자가 올바르지 않습니다.");
        }

        // 여기까지 왔으면 성공 → 1회용이므로 제거
        store.remove(captchaId);
    }

    // =========================================================
    // 3) 내부 유틸 메서드 및 클래스
    // =========================================================

    /**
     * 영문 대문자 + 숫자로 구성된 랜덤 문자열 생성.
     *
     * @param length 생성할 문자열 길이
     */
    private String generateRandomAnswer(int length) {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // 헷갈리는 문자(O,0,I,1 등 제외 가능)
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < length; i++) {
            int idx = (int) (Math.random() * chars.length());
            sb.append(chars.charAt(idx));
        }
        return sb.toString();
    }

    /**
     * 캡챠 한 건에 대한 상태 정보.
     *
     * - answer: 정답 문자열
     * - expiresAt: 만료 시각
     * - remainingAttempts: 남은 입력 가능 횟수
     */
    private static class CaptchaInfo {
        private final String answer;
        private final LocalDateTime expiresAt;
        private int remainingAttempts;

        private CaptchaInfo(String answer, LocalDateTime expiresAt, int remainingAttempts) {
            this.answer = answer;
            this.expiresAt = expiresAt;
            this.remainingAttempts = remainingAttempts;
        }

        /** 만료 여부 검사 */
        private boolean isExpired() {
            return expiresAt.isBefore(LocalDateTime.now());
        }

        /** 정답 비교 (대소문자 무시) */
        private boolean matches(String userInput) {
            return answer.equalsIgnoreCase(userInput.trim());
        }

        /** 남은 시도 수 1 감소 */
        private void decreaseAttempts() {
            this.remainingAttempts--;
        }

        private int getRemainingAttempts() {
            return remainingAttempts;
        }
    }
}
