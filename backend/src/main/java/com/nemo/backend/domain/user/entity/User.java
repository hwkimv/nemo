package com.nemo.backend.domain.user.entity;

import com.nemo.backend.global.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 사용자(User) 엔티티
 *
 * - 기본 사용자 정보(email, password, nickname 등)를 관리합니다.
 * - 소셜 로그인(provider, socialId)과 일반 로그인 둘 다 지원합니다.
 * - 로그인 실패 횟수, 캡챠 필요 여부, 계정 잠금 정보는
 *   AuthService.login() 과정에서 사용됩니다.
 */
@Entity
@Table(name = "users")
public class User extends BaseEntity {

    /* ---------------------- 기본 정보 ---------------------- */

    @Getter
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 로그인 이메일 (고유값) */
    @Getter
    @Setter
    @Column(nullable = false, unique = true, length = 191)
    private String email;

    /** 비밀번호 (소셜 로그인 계정은 null 허용) */
    @Getter
    @Setter
    @Column(nullable = false)
    private String password;

    /** 닉네임 (null 가능하지만 응답 DTO에서 빈 문자열 처리됨) */
    @Getter
    @Setter
    @Column(name = "nickname")
    private String nickname;

    /** 프로필 이미지 URL */
    @Getter
    @Setter
    @Column(name = "profile_image_url")
    private String profileImageUrl;

    /** 로그인 제공자 (LOCAL, KAKAO, APPLE 등) */
    @Getter
    @Setter
    private String provider;

    /** 소셜 로그인 ID (provider와 묶어서 unique 처리) */
    @Getter
    @Setter
    private String socialId;

    /** 요금제 타입 (FREE, PLUS 등) */
    @Getter
    @Setter
    @Column(nullable = false, length = 20)
    private String planType = "FREE";

    /** 해당 사용자가 저장할 수 있는 최대 사진 수 */
    @Getter
    @Setter
    @Column(nullable = false)
    private int maxPhotoCount = 20;


    /* ----------------------------------------------------------
     * 🔐 로그인 보안 관련 필드
     * ----------------------------------------------------------
     * 아래 3개 필드는 이메일/비밀번호 로그인 시도 실패 관련 로직에서 사용됩니다.
     *
     * ✔ loginFailCount  : 연속 로그인 실패 횟수
     * ✔ lastLoginFailedAt : 마지막 실패 시각
     * ✔ lockedUntil : 특정 시각까지 로그인 차단(잠금)
     *
     * AuthService.login()에서 다음 정책에 활용됩니다:
     *
     * - 실패 2회 이상 → 캡챠 필요
     * - 실패 5회 이상 → 일정 시간 잠금(lockout)
     * - 비밀번호 변경 성공, 로그인 성공 시 → 실패 기록 초기화
     * ---------------------------------------------------------- */

    /** 연속 로그인 실패 횟수 (로그인 성공/비번 변경 시 초기화) */
    @Getter
    @Setter
    @Column(nullable = false)
    private int loginFailCount = 0;

    /** 마지막 로그인 실패 발생 시각 */
    @Getter
    @Setter
    private LocalDateTime lastLoginFailedAt;

    /** lockedUntil 이전까지 계정 로그인 불가 (비밀번호 재설정 유도) */
    @Getter
    @Setter
    private LocalDateTime lockedUntil;


    /* ---------------------- 헬퍼 메서드 ---------------------- */

    /**
     * 현재 계정이 잠겨 있는지 확인합니다.
     * - lockedUntil 값이 현재 시각 이후면 "잠금 상태"
     */
    public boolean isLocked() {
        return lockedUntil != null && lockedUntil.isAfter(LocalDateTime.now());
    }

    /**
     * 로그인 실패 시 호출됩니다.
     * - 실패 카운트 증가
     * - 마지막 실패 시각 업데이트
     */
    public void increaseLoginFail() {
        this.loginFailCount++;
        this.lastLoginFailedAt = LocalDateTime.now();
    }

    /**
     * 로그인 성공 또는 비밀번호 재설정 성공 시 호출됩니다.
     * - 실패 기록 초기화
     * - 잠금 해제
     */
    public void resetLoginFail() {
        this.loginFailCount = 0;
        this.lastLoginFailedAt = null;
        this.lockedUntil = null;
    }

    /**
     * 계정을 일정 시간 동안 잠금 상태로 변경합니다.
     * - AuthService.login()에서 실패 횟수가 기준을 초과할 때 호출됩니다.
     */
    public void lockUntil(LocalDateTime lockedUntil) {
        this.lockedUntil = lockedUntil;
    }
}
