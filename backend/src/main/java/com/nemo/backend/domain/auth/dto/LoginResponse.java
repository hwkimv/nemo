package com.nemo.backend.domain.auth.dto;

import lombok.Getter;

/**
 * 로그인 성공 응답 DTO.
 *
 * ⚠️ 주의:
 * - 이 DTO는 "로그인 성공(200 OK)"일 때만 사용됩니다.
 * - 로그인 실패, 캡챠 필요, 계정 잠금 등의 경우에는
 *   AuthException / ApiException 을 통해 에러 JSON이 내려갑니다.
 *
 * 성공 응답 JSON 예:
 * {
 *   "accessToken": "xxx.yyy.zzz",
 *   "refreshToken": "uuid-....",
 *   "expiresIn": 3600,
 *   "isNewUser": false,
 *   "user": {
 *     "userId": 1,
 *     "nickname": "닉네임",
 *     "profileImageUrl": "https://.../profile.jpg",
 *     "provider": "local" | "kakao" | "google"
 *   }
 * }
 *
 * ※ 캡챠 필요 여부(captchaRequired), 계정 잠금(lockUntil) 등의 정보는
 *    "200 OK가 아니라 401/423 에러 응답"으로 내려갑니다.
 */
@Getter
public class LoginResponse {

    /** JWT Access Token (보호 API 호출 시 Authorization 헤더에 사용) */
    private final String accessToken;

    /** Refresh Token (만료 시 토큰 재발급 요청에 사용) */
    private final String refreshToken;

    /** Access Token 만료 시간 (초 단위, 예: 3600 = 1시간) */
    private final long expiresIn;
    /** 소셜 최초 로그인으로 자동 회원가입된 경우 true */
    private final boolean isNewUser;

    /** 로그인 성공한 사용자 요약 정보 */
    private final UserSummary user;

    public LoginResponse(String accessToken,
                         String refreshToken,
                         long expiresIn,
                         boolean isNewUser,
                         Long userId,
                         String nickname,
                         String profileImageUrl,
                         String provider) {               // 👈 provider 추가
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.expiresIn = expiresIn;
        this.isNewUser = isNewUser;
        this.user = new UserSummary(userId, nickname, profileImageUrl, provider);
    }

    /**
     * 응답 내부 사용자 요약 정보 객체.
     */
    @Getter
    public static class UserSummary {
        private final Long userId;
        private final String nickname;
        private final String profileImageUrl;
        private final String provider;   // 👈 추가

        public UserSummary(Long userId,
                           String nickname,
                           String profileImageUrl,
                           String provider) {
            this.userId = (userId == null ? 0L : userId);
            this.nickname = (nickname == null ? "" : nickname);
            this.profileImageUrl = (profileImageUrl == null ? "" : profileImageUrl);
            this.provider = (provider == null ? "local" : provider);
        }
    }
}

