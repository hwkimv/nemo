// backend/src/main/java/com/nemo/backend/domain/album/dto/AlbumListingRow.java
package com.nemo.backend.domain.album.dto;

/**
 * 목록 페이지에 들어갈 앨범 한 건의 식별 정보.
 *
 * 상세 정보(제목·커버·장수)는 이 id들로 따로 한 번에 가져온다.
 * 페이지를 먼저 확정하고 상세를 채우는 순서라, 페이지 밖 앨범의 사진은 아예 읽지 않는다.
 */
public record AlbumListingRow(Long albumId, String role) {
}
