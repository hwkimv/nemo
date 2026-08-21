// backend/src/main/java/com/nemo/backend/domain/photo/service/PhotoPersistence.java
package com.nemo.backend.domain.photo.service;

import com.nemo.backend.domain.photo.entity.Photo;
import com.nemo.backend.domain.photo.repository.PhotoRepository;
import com.nemo.backend.domain.storage.entity.StorageCleanupTask;
import com.nemo.backend.domain.storage.service.StorageCleanupService;
import com.nemo.backend.domain.storage.service.StorageService;
import com.nemo.backend.global.exception.ApiException;
import com.nemo.backend.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사진에 대한 <b>DB 작업만</b> 모아 둔 곳.
 *
 * <h3>왜 별도 클래스인가</h3>
 * 두 가지를 분리하기 위해서다.
 * <ul>
 *   <li><b>느린 외부 저장소 호출(S3)</b> — 트랜잭션 밖에 있어야 한다.
 *       트랜잭션이 S3 응답을 기다리는 동안 DB 커넥션과 행 잠금을 쥐고 있으면
 *       업로드가 몰릴 때 커넥션 풀이 마른다.</li>
 *   <li><b>DB 조작</b> — 트랜잭션 안에 있어야 한다.</li>
 * </ul>
 * 같은 빈 안에서는 {@code @Transactional} 메서드를 불러도 프록시를 타지 않아
 * 트랜잭션이 걸리지 않는다. 그래서 빈을 나눈다.
 * (RefreshTokenMaintenance와 같은 이유다)
 */
@Component
@RequiredArgsConstructor
public class PhotoPersistence {

    private final PhotoRepository photoRepository;
    private final StorageService storageService;
    private final StorageCleanupService cleanupService;

    /**
     * 한도를 잠그고 확인한 뒤 사진을 저장한다.
     *
     * <p>{@code REQUIRES_NEW}가 아니라 새 트랜잭션을 여는 이유는, 호출자인
     * {@code uploadHybrid()}가 트랜잭션 밖에서 돌기 때문이다.
     * 이 트랜잭션은 <b>S3 업로드가 끝난 뒤에 열려 곧바로 닫힌다.</b> 짧게 유지된다.
     */
    @Transactional
    public Photo save(Long userId, Photo photo) {
        // 저장 직전에 한도를 다시 확인한다. 이번에는 사용자 행을 잠근 채로 센다.
        // 세는 시점과 INSERT 시점 사이의 틈은 여기서만 닫힌다.
        storageService.reserveQuotaOrThrow(userId);
        return photoRepository.save(photo);
    }

    /**
     * 사진을 삭제 처리하고, <b>같은 트랜잭션에서</b> 파일 정리 작업을 예약한다.
     *
     * <h3>왜 DB가 먼저인가</h3>
     * 예전에는 S3를 먼저 지우고 DB를 나중에 건드렸다. 그 사이에 DB가 실패하면
     * <b>롤백으로 되돌릴 수 없는 상태</b>가 남는다 — DB에는 사진이 있는데 파일은 없다.
     * 사용자에게는 목록에 보이지만 열리지 않는 사진이 된다.
     *
     * <p>순서를 뒤집으면 최악의 경우가 "파일이 잠깐 더 남아 있는 것"으로 바뀐다.
     * 이건 나중에 지울 수 있다. 사라진 파일은 되살릴 수 없다.
     * <b>되돌릴 수 있는 쪽으로 실패가 기울게 만든다.</b>
     *
     * <p>그리고 "지워야 할 키"를 같은 트랜잭션에 적기 때문에, 사진 삭제가 커밋되면
     * 정리 작업도 반드시 함께 커밋된다. 둘 중 하나만 남는 상태가 생기지 않는다.
     *
     * @return 만들어진 정리 작업 id들. 호출자가 트랜잭션 밖에서 즉시 처리를 시도한다.
     */
    @Transactional
    public java.util.List<Long> markDeletedAndScheduleCleanup(Long userId, Long photoId,
                                                              java.util.List<String> objectKeys) {
        Photo photo = photoRepository.findByIdAndDeletedIsFalse(photoId)
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_ARGUMENT, "존재하지 않는 사진입니다."));
        if (!photo.getUserId().equals(userId)) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "삭제 권한이 없습니다.");
        }

        photo.setDeleted(true);
        photoRepository.save(photo);

        java.util.List<Long> taskIds = new java.util.ArrayList<>();
        for (String key : objectKeys) {
            Long id = cleanupService.scheduleInCurrentTransaction(key, StorageCleanupTask.Reason.PHOTO_DELETED);
            if (id != null) taskIds.add(id);
        }
        return taskIds;
    }

    /** 삭제 대상 사진을 읽는다. S3 키를 뽑기 위해 트랜잭션 밖 호출자가 먼저 부른다. */
    @Transactional(readOnly = true)
    public Photo findOwnedPhotoOrThrow(Long userId, Long photoId) {
        Photo photo = photoRepository.findByIdAndDeletedIsFalse(photoId)
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_ARGUMENT, "존재하지 않는 사진입니다."));
        if (!photo.getUserId().equals(userId)) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "삭제 권한이 없습니다.");
        }
        return photo;
    }
}
