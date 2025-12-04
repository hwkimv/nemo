// backend/src/main/java/com/nemo/backend/domain/auth/service/PasswordResetService.java
package com.nemo.backend.domain.auth.service;

import com.nemo.backend.domain.auth.dto.PasswordCodeRequest;
import com.nemo.backend.domain.auth.dto.PasswordCodeVerifyRequest;
import com.nemo.backend.domain.auth.dto.PasswordResetRequest;
import com.nemo.backend.domain.user.entity.User;
import com.nemo.backend.domain.user.repository.UserRepository;
import com.nemo.backend.global.exception.ApiException;
import com.nemo.backend.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 🔐 비밀번호 분실/재설정 플로우 서비스
 *
 * 플로우 요약:
 *  1) 사용자 이메일로 인증코드 발송
 *     - sendPasswordResetCode()
 *  2) 이메일 + 인증코드 검증 후, resetToken 발급
 *     - verifyCodeAndIssueToken()
 *  3) resetToken으로 새 비밀번호 설정
 *     - resetPassword()
 *
 * 보안 포인트:
 *  - 이메일 존재 여부를 직접 노출하지 않음 (계정 유추 방지)
 *  - resetToken은 메모리(또는 향후 Redis)에서 TTL 기반으로 관리
 *  - 비밀번호 재설정 성공 시 로그인 실패 기록/잠금 상태 초기화
 */
@Service
@RequiredArgsConstructor
public class PasswordResetService {

    private final EmailVerificationService emailVerificationService;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    /** resetToken 유효 시간 (기본 10분) */
    private static final Duration RESET_TOKEN_TTL = Duration.ofMinutes(10);

    /**
     * In-memory resetToken 저장소
     *  - key: resetToken (rt_xxx)
     *  - value: 이메일/만료시간/사용여부
     *
     * 👉 운영환경에서는 Redis 등 외부 캐시 스토리지로 교체 가능
     */
    private final Map<String, ResetTokenInfo> resetTokens = new ConcurrentHashMap<>();

    /**
     * resetToken 에 대한 메타 정보
     */
    @Getter
    private static class ResetTokenInfo {
        /** 토큰이 발급된 이메일 */
        private final String email;

        /** 토큰 만료 시각 */
        private final LocalDateTime expiresAt;

        /** 이미 사용되었는지 여부 (1회용) */
        private boolean used;

        ResetTokenInfo(String email, LocalDateTime expiresAt) {
            this.email = email;
            this.expiresAt = expiresAt;
            this.used = false;
        }

        /** 현재 시각 기준 만료 여부 */
        boolean isExpired() {
            return expiresAt.isBefore(LocalDateTime.now());
        }

        /** 사용 처리 (한 번 쓰면 다시 못 쓰게) */
        void markUsed() { this.used = true; }

        /** 사용 가능 여부 (만료 X && used=false) */
        boolean isUsable() { return !used && !isExpired(); }
    }

    // ======================================================
    // 1) 비밀번호 분실: 인증코드 발송
    // ======================================================

    /**
     * 사용자가 "비밀번호를 잊어버렸어요" 했을 때 호출되는 단계.
     *
     * - 입력한 이메일로 6자리 인증코드를 발송한다.
     * - 가입 여부와 상관없이 "성공" 응답을 줘서
     *   이메일 존재 여부를 추론할 수 없게 만든다.
     */
    public void sendPasswordResetCode(PasswordCodeRequest req) {
        if (req == null || req.email() == null) {
            throw new ApiException(ErrorCode.INVALID_EMAIL_FORMAT);
        }

        // 내부적으로 EmailVerificationService가 rate limit, 형식 검증 등을 처리
        emailVerificationService.sendVerificationCode(req.email());
    }

    // ======================================================
    // 2) 인증코드 검증 → resetToken 발급
    // ======================================================

