// backend/src/main/java/com/nemo/backend/domain/album/dto/AlbumPhotoRow.java
package com.nemo.backend.domain.album.dto;

import java.time.LocalDateTime;

/**
 * 앨범 목록 화면이 필요로 하는 사진 정보만 담은 얇은 행.
 *
 * 목록에는 "사진 몇 장인지"와 "커버로 뭘 보여줄지"만 필요하다.
 * 그런데 예전 코드는 Album 엔티티의 LAZY photos 컬렉션을 앨범마다 건드려서
 * 앨범 개수만큼 SELECT를 날리고, 사진 엔티티를 통째로 메모리에 올렸다.
 * 필요한 컬럼 4개만 한 번에 가져오기 위한 projection이다.
 */
public record AlbumPhotoRow(
        Long albumId,
        String thumbnailUrl,
        String imageUrl,
        LocalDateTime createdAt
) {
    /** 커버로 쓸 URL: 썸네일이 있으면 썸네일, 없으면 원본 */
    public String displayUrl() {
        return (thumbnailUrl != null && !thumbnailUrl.isBlank()) ? thumbnailUrl : imageUrl;
    }
}
