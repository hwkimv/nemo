package com.nemo.backend.domain.auth.service;

import com.nemo.backend.domain.auth.dto.*;
import com.nemo.backend.domain.auth.jwt.JwtUtil;
import com.nemo.backend.domain.auth.token.RefreshToken;
import com.nemo.backend.domain.auth.token.RefreshTokenRepository;
import com.nemo.backend.domain.user.entity.User;
import com.nemo.backend.domain.user.repository.UserRepository;
import com.nemo.backend.global.exception.ApiException;
import com.nemo.backend.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@Transactional
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    // 캡챠 검증용 서비스
    private final CaptchaService captchaService;

    // 🔐 로그인 실패 정책
    // - 2번까지는 그냥 실패
    // - 3번째 시도부터는 캡챠 요구
    // - 5번 연속 실패 시 계정 잠금(비밀번호 재설정 필요)
    private static final int LOGIN_CAPTCHA_THRESHOLD = 2; // 2번 틀린 뒤부터 캡챠
    private static final int LOGIN_MAX_FAIL_COUNT = 5;    // 총 5번 틀리면 잠금

    @Value("${jwt.access-exp-seconds:3600}")
    private long accessExpSeconds;

    @Value("${jwt.refresh-exp-days:14}")
    private long refreshExpDays;

    @Value("${jwt.refresh-rotate-threshold-sec:259200}")
    private long rotateThresholdSec;

    // =======================
    // 1) 회원가입
    // =======================
    public SignUpResponse signUp(SignUpRequest request) {

        if (request == null
                || request.getEmail() == null || request.getEmail().isBlank()
                || request.getPassword() == null || request.getPassword().isBlank()
                || request.getNickname() == null || request.getNickname().isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "필수 정보가 누락되었습니다. (email, password, nickname)");
        }

        String email = request.getEmail().trim();

        if (userRepository.existsByEmail(email)) {
            throw new ApiException(ErrorCode.DUPLICATE_EMAIL);
        }

        User user = new User();
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setNickname(request.getNickname().trim());  // ★ 필수
        user.setProfileImageUrl(request.getProfileImageUrl() != null ? request.getProfileImageUrl() : "");
        user.setProvider("local");
        user.setSocialId(null);

        User saved = userRepository.save(user);

        String createdAtStr = (saved.getCreatedAt() != null)
                ? saved.getCreatedAt().toString()
                : "";

        return new SignUpResponse(
                saved.getId(),
                saved.getEmail(),
                saved.getNickname(),
                saved.getProfileImageUrl(),
                createdAtStr
        );
    }


    // =======================
    // 2) 로그인 (시도 횟수 / 계정 잠금 / 캡챠 반영)
    // =======================
    /**
     * 이메일/비밀번호 로그인.
     *
     * 정책:
     * - 비밀번호 2번까지: 그냥 INVALID_CREDENTIALS
     * - 3~4번째: 캡챠를 요구 (프론트는 에러 코드 = NEED_CAPTCHA 받으면 캡챠 화면으로 유도)
     * - 5번째 이상: 계정 잠금 → ACCOUNT_LOCKED 반환, 비밀번호 재설정 필요
     *
     * 계정 잠금은 User.loginFailCount & lockedUntil 로 관리하며,
     * 비밀번호 재설정 성공 시 User.resetLoginFail()로 해제한다.
     */
    public LoginResponse login(LoginRequest request) {

        // 0) 기본 파라미터 검증
        if (request == null
                || request.getEmail() == null || request.getEmail().isBlank()
                || request.getPassword() == null || request.getPassword().isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }

        String email = request.getEmail().trim();

        // 1) 사용자 조회
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_CREDENTIALS));

        // (선택) 소셜 계정은 여기서 막고, 소셜 로그인 전용 API로만 로그인하게 할 수도 있음
        // if (!"local".equalsIgnoreCase(user.getProvider())) {
        //     throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
        // }

        // 2) 계정 잠금 여부 먼저 확인
        if (user.isLocked()) {
            // 이미 잠겨 있는 계정 → 바로 에러
            throw new ApiException(ErrorCode.ACCOUNT_LOCKED);
        }

        int failCount = user.getLoginFailCount();
        boolean needCaptcha = failCount >= LOGIN_CAPTCHA_THRESHOLD;

        // 3) 캡챠가 필요한 상태인지 체크
        if (needCaptcha) {
            // 3-1) 캡챠 ID/문자 자체가 안 왔으면 → "캡챠 먼저 입력해"라는 신호만 보냄
            if (request.getCaptchaId() == null || request.getCaptchaId().isBlank()
                    || request.getCaptchaAnswer() == null || request.getCaptchaAnswer().isBlank()) {
                throw new ApiException(ErrorCode.NEED_CAPTCHA);
            }

            // 3-2) 캡챠 값이 왔으면 실제 검증
            //  - 틀리거나 만료된 경우: CaptchaService가 ApiException(INVALID_CAPTCHA / CODE_EXPIRED / ATTEMPTS_EXCEEDED) 던짐
            captchaService.validate(request.getCaptchaId(), request.getCaptchaAnswer());
        }

        // 4) 비밀번호 검증
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            // ⬇️ 실패 시 처리: 실패 횟수 +1, 필요 시 계정 잠금
            handleLoginFail(user);
            throw new ApiException(
                    user.isLocked() ? ErrorCode.ACCOUNT_LOCKED : ErrorCode.INVALID_CREDENTIALS
            );
        }

        // 5) 여기까지 왔으면 로그인 성공 → 실패 기록 초기화
        user.resetLoginFail();
        userRepository.save(user);

        // 6) 토큰 발급
        String accessToken = jwtUtil.createAccessToken(user.getId(), user.getEmail());
        String refreshTokenStr = upsertRefreshTokenForUser(user.getId());

        String nickname = user.getNickname() != null ? user.getNickname() : "";
        String profile = user.getProfileImageUrl() != null ? user.getProfileImageUrl() : "";

        return new LoginResponse(
                accessToken,
                refreshTokenStr,
                accessExpSeconds,
                user.getId(),
                nickname,
                profile
        );
    }

    /**
     * 로그인 실패 시 호출되는 내부 메서드.
     * - 실패 횟수 1 증가
     * - 실패 횟수가 최대치를 넘으면 lockedUntil 설정
     */
    private void handleLoginFail(User user) {
        user.increaseLoginFail(); // loginFailCount++, lastLoginFailedAt 갱신

        if (user.getLoginFailCount() >= LOGIN_MAX_FAIL_COUNT) {
            // 정책: 5회 이상 틀리면 계정 잠금.
            // lockedUntil을 "사실상 영구 잠금"처럼 길게 잡고,
            // 비밀번호 재설정 성공 시 resetLoginFail()로 해제.
            user.lockUntil(LocalDateTime.now().plusYears(100));
        }

        userRepository.save(user);
    }

    // =======================
    // 3) 로그아웃 (명세 반영)
    // =======================
    public void logout(Long userId, String refreshToken) {

        // ✅ 1) 리프레시 토큰이 아예 없으면 → 할 일이 없으니 그냥 반환 (에러 X)
        if (refreshToken == null || refreshToken.isBlank()) {
            return; // 이미 사실상 로그아웃 상태
        }

        RefreshToken stored = refreshTokenRepository.findByToken(refreshToken)
                .orElse(null);

        // ✅ 2) DB에 해당 토큰이 없으면 → 이미 삭제된 상태이므로 그냥 성공으로 처리
        if (stored == null) {
            return;
        }

        // ✅ 3) accessToken 이 있었다면, 그 사용자와 refreshToken 소유자가 일치하는지 검증
        if (userId != null && !stored.getUserId().equals(userId)) {
            throw new ApiException(ErrorCode.INVALID_TOKEN);
        }

        LocalDateTime now = LocalDateTime.now();

        // ✅ 4) 만료된 토큰이면 그냥 삭제하고 끝 (에러 안 던짐)
        if (stored.getExpiry() == null || !stored.getExpiry().isAfter(now)) {
            refreshTokenRepository.delete(stored);
            return;
        }

        // ✅ 5) 정상 토큰이면 해당 토큰만 삭제
        refreshTokenRepository.delete(stored);
    }

    /**
     * (기존 코드 사용 중이면 유지용 – 전체 토큰 삭제)
     */
    public void logoutAll(Long userId) {
        refreshTokenRepository.deleteByUserId(userId);
    }

    // =======================
    // 4) 회원탈퇴 (비밀번호 확인 방식)
    // =======================
    public void deleteAccount(Long userId, String rawPassword) {

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));

        if (rawPassword != null && !rawPassword.isBlank()) {
            if (!passwordEncoder.matches(rawPassword, user.getPassword())) {
                throw new ApiException(ErrorCode.INVALID_PASSWORD);
            }
        }

        refreshTokenRepository.deleteByUserId(userId);
        userRepository.delete(user);
    }

    public void deleteAccount(Long userId) {
        deleteAccount(userId, null);
    }

    // =======================
    // 5) Access Token 재발급
    // =======================
    @Transactional(readOnly = true)
    public RefreshResponse refresh(RefreshRequest request) {

        if (request == null
                || request.refreshToken() == null
                || request.refreshToken().isBlank()) {
            // 400 TOKEN_REQUIRED
            throw new ApiException(ErrorCode.TOKEN_REQUIRED);
        }

        RefreshToken stored = refreshTokenRepository.findByToken(request.refreshToken())
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_TOKEN));

        LocalDateTime now = LocalDateTime.now();

        // 만료 or 잘못된 토큰
        if (stored.getExpiry() == null || !stored.getExpiry().isAfter(now)) {
            refreshTokenRepository.delete(stored);
            throw new ApiException(ErrorCode.INVALID_TOKEN);
        }

        User user = userRepository.findById(stored.getUserId())
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));

        // 새 액세스 토큰 발급
        String newAccessToken = jwtUtil.createAccessToken(user.getId(), user.getEmail());

        // refreshToken 갱신 정책 (만료 임박 시 새로 발급)
        LocalDateTime expiry = stored.getExpiry();
        long totalSeconds = refreshExpDays * 24L * 60L * 60L;
        long remainingSeconds = java.time.Duration.between(now, expiry).getSeconds();

        String newRefreshToken = null;

        // 남은 시간이 전체의 1/3 이하라면 새 토큰 발급
        if (remainingSeconds < totalSeconds / 3) {
            String rotated = UUID.randomUUID().toString();
            stored.setToken(rotated);
            stored.setExpiry(now.plusDays(refreshExpDays));
            refreshTokenRepository.save(stored);
            newRefreshToken = rotated;
        }

        // 명세: refreshToken은 갱신된 경우만 포함
        return new RefreshResponse(newAccessToken, newRefreshToken);
    }

    // =======================
    // 내부 유틸: RefreshToken upsert
    // =======================
    private String upsertRefreshTokenForUser(Long userId) {

        LocalDateTime newExpiry = LocalDateTime.now().plusDays(refreshExpDays);

        return refreshTokenRepository.findFirstByUserId(userId)
                .map(entity -> {
                    entity.setToken(UUID.randomUUID().toString());
                    entity.setExpiry(newExpiry);
                    return entity.getToken();
                })
                .orElseGet(() -> {
                    RefreshToken refreshToken = new RefreshToken();
                    refreshToken.setUserId(userId);
                    refreshToken.setToken(UUID.randomUUID().toString());
                    refreshToken.setExpiry(newExpiry);
                    refreshTokenRepository.save(refreshToken);
                    return refreshToken.getToken();
                });
    }
}
