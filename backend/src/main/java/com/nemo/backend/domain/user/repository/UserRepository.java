package com.nemo.backend.domain.user.repository;

import com.nemo.backend.domain.user.entity.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link User} persistence.
 * -------------------------------------------
 * - 기본 CRUD (findById, save 등)
 * - 이메일 단건 조회 (findByEmail)
 * - 닉네임/이메일 기반 검색 기능 추가 (searchByNicknameOrEmail)
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * 저장 한도 확인용 사용자 조회 (행 잠금).
     *
     * 사진 저장 한도는 "행 개수"에 대한 조건이라 DB unique 제약으로 막을 수 없다.
     * 세는 시점과 넣는 시점 사이에 다른 요청이 끼어들면 둘 다 통과한다.
     * 같은 사용자에 대한 업로드를 이 행 잠금으로 줄 세워 그 틈을 없앤다.
     *
     * 반드시 호출자의 트랜잭션 안에서 쓴다. 잠금은 그 트랜잭션이 커밋될 때 풀린다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Long id);

    /**
     * ✅ 이메일로 사용자 조회
     * -----------------------------
     * - 로그인 시 사용자 존재 여부 확인용
     */
    Optional<User> findByEmail(String email);

    /**
     * ✅ 닉네임 또는 이메일 일부로 사용자 검색
     * -----------------------------
     * - 친구 검색 API에서 사용
     * - 대소문자 구분 없이 검색 (LOWER)
     * - LIKE 검색으로 부분 일치 처리
     *
     * 예시:
     *  keyword = "네컷"  →  닉네임에 '네컷'이 포함된 모든 사용자 조회
     */
    @Query("SELECT u FROM User u WHERE LOWER(u.nickname) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
            "OR LOWER(u.email) LIKE LOWER(CONCAT('%', :keyword, '%'))")
    List<User> searchByNicknameOrEmail(String keyword);
    boolean existsByEmail(String email);

    // SNS 로그인용: provider + socialId 로 사용자 조회
    Optional<User> findByProviderAndSocialId(String provider, String socialId);
}
