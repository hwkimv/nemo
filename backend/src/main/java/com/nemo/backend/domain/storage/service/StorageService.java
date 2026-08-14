package com.nemo.backend.domain.storage.service;

import com.nemo.backend.domain.photo.repository.PhotoRepository;
import com.nemo.backend.domain.storage.dto.StorageQuotaResponse;
import com.nemo.backend.domain.storage.exception.PhotoLimitExceededException;
import com.nemo.backend.domain.user.entity.User;
import com.nemo.backend.domain.user.repository.UserRepository;
import com.nemo.backend.global.exception.ApiException;
import com.nemo.backend.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StorageService {

    private final UserRepository userRepository;
    private final PhotoRepository photoRepository;

    // ✅ 저장 한도/사용량 조회
    public StorageQuotaResponse getQuota(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND));

        int maxPhotos = user.getMaxPhotoCount();
        int usedPhotos = photoRepository.countByUserIdAndDeletedIsFalse(userId);
        int remainPhotos = Math.max(0, maxPhotos - usedPhotos);

        double usagePercent = 0.0;
        if (maxPhotos > 0) {
            usagePercent = (usedPhotos / (double) maxPhotos) * 100.0;
            usagePercent = Math.round(usagePercent * 10) / 10.0;
        }

        return StorageQuotaResponse.builder()
                .planType(user.getPlanType())
                .maxPhotos(maxPhotos)
                .usedPhotos(usedPhotos)
                .remainPhotos(remainPhotos)
                .usagePercent(usagePercent)
                .build();
    }

    /**
     * 저장 직전에 한도를 한 번 더 확인한다. 이번에는 <b>사용자 행을 잠근 채로</b> 센다.
     *
     * checkPhotoLimitOrThrow()만으로는 부족하다. 세는 시점과 INSERT 시점이 떨어져 있어
     * 그 사이에 다른 요청이 끼어들면 여러 요청이 모두 "아직 여유 있음"을 보고 통과한다.
     * 실제로 한도 20장에 한 자리 남은 상태에서 동시 8건을 보내면 7건이 통과해 26장이 됐다.
     * (PhotoQuotaConcurrencyTest)
     *
     * 이 조건은 행 개수에 대한 것이라 unique 제약 같은 DB 차원의 마지막 방어선이 없다.
     * 애플리케이션이 지키지 못하면 아무도 지켜주지 않는다.
     *
     * MANDATORY인 이유: 별도 트랜잭션에서 돌면 잠금이 즉시 풀려 아무 의미가 없다.
     * 반드시 INSERT를 포함한 호출자 트랜잭션 안에서 실행돼야 한다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reserveQuotaOrThrow(Long userId) {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND));

        int maxPhotos = user.getMaxPhotoCount();
        int usedPhotos = photoRepository.countByUserIdAndDeletedIsFalse(userId);

        if (usedPhotos >= maxPhotos) {
            throw new PhotoLimitExceededException(maxPhotos, usedPhotos);
        }
    }

    // ✅ 업로드 전에 한도 체크 (초과 시 예외 던짐)
    //    느린 파일 저장을 시작하기 전에 미리 거절하기 위한 것이며, 이것만으로는 동시성을 막지 못한다.
    //    최종 보장은 저장 직전의 reserveQuotaOrThrow()가 한다.
    public void checkPhotoLimitOrThrow(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND));

        int maxPhotos = user.getMaxPhotoCount();
        int usedPhotos = photoRepository.countByUserIdAndDeletedIsFalse(userId);

        if (usedPhotos >= maxPhotos) {
            throw new PhotoLimitExceededException(maxPhotos, usedPhotos);
        }
    }
}
