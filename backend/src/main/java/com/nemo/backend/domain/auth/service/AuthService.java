package com.nemo.backend.domain.auth.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nemo.backend.domain.auth.dto.GoogleLoginRequest;
import com.nemo.backend.domain.auth.dto.KakaoLoginRequest;
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
import org.springframework.http.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

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
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${jwt.access-exp-seconds:3600}")
    private long accessExpSeconds;

    @Value("${jwt.refresh-exp-days:14}")
    private long refreshExpDays;

    @Value("${jwt.refresh-rotate-threshold-sec:259200}")
    private long rotateThresholdSec;

    // 구글 ID 토큰의 aud 검증용 (없으면 스킵)
    @Value("${oauth.google.client-id:}")
    private String googleClientId;

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
    // 2) 로그인
    // =======================
    public LoginResponse login(LoginRequest request) {

        if (request == null
                || request.getEmail() == null || request.getEmail().isBlank()
                || request.getPassword() == null || request.getPassword().isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }

        User user = userRepository.findByEmail(request.getEmail().trim())
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_CREDENTIALS));

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
        }

        // 로컬 로그인은 항상 isNewUser = false
        return createLoginResponse(user, false);
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

    private LoginResponse createLoginResponse(User user, boolean isNewUser) {
        String accessToken = jwtUtil.createAccessToken(user.getId(), user.getEmail());
        String refreshTokenStr = upsertRefreshTokenForUser(user.getId());

        String nickname = user.getNickname() != null ? user.getNickname() : "";
        String profile = user.getProfileImageUrl() != null ? user.getProfileImageUrl() : "";

        return new LoginResponse(
                accessToken,
                refreshTokenStr,
                accessExpSeconds,
                isNewUser,
                user.getId(),
                nickname,
                profile
        );
    }

    private String buildSocialEmail(String provider, String socialId, String emailFromProvider) {
        String email = (emailFromProvider != null && !emailFromProvider.isBlank())
                ? emailFromProvider.trim()
                : null;

        // 이메일이 없거나 이미 사용 중이면 provider/socialId 기반 가짜 이메일 생성
        if (email == null || userRepository.existsByEmail(email)) {
            email = provider + "_" + socialId + "@oauth.nemo";
        }
        return email;
    }

    private LoginResponse processSocialLogin(String provider,
                                             String socialId,
                                             String emailFromProvider,
                                             String nicknameFromProvider,
                                             String profileImageUrlFromProvider) {

        if (socialId == null || socialId.isBlank()) {
            // 토큰은 맞는데 유저 ID를 못 읽은 경우 → 토큰 불량 취급
            if ("kakao".equals(provider)) {
                throw new ApiException(ErrorCode.INVALID_KAKAO_TOKEN);
            } else if ("google".equals(provider)) {
                throw new ApiException(ErrorCode.INVALID_GOOGLE_TOKEN);
            } else {
                throw new ApiException(ErrorCode.INVALID_TOKEN);
            }
        }

        // 1) 기존 SNS 계정 있는지
        User user = userRepository.findByProviderAndSocialId(provider, socialId)
                .orElse(null);

        boolean isNewUser = false;

        if (user == null) {
            // 2) 없으면 자동 회원가입
            String email = buildSocialEmail(provider, socialId, emailFromProvider);

            user = new User();
            user.setEmail(email);
            // SNS 로그인 계정은 로컬 비밀번호로 로그인하지 않으므로 랜덤 비밀번호
            user.setPassword(passwordEncoder.encode(UUID.randomUUID().toString()));
            user.setNickname(
                    (nicknameFromProvider != null && !nicknameFromProvider.isBlank())
                            ? nicknameFromProvider.trim()
                            : provider + "_user"
            );
            user.setProfileImageUrl(
                    (profileImageUrlFromProvider != null) ? profileImageUrlFromProvider : ""
            );
            user.setProvider(provider);
            user.setSocialId(socialId);

            user = userRepository.save(user);
            isNewUser = true;
        } else {
            // 3) 있을 경우, 닉네임/프로필 정도는 최신값으로 가볍게 동기화
            if (nicknameFromProvider != null && !nicknameFromProvider.isBlank()
                    && !nicknameFromProvider.equals(user.getNickname())) {
                user.setNickname(nicknameFromProvider.trim());
            }
            if (profileImageUrlFromProvider != null
                    && !profileImageUrlFromProvider.isBlank()
                    && !profileImageUrlFromProvider.equals(user.getProfileImageUrl())) {
                user.setProfileImageUrl(profileImageUrlFromProvider);
            }
        }

        return createLoginResponse(user, isNewUser);
    }

    // =======================
    // SNS 로그인 - Kakao
    // =======================
    public LoginResponse loginWithKakao(KakaoLoginRequest request) {
        if (request == null || request.getAccessToken() == null || request.getAccessToken().isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "accessToken 은 필수입니다.");
        }

        String accessToken = request.getAccessToken().trim();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        HttpEntity<Void> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    "https://kapi.kakao.com/v2/user/me",
                    HttpMethod.GET,
                    entity,
                    String.class
            );

            JsonNode root = objectMapper.readTree(response.getBody());
            String kakaoId = root.path("id").asText(null);

            JsonNode account = root.path("kakao_account");
            JsonNode profile = account.path("profile");

            String email = account.path("email").asText(null);
            String nickname = profile.path("nickname").asText("");
            String profileImageUrl = profile.path("profile_image_url").asText("");

            return processSocialLogin("kakao", kakaoId, email, nickname, profileImageUrl);
        } catch (HttpClientErrorException e) {
            // 401 / 403 등 → 명세상 INVALID_KAKAO_TOKEN
            throw new ApiException(ErrorCode.INVALID_KAKAO_TOKEN);
        } catch (Exception e) {
            // 파싱 에러 등도 일단 토큰 불량 취급
            throw new ApiException(ErrorCode.INVALID_KAKAO_TOKEN);
        }
    }

    // =======================
    // SNS 로그인 - Google
    // =======================
    public LoginResponse loginWithGoogle(GoogleLoginRequest request) {
        if (request == null || request.getIdToken() == null || request.getIdToken().isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "idToken 은 필수입니다.");
        }

        String idToken = request.getIdToken().trim();

        try {
            // https://oauth2.googleapis.com/tokeninfo?id_token=...
            String uri = UriComponentsBuilder
                    .fromHttpUrl("https://oauth2.googleapis.com/tokeninfo")
                    .queryParam("id_token", idToken)
                    .build(true)
                    .toUriString();

            ResponseEntity<String> response = restTemplate.getForEntity(uri, String.class);
            JsonNode root = objectMapper.readTree(response.getBody());

            String sub = root.path("sub").asText(null);        // Google user ID
            String email = root.path("email").asText(null);
            String name = root.path("name").asText("");
            String picture = root.path("picture").asText("");

            // aud 검증 (clientId 설정돼 있을 때만)
            String aud = root.path("aud").asText(null);
            if (googleClientId != null && !googleClientId.isBlank()
                    && aud != null && !googleClientId.equals(aud)) {
                throw new ApiException(ErrorCode.INVALID_GOOGLE_TOKEN, "클라이언트 ID가 일치하지 않습니다.");
            }

            return processSocialLogin("google", sub, email, name, picture);
        } catch (HttpClientErrorException e) {
            throw new ApiException(ErrorCode.INVALID_GOOGLE_TOKEN);
        } catch (Exception e) {
            throw new ApiException(ErrorCode.INVALID_GOOGLE_TOKEN);
        }
    }
}
