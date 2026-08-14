// backend/src/main/java/com/nemo/backend/domain/auth/service/RefreshTokenMaintenance.java
package com.nemo.backend.domain.auth.service;

import com.nemo.backend.domain.auth.token.RefreshToken;
import com.nemo.backend.domain.auth.token.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 만료된 Refresh Token 정리.
 *
 * 왜 별도 클래스인가:
 * 만료 토큰을 발견했을 때 우리는 두 가지를 동시에 하고 싶다.
 *  1) 그 요청은 실패시킨다 (401)
 *  2) 쓸모없어진 row는 DB에서 지운다
 *
 * 그런데 같은 트랜잭션 안에서 지우고 예외를 던지면, 예외가 트랜잭션을 롤백하면서 삭제까지 함께 되돌아간다.
 * 결과적으로 만료 토큰이 영원히 테이블에 쌓인다.
 * 그래서 삭제만 REQUIRES_NEW로 분리해, 요청 실패와 무관하게 독립적으로 커밋되게 한다.
 *
 * (self-invocation은 프록시를 타지 않으므로 반드시 별도 빈이어야 한다.)
 */
@Component
@RequiredArgsConstructor
public class RefreshTokenMaintenance {

    private final RefreshTokenRepository refreshTokenRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deleteInSeparateTransaction(RefreshToken token) {
        refreshTokenRepository.deleteById(token.getId());
    }
}
