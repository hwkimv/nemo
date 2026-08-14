// backend/src/main/java/com/nemo/backend/domain/album/repository/AlbumListingRepository.java
package com.nemo.backend.domain.album.repository;

import com.nemo.backend.domain.album.dto.AlbumListingRow;
import com.nemo.backend.domain.album.dto.AlbumOwnershipFilter;

import java.util.List;

/**
 * 앨범 목록 화면 전용 조회.
 *
 * 목록은 "내가 소유한 앨범"과 "공유받은 앨범"을 합친 뒤 정렬·페이지를 나눠야 한다.
 * 두 집합은 서로 다른 테이블에서 나오므로 Spring Data의 파생 쿼리 하나로는 표현할 수 없다.
 * 그래서 UNION ALL로 합쳐 DB에서 정렬·페이징한다.
 */
public interface AlbumListingRepository {

    /** 정렬·페이징까지 DB에서 끝낸 한 페이지 분량의 앨범 id와 역할 */
    List<AlbumListingRow> findAlbumPage(Long userId,
                                        AlbumOwnershipFilter ownership,
                                        boolean favoriteOnly,
                                        AlbumSortField sortField,
                                        boolean ascending,
                                        int page,
                                        int size);

    /** 같은 조건의 전체 개수 (page.totalElements 용) */
    long countAlbums(Long userId, AlbumOwnershipFilter ownership, boolean favoriteOnly);

    /**
     * 정렬 가능한 필드. 문자열을 SQL에 그대로 이어붙이면 SQL injection이 되므로
     * 허용 목록을 enum으로 고정하고, 컬럼 이름은 여기에서만 나온다.
     */
    enum AlbumSortField {
        CREATED_AT("created_at"),
        NAME("sort_name");

        private final String column;

        AlbumSortField(String column) {
            this.column = column;
        }

        public String column() {
            return column;
        }

        /** 알 수 없는 값은 기존 동작과 같이 createdAt으로 떨어뜨린다. */
        public static AlbumSortField from(String raw) {
            if (raw == null) return CREATED_AT;
            return switch (raw.trim()) {
                case "title", "name" -> NAME;
                default -> CREATED_AT;
            };
        }
    }
}
