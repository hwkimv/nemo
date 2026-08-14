// backend/src/main/java/com/nemo/backend/domain/album/repository/AlbumListingRepositoryImpl.java
package com.nemo.backend.domain.album.repository;

import com.nemo.backend.domain.album.dto.AlbumListingRow;
import com.nemo.backend.domain.album.dto.AlbumOwnershipFilter;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 소유 앨범과 공유받은 앨범을 UNION ALL로 합쳐 DB에서 정렬·페이징한다.
 *
 * 왜 native query인가:
 * 두 집합이 서로 다른 테이블(album, album_share)에서 나오고, 정렬 기준이 요청마다
 * 달라진다(createdAt / title, asc / desc). JPQL로는 이 조합을 깔끔하게 표현하기 어렵다.
 *
 * 정렬 컬럼은 문자열을 이어붙이지만 {@link AlbumSortField} enum에서만 나온다.
 * 사용자 입력이 SQL에 직접 들어가는 경로는 없다.
 */
@Repository
public class AlbumListingRepositoryImpl implements AlbumListingRepository {

    @PersistenceContext
    private EntityManager em;

    /**
     * 소유/공유 앨범을 합친 뒤 즐겨찾기 조건까지 적용한 공통 FROM 절.
     *
     * sort_name은 대소문자를 무시한 제목 정렬용이다. 기존 코드가 Java에서
     * String.CASE_INSENSITIVE_ORDER로 정렬했으므로 DB에서도 LOWER()로 맞춘다.
     */
    private static final String BASE = """
            FROM (
                SELECT a.id AS album_id,
                       CAST('OWNER' AS VARCHAR(20)) AS role,
                       a.created_at AS created_at,
                       LOWER(a.name) AS sort_name
                FROM album a
                WHERE a.user_id = :userId
                  AND :includeOwned = TRUE
                UNION ALL
                SELECT a.id,
                       CAST(s.role AS VARCHAR(20)),
                       a.created_at,
                       LOWER(a.name)
                FROM album_share s
                JOIN album a ON a.id = s.album_id
                WHERE s.user_id = :userId
                  AND s.status = 'ACCEPTED'
                  AND s.active = TRUE
                  AND :includeShared = TRUE
            ) t
            WHERE (:favoriteOnly = FALSE OR t.album_id IN (
                    SELECT f.album_id FROM album_favorite f WHERE f.user_id = :userId))
            """;

    @Override
    public List<AlbumListingRow> findAlbumPage(Long userId,
                                               AlbumOwnershipFilter ownership,
                                               boolean favoriteOnly,
                                               AlbumSortField sortField,
                                               boolean ascending,
                                               int page,
                                               int size) {

        String direction = ascending ? "ASC" : "DESC";
        // 정렬 값이 같을 때 페이지 경계에서 순서가 흔들리지 않도록 album_id를 tie-breaker로 둔다.
        // 이게 없으면 같은 createdAt을 가진 앨범이 두 페이지에 중복되거나 누락될 수 있다.
        String sql = "SELECT t.album_id, t.role " + BASE
                + " ORDER BY t." + sortField.column() + " " + direction + ", t.album_id ASC";

        Query query = em.createNativeQuery(sql);
        bindCommon(query, userId, ownership, favoriteOnly);
        query.setFirstResult(Math.max(page, 0) * size);
        query.setMaxResults(size);

        List<?> rows = query.getResultList();
        return rows.stream()
                .map(row -> {
                    Object[] cells = (Object[]) row;
                    return new AlbumListingRow(
                            ((Number) cells[0]).longValue(),
                            (String) cells[1]
                    );
                })
                .toList();
    }

    @Override
    public long countAlbums(Long userId, AlbumOwnershipFilter ownership, boolean favoriteOnly) {
        Query query = em.createNativeQuery("SELECT COUNT(*) " + BASE);
        bindCommon(query, userId, ownership, favoriteOnly);
        return ((Number) query.getSingleResult()).longValue();
    }

    private void bindCommon(Query query, Long userId, AlbumOwnershipFilter ownership, boolean favoriteOnly) {
        query.setParameter("userId", userId);
        query.setParameter("includeOwned", ownership != AlbumOwnershipFilter.SHARED);
        query.setParameter("includeShared", ownership != AlbumOwnershipFilter.OWNED);
        query.setParameter("favoriteOnly", favoriteOnly);
    }
}