    /**
     * 이메일 + 인증코드를 검증하고,
     * 성공 시 비밀번호 재설정에 사용할 1회용 resetToken을 발급한다.
     */
    public ResetTokenResult verifyCodeAndIssueToken(PasswordCodeVerifyRequest req) {
        EmailVerificationService.VerifyResult result =
                emailVerificationService.verifyCodeWithReason(req.email(), req.code());

        return switch (result) {
            case SUCCESS -> {
                // ✅ 인증 성공 시: resetToken 생성
                String token = "rt_" + UUID.randomUUID();
                LocalDateTime now = LocalDateTime.now();

                // 메모리에 토큰 저장 (이메일 + 만료 시간)
                resetTokens.put(token, new ResetTokenInfo(req.email(), now.plus(RESET_TOKEN_TTL)));

                yield new ResetTokenResult(true, token, (int) RESET_TOKEN_TTL.getSeconds());
            }
            case CODE_MISMATCH -> throw new ApiException(ErrorCode.CODE_MISMATCH);
            case CODE_EXPIRED -> throw new ApiException(ErrorCode.CODE_EXPIRED);
            case ATTEMPTS_EXCEEDED -> throw new ApiException(ErrorCode.ATTEMPTS_EXCEEDED);
        };
    }

    // ======================================================
    // 3) resetToken 으로 새 비밀번호 설정
    // ======================================================

    /**
     * 3단계: resetToken을 사용해 실제로 비밀번호를 변경한다.
     *
     * 주요 로직:
     *  - resetToken 유효성 검증 (존재/만료/이미 사용 여부)
     *  - 비밀번호 정책 검증 (길이/조합 등)
     *  - 사용자 조회 후 새 비밀번호로 업데이트
     *  - ✅ 로그인 실패 기록 / 계정 잠금 상태 초기화
     *  - resetToken을 1회용으로 마킹
     */
    public void resetPassword(PasswordResetRequest req) {
        // 0) 기본 검증
        if (req == null
                || req.resetToken() == null
                || req.resetToken().isBlank()) {
            throw new ApiException(ErrorCode.INVALID_RESET_TOKEN);
        }

        if (!req.newPassword().equals(req.confirmPassword())) {
            throw new ApiException(ErrorCode.PASSWORD_CONFIRM_MISMATCH);
        }

        if (!isValidPassword(req.newPassword())) {
            throw new ApiException(ErrorCode.PASSWORD_POLICY_VIOLATION);
        }

        // 1) resetToken 검증
        ResetTokenInfo info = resetTokens.get(req.resetToken());
        if (info == null || !info.isUsable()) {
            // - 존재하지 않거나
            // - 이미 사용되었거나
            // - 만료된 토큰인 경우
            throw new ApiException(ErrorCode.INVALID_RESET_TOKEN);
        }

        String email = info.getEmail();

        // 2) 사용자 조회
        //    (존재하지 않는 이메일에 대해서도 INVALID_RESET_TOKEN 으로 통일)
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_RESET_TOKEN));

        // 3) 새 비밀번호 저장 (BCrypt 등으로 암호화)
        user.setPassword(passwordEncoder.encode(req.newPassword()));

        // ✅ 4) 로그인 실패 기록 / 계정 잠금 상태 초기화
        //    - 비밀번호를 정상적으로 변경했기 때문에
        //      과거의 실패 횟수/잠금 상태는 모두 리셋한다.
        user.resetLoginFail();

        // 변경사항 저장
        userRepository.save(user);

        // 5) 토큰 1회용 처리
        info.markUsed();
    }

    // ======================================================
    // 비밀번호 정책 검증 유틸
    // ======================================================

    /**
     * 비밀번호 정책:
     *  - 길이: 8 ~ 64자
     *  - 영문 / 숫자 / 특수문자 중 2종류 이상 포함
     */
    private boolean isValidPassword(String pw) {
        if (pw == null || pw.length() < 8 || pw.length() > 64) return false;

        boolean hasLetter = pw.chars().anyMatch(Character::isLetter);
        boolean hasDigit = pw.chars().anyMatch(Character::isDigit);
        boolean hasSpecial = pw.chars().anyMatch(c -> !Character.isLetterOrDigit(c));

        int kinds = (hasLetter ? 1 : 0) + (hasDigit ? 1 : 0) + (hasSpecial ? 1 : 0);
        return kinds >= 2;
    }

    /**
     * 인증코드 검증 성공 후, 프론트로 넘겨주는 결과 DTO.
     * - verified: 인증 성공 여부
     * - resetToken: 비밀번호 재설정에 사용할 토큰
     * - expiresIn: 토큰 유효 시간(초)
     */
    public record ResetTokenResult(
            boolean verified,
            String resetToken,
            int expiresIn
    ) {}
}
