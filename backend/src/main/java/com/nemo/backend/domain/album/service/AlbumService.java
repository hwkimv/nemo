// backend/src/main/java/com/nemo/backend/domain/album/service/AlbumService.java
package com.nemo.backend.domain.album.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.nemo.backend.domain.album.dto.*;
import com.nemo.backend.domain.album.entity.Album;
import com.nemo.backend.domain.album.entity.AlbumShare;
import com.nemo.backend.domain.album.entity.AlbumShare.Status;
import com.nemo.backend.domain.album.entity.AlbumFavorite;
import com.nemo.backend.domain.album.repository.AlbumFavoriteRepository;
import com.nemo.backend.domain.album.repository.AlbumRepository;
import com.nemo.backend.domain.album.repository.AlbumShareRepository;
import com.nemo.backend.domain.album.repository.AlbumListingRepository;
import com.nemo.backend.domain.photo.entity.Photo;
import com.nemo.backend.domain.photo.repository.PhotoRepository;
import com.nemo.backend.domain.photo.service.PhotoStorage;
import com.nemo.backend.domain.photo.service.S3PhotoStorage;
import com.nemo.backend.domain.user.entity.User;
import com.nemo.backend.global.exception.ApiException;
import com.nemo.backend.global.exception.ErrorCode;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@Transactional(readOnly = true)
public class AlbumService {

    private final AlbumRepository albumRepository;
    private final AlbumShareRepository albumShareRepository;
    private final PhotoRepository photoRepository;
    private final AlbumFavoriteRepository albumFavoriteRepository;
    private final AlbumListingRepository albumListingRepository;
    private final PhotoStorage photoStorage;

    private final String publicBaseUrl;

    @PersistenceContext
    private EntityManager em;

    public AlbumService(
            AlbumRepository albumRepository,
            AlbumShareRepository albumShareRepository,
            PhotoRepository photoRepository,
            AlbumFavoriteRepository albumFavoriteRepository,
            AlbumListingRepository albumListingRepository,
            PhotoStorage photoStorage,
            @Value("${app.public-base-url:http://localhost:8080}") String publicBaseUrl
    ) {
        this.albumRepository = albumRepository;
        this.albumShareRepository = albumShareRepository;
        this.photoRepository = photoRepository;
        this.albumFavoriteRepository = albumFavoriteRepository;
        this.albumListingRepository = albumListingRepository;
        this.photoStorage = photoStorage;
        this.publicBaseUrl = publicBaseUrl.replaceAll("/+$", "");
    }

    // 1) 앨범 목록 조회 (ownership + favoriteOnly)
    // ownership: ALL / OWNED / SHARED
    //
    // ⚡ N+1 제거:
    // 예전에는 앨범마다 (1) LAZY photos 컬렉션 접근 (2) 공유여부 exists 조회를 해서
    // 앨범 100개 = SQL 202개였다. 지금은 앨범이 몇 개든 SQL 개수가 고정이다.
    //   ① 소유 앨범 목록  ② 공유받은 앨범 목록(fetch join)
    //   ③ 대상 앨범들의 살아있는 사진 행 일괄 조회  ④ 공유 중인 앨범 id 일괄 조회
    public List<AlbumSummaryResponse> getAlbums(Long userId, AlbumOwnershipFilter ownership) {

        // ①② 목록에 필요한 앨범 엔티티를 먼저 모은다. (photos 컬렉션은 건드리지 않는다)
        List<Album> ownedAlbums = albumRepository.findByUserId(userId);
        List<AlbumShare> acceptedShares =
                albumShareRepository.findAcceptedSharesWithAlbum(userId, Status.ACCEPTED);

        Set<Long> albumIds = new LinkedHashSet<>();
        ownedAlbums.forEach(a -> albumIds.add(a.getId()));
        acceptedShares.forEach(s -> albumIds.add(s.getAlbum().getId()));

        // ③ 앨범별 살아있는 사진 정보를 한 번에 (장수 계산 + 커버 선정에 모두 사용)
        Map<Long, List<AlbumPhotoRow>> photoRowsByAlbum = albumIds.isEmpty()
                ? Map.of()
                : albumRepository.findAlivePhotoRows(albumIds).stream()
                        .collect(Collectors.groupingBy(AlbumPhotoRow::albumId));

        // ④ 소유 앨범 중 현재 남과 공유 중인 것들을 한 번에
        Set<Long> sharedWithOthers = albumIds.isEmpty()
                ? Set.of()
                : new HashSet<>(albumShareRepository.findSharedAlbumIds(albumIds, Status.ACCEPTED));

        // 1) 내가 소유한 앨범들
        List<AlbumSummaryResponse> owned = ownedAlbums.stream()
                .map(album -> {
                    List<AlbumPhotoRow> rows = photoRowsByAlbum.getOrDefault(album.getId(), List.of());
                    return AlbumSummaryResponse.builder()
                            .albumId(album.getId())
                            .title(album.getName())
                            .coverPhotoUrl(resolveCoverUrl(album.getCoverPhotoUrl(), rows))
                            .photoCount(rows.size())
                            .createdAt(album.getCreatedAt())
                            .role("OWNER")
                            .shared(sharedWithOthers.contains(album.getId()))
                            .build();
                })
                .collect(Collectors.toList());

        // 2) 내가 공유받은 앨범들
        List<AlbumSummaryResponse> shared = acceptedShares.stream()
                .map(share -> {
                    Album album = share.getAlbum();
                    List<AlbumPhotoRow> rows = photoRowsByAlbum.getOrDefault(album.getId(), List.of());
                    return AlbumSummaryResponse.builder()
                            .albumId(album.getId())
                            .title(album.getName())
                            .coverPhotoUrl(resolveCoverUrl(album.getCoverPhotoUrl(), rows))
                            .photoCount(rows.size())
                            .createdAt(album.getCreatedAt())
                            .role(share.getRole().name())
                            // 공유받은 앨범 목록이므로 항상 true
                            .shared(true)
                            .build();
                })
                .collect(Collectors.toList());


        List<AlbumSummaryResponse> result;

        switch (ownership) {
            case OWNED -> result = owned;
            case SHARED -> result = shared;
            case ALL -> {
                result = new ArrayList<>(owned);
                result.addAll(shared);
            }
            default -> throw new IllegalStateException("Unexpected value: " + ownership);
        }

        result.sort(Comparator.comparing(AlbumSummaryResponse::getCreatedAt).reversed());

        return result;
    }

    /**
     * 앨범 목록 한 페이지. 정렬과 페이지 나누기를 DB에서 끝낸다.
     *
     * 예전에는 Controller가 전체 목록을 만든 뒤 메모리에서 정렬하고 subList()로 잘랐다.
     * 1페이지만 보려 해도 사용자의 앨범 전부와 그 사진 정보를 메모리에 올렸다.
     *
     * 지금은 두 단계다.
     *   1) DB에서 정렬·페이징까지 끝내고 이 페이지에 들어갈 앨범 id만 받는다
     *   2) 그 id들에 대해서만 상세(장수·커버·공유여부)를 채운다
     * 그래서 페이지 밖 앨범의 사진은 아예 읽지 않는다.
     */
    public AlbumPageResult getAlbumPage(Long userId,
                                        String ownership,
                                        boolean favoriteOnly,
                                        String sortField,
                                        boolean ascending,
                                        int page,
                                        int size) {

        AlbumOwnershipFilter filter = AlbumOwnershipFilter.from(ownership);

        long totalElements = albumListingRepository.countAlbums(userId, filter, favoriteOnly);
        List<AlbumListingRow> pageRows = albumListingRepository.findAlbumPage(
                userId, filter, favoriteOnly,
                AlbumListingRepository.AlbumSortField.from(sortField),
                ascending, page, size);

        return new AlbumPageResult(toSummaries(userId, pageRows), totalElements);
    }

    /** 페이지에 들어갈 앨범들만 상세를 채운다. 쿼리 수는 앨범 수와 무관하게 고정이다. */
    private List<AlbumSummaryResponse> toSummaries(Long userId, List<AlbumListingRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }

        List<Long> albumIds = rows.stream().map(AlbumListingRow::albumId).toList();

        Map<Long, Album> albumsById = albumRepository.findAllById(albumIds).stream()
                .collect(Collectors.toMap(Album::getId, a -> a));

        Map<Long, List<AlbumPhotoRow>> photoRowsByAlbum = albumRepository.findAlivePhotoRows(albumIds)
                .stream()
                .collect(Collectors.groupingBy(AlbumPhotoRow::albumId));

        Set<Long> sharedWithOthers =
                new HashSet<>(albumShareRepository.findSharedAlbumIds(albumIds, Status.ACCEPTED));

        List<AlbumSummaryResponse> result = new ArrayList<>(rows.size());
        for (AlbumListingRow row : rows) {
            Album album = albumsById.get(row.albumId());
            if (album == null) {
                continue; // 조회 도중 삭제된 앨범
            }
            List<AlbumPhotoRow> photoRows = photoRowsByAlbum.getOrDefault(row.albumId(), List.of());
            boolean isOwner = "OWNER".equals(row.role());

            result.add(AlbumSummaryResponse.builder()
                    .albumId(album.getId())
                    .title(album.getName())
                    .coverPhotoUrl(resolveCoverUrl(album.getCoverPhotoUrl(), photoRows))
                    .photoCount(photoRows.size())
                    .createdAt(album.getCreatedAt())
                    .role(row.role())
                    // 소유 앨범은 "남과 공유 중인가", 공유받은 앨범은 정의상 항상 true
                    .shared(isOwner ? sharedWithOthers.contains(album.getId()) : true)
                    .build());
        }
        return result;
    }

    /** 목록 한 페이지와 전체 개수 */
    public record AlbumPageResult(List<AlbumSummaryResponse> content, long totalElements) {
    }

    // favoriteOnly까지 포함
    public List<AlbumSummaryResponse> getAlbums(Long userId, String ownership, boolean favoriteOnly) {

        AlbumOwnershipFilter filter = AlbumOwnershipFilter.from(ownership);
        List<AlbumSummaryResponse> base = getAlbums(userId, filter);

        if (!favoriteOnly) {
            return base;
        }

        Set<Long> favIds = albumFavoriteRepository.findByUserId(userId).stream()
                .map(f -> f.getAlbum().getId())
                .collect(Collectors.toSet());

        return base.stream()
                .filter(a -> favIds.contains(a.getAlbumId()))
                .toList();
    }

    // 2) 앨범 상세 조회
    public AlbumDetailResponse getAlbum(Long userId, Long albumId) {
        Album album = albumRepository.findById(albumId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "ALBUM_NOT_FOUND"));

        String role;
        if (album.getUser() != null && userId.equals(album.getUser().getId())) {
            role = "OWNER";
        } else {
            AlbumShare share = albumShareRepository
                    .findByAlbumIdAndUserIdAndStatusAndActiveTrue(albumId, userId, Status.ACCEPTED)
                    .orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN, "해당 앨범에 접근할 권한이 없습니다."));
            role = share.getRole().name();
        }

        autoSetThumbnailIfMissing(album);
        return toDetail(album, role);
    }

    // 3) 앨범 생성
    @Transactional
    public AlbumDetailResponse createAlbum(Long userId, CreateAlbumRequest req) {
        if (req.getTitle() == null || req.getTitle().isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "앨범 이름(title)은 필수입니다.");
        }

        Album album = new Album();
        album.setName(req.getTitle());
        album.setDescription(req.getDescription());

        User ownerRef = em.getReference(User.class, userId);
        album.setUser(ownerRef);

        Album saved = albumRepository.save(album);

        // 초기 사진 지정
        // ⚠️ photoIdList와 coverPhotoId는 클라이언트가 보낸 값이다.
        //    requireUsablePhotos()가 "요청자 소유 + 미삭제"인 사진만 통과시키고, 하나라도 아니면 요청 전체를 실패시킨다.
        if (req.getPhotoIdList() != null && !req.getPhotoIdList().isEmpty()) {
            List<Photo> alivePhotos = requireUsablePhotos(userId, req.getPhotoIdList());

            if (saved.getPhotos() == null) {
                saved.setPhotos(new ArrayList<>());
            }
            saved.getPhotos().addAll(alivePhotos);

            // 생성 시 사용자가 지정한 썸네일이 있으면 우선 적용 (photoIdList 안에 있는 경우)
            if (req.getCoverPhotoId() != null) {
                alivePhotos.stream()
                        .filter(p -> req.getCoverPhotoId().equals(p.getId()))
                        .findFirst()
                        .ifPresent(p -> saved.setCoverPhotoUrl(coverUrlOf(p)));
            }
        }

        // photoIdList 가 비어 있어도 coverPhotoId 가 들어온 경우 한 번 더 커버 처리
        if (req.getCoverPhotoId() != null &&
                (saved.getCoverPhotoUrl() == null || saved.getCoverPhotoUrl().isBlank())) {

            // cover 경로도 같은 소유권 정책을 쓴다. 여기가 열려 있으면
            // photoIdList를 막아도 coverPhotoId로 남의 사진 URL을 얻어낼 수 있다.
            Photo cover = requireUsablePhotos(userId, List.of(req.getCoverPhotoId())).get(0);
            saved.setCoverPhotoUrl(coverUrlOf(cover));

            // 앨범에 아직 없는 사진이면 같이 추가
            if (saved.getPhotos() == null) {
                saved.setPhotos(new ArrayList<>());
            }
            boolean exists = saved.getPhotos().stream()
                    .anyMatch(existing -> existing.getId().equals(cover.getId()));
            if (!exists) {
                saved.getPhotos().add(cover);
            }
        }

        // 최종적으로 커버가 비어 있으면 자동 썸네일
        autoSetThumbnailIfMissing(saved);

        return toDetail(saved, "OWNER");
    }

    /**
     * 앨범에 넣어도 되는 사진만 돌려준다. 하나라도 쓸 수 없으면 요청 전체를 실패시킨다.
     *
     * 정책: <b>요청자가 소유한, 삭제되지 않은 사진만 허용</b>한다.
     * 앨범 편집 권한(OWNER/EDITOR)과 사진 사용 권한은 별개다. 공유 앨범의 EDITOR라도
     * 남의 사진을 끌어올 수는 없다. 현재 규모에서 가장 단순하고 안전한 기본값이며,
     * 공유 편집 정책이 필요해지면 그때 명시적으로 넓힌다.
     *
     * 조용히 일부만 추가하지 않는 이유: 사용자는 10장을 골랐는데 7장만 들어가면
     * 무엇이 왜 빠졌는지 알 수 없고, 공격자에게는 "어떤 ID가 통과하는지" 알려주는 신호가 된다.
     */
    private List<Photo> requireUsablePhotos(Long userId, List<Long> requestedIds) {
        if (requestedIds == null || requestedIds.isEmpty()) {
            return List.of();
        }
        if (requestedIds.stream().anyMatch(java.util.Objects::isNull)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "사진 ID에 null이 포함될 수 없습니다.");
        }

        // 같은 ID를 여러 번 보내도 개수 비교가 어긋나지 않도록 중복 제거 후 비교한다.
        Set<Long> uniqueIds = new LinkedHashSet<>(requestedIds);

        List<Photo> usable = photoRepository.findAllByIdInAndUserIdAndDeletedIsFalse(uniqueIds, userId);

        if (usable.size() != uniqueIds.size()) {
            // 존재하지 않음 / 삭제됨 / 남의 사진을 구분해 알려주지 않는다. (존재 여부 탐색 방지)
            throw new ApiException(ErrorCode.PHOTO_NOT_USABLE);
        }
        return usable;
    }

    /** 커버로 쓸 URL: 썸네일이 있으면 썸네일, 없으면 원본 */
    private String coverUrlOf(Photo photo) {
        String thumb = photo.getThumbnailUrl();
        return (thumb != null && !thumb.isBlank()) ? thumb : photo.getImageUrl();
    }

    // 4) 앨범에 사진 추가 / 제거
    @Transactional
    public int addPhotos(Long userId, Long albumId, List<Long> photoIdList) {
        Album album = albumRepository.findById(albumId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "ALBUM_NOT_FOUND"));

        if (!canManagePhotos(userId, album)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "해당 앨범에 사진을 추가할 권한이 없습니다.");
        }

        // canManagePhotos()는 "이 앨범을 수정할 수 있는가"만 본다.
        // "추가하려는 사진을 쓸 수 있는가"는 완전히 다른 질문이므로 여기서 따로 검증한다.
        List<Photo> photos = requireUsablePhotos(userId, photoIdList);

        if (album.getPhotos() == null) {
            album.setPhotos(new ArrayList<>());
        }

        int count = 0;
        for (Photo p : photos) {
            boolean alreadyExists = album.getPhotos().stream()
                    .anyMatch(existing -> existing.getId().equals(p.getId()));
            if (!alreadyExists) {
                album.getPhotos().add(p);
                count++;
            }
        }

        // 썸네일이 비어 있으면 자동 지정
        autoSetThumbnailIfMissing(album);
        return count;
    }

    @Transactional
    public int removePhotos(Long userId, Long albumId, List<Long> photoIdList) {
        Album album = albumRepository.findById(albumId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "ALBUM_NOT_FOUND"));

        if (!canManagePhotos(userId, album)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "해당 앨범에서 사진을 삭제할 권한이 없습니다.");
        }

        if (album.getPhotos() == null || album.getPhotos().isEmpty()) {
            return 0;
        }

        Set<Long> targetIds = new HashSet<>(photoIdList);

        // 현재 썸네일이 삭제 대상인지 체크
        String currentCover = album.getCoverPhotoUrl();
        boolean coverWillBeRemoved = false;
        if (currentCover != null && !currentCover.isBlank()) {
            coverWillBeRemoved = album.getPhotos().stream()
                    .filter(p -> targetIds.contains(p.getId()))
                    .anyMatch(p -> {
                        String candidate = (p.getThumbnailUrl() != null && !p.getThumbnailUrl().isBlank())
                                ? p.getThumbnailUrl()
                                : p.getImageUrl();
                        return currentCover.equals(candidate);
                    });
        }

        int beforeSize = album.getPhotos().size();
        album.getPhotos().removeIf(p -> targetIds.contains(p.getId()));
        int count = beforeSize - album.getPhotos().size();

        // 남은 사진 기반 썸네일 처리
        if (album.getPhotos().isEmpty()) {
            album.setCoverPhotoUrl(null);
        } else if (coverWillBeRemoved) {
            album.setCoverPhotoUrl(null);
            autoSetThumbnailIfMissing(album);
        }

        return count;
    }

    // 5) 앨범 수정
    @Transactional
    public AlbumDetailResponse updateAlbum(Long userId, Long albumId, UpdateAlbumRequest req) {
        Album album = albumRepository.findById(albumId)
                .orElseThrow(() -> new ApiException(ErrorCode.ALBUM_NOT_FOUND, "ALBUM_NOT_FOUND"));

        // 소유자만 수정 가능 (명세 기준)
        if (album.getUser() == null || !userId.equals(album.getUser().getId())) {
            throw new ApiException(ErrorCode.ALBUM_FORBIDDEN, "해당 앨범을 수정할 권한이 없습니다.");
        }

        // 제목/설명 수정 (null 이면 변경 안 함)
        if (req.getTitle() != null) {
            album.setName(req.getTitle());
        }
        if (req.getDescription() != null) {
            album.setDescription(req.getDescription());
        }

        // coverPhotoId 가 들어온 경우 대표 사진 변경
        if (req.getCoverPhotoId() != null) {
            Long coverPhotoId = req.getCoverPhotoId();

            Photo photo = photoRepository.findByIdAndDeletedIsFalse(coverPhotoId)
                    .orElseThrow(() ->
                            new ApiException(ErrorCode.PHOTO_NOT_FOUND, "대표 사진으로 지정할 사진을 찾을 수 없습니다."));

            boolean inAlbum = album.getPhotos() != null &&
                    album.getPhotos().stream()
                            .filter(p -> Boolean.FALSE.equals(p.getDeleted()))
                            .anyMatch(p -> p.getId().equals(coverPhotoId));

            if (!inAlbum) {
                throw new ApiException(
                        ErrorCode.VALIDATION_FAILED,
                        "대표 사진은 해당 앨범에 포함된 사진만 지정할 수 있습니다."
                );
            }

            String thumb = (photo.getThumbnailUrl() != null && !photo.getThumbnailUrl().isBlank())
                    ? photo.getThumbnailUrl()
                    : photo.getImageUrl();
            album.setCoverPhotoUrl(thumb);
        }

        // coverPhotoId 안 들어온 경우: 비어 있으면 자동 썸네일 채우기
        if (req.getCoverPhotoId() == null) {
            autoSetThumbnailIfMissing(album);
        }

        return toDetail(album, "OWNER");
    }

    @Transactional
    public void deleteAlbum(Long userId, Long albumId) {
        Album album = albumRepository.findById(albumId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "ALBUM_NOT_FOUND"));

        if (album.getUser() == null || !userId.equals(album.getUser().getId())) {
            throw new ApiException(ErrorCode.FORBIDDEN, "해당 앨범을 삭제할 권한이 없습니다.");
        }

        // ✅ 0) 이 앨범과 연결된 공유 정보 전부 삭제
        albumShareRepository.deleteByAlbumId(albumId);

        // ✅ 1) 이 앨범을 즐겨찾기한 기록 전부 삭제
        albumFavoriteRepository.deleteByAlbumId(albumId);

        // ✅ 2) 앨범-사진 연관관계 정리
        if (album.getPhotos() != null && !album.getPhotos().isEmpty()) {
            album.getPhotos().clear();
        }

        // ✅ 3) 앨범 삭제
        albumRepository.delete(album);
    }

    // 6) 앨범 썸네일 생성/지정
    @Transactional
    public AlbumThumbnailResponse updateThumbnail(
            Long userId,
            Long albumId,
            Long photoId,
            MultipartFile file
    ) {
        Album album = albumRepository.findById(albumId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "ALBUM_NOT_FOUND"));

        if (album.getUser() == null || !userId.equals(album.getUser().getId())) {
            throw new ApiException(ErrorCode.FORBIDDEN, "해당 앨범에 접근할 권한이 없습니다.");
        }

        String thumbnailUrl;

        // 1) file 이 있으면 업로드한 이미지로 썸네일 지정
        if (file != null && !file.isEmpty()) {
            try {
                String key = photoStorage.store(file);
                thumbnailUrl = toPublicUrl(key);
            } catch (Exception e) {
                throw new ApiException(
                        ErrorCode.STORAGE_FAILED,
                        "썸네일 파일 업로드 실패: " + e.getMessage(),
                        e
                );
            }
        }
        // 2) photoId 가 있으면 앨범 내 사진을 썸네일로 지정
        else if (photoId != null) {
            Photo photo = photoRepository.findByIdAndDeletedIsFalse(photoId)
                    .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "PHOTO_NOT_FOUND"));

            if (album.getPhotos() == null ||
                    album.getPhotos().stream().noneMatch(p -> p.getId().equals(photoId))) {
                throw new ApiException(ErrorCode.FORBIDDEN, "해당 앨범의 사진이 아닙니다.");
            }

            thumbnailUrl = (photo.getThumbnailUrl() != null && !photo.getThumbnailUrl().isBlank())
                    ? photo.getThumbnailUrl()
                    : photo.getImageUrl();
        }
        // 3) Body 비어 있으면 → 자동 지정
        else {
            thumbnailUrl = pickAutoThumbnailUrl(album);
            if (thumbnailUrl == null) {
                // 앨범에 살아있는 사진이 없는 경우
                throw new ApiException(ErrorCode.NOT_FOUND, "PHOTO_NOT_FOUND");
            }
        }

        album.setCoverPhotoUrl(thumbnailUrl);

        return new AlbumThumbnailResponse(
                album.getId(),
                thumbnailUrl,
                "앨범 썸네일이 성공적으로 설정되었습니다."
        );
    }

    // 7) 즐겨찾기
    private boolean canAccessAlbum(Long userId, Album album) {
        if (album.getUser() != null && userId.equals(album.getUser().getId())) {
            return true;
        }

        return albumShareRepository
                .findByAlbumIdAndUserIdAndStatusAndActiveTrue(album.getId(), userId, Status.ACCEPTED)
                .isPresent();
    }

    private boolean canManagePhotos(Long userId, Album album) {
        if (album.getUser() != null && userId.equals(album.getUser().getId())) {
            return true;
        }

        return albumShareRepository
                .findByAlbumIdAndUserIdAndStatusAndActiveTrue(album.getId(), userId, Status.ACCEPTED)
                .map(AlbumShare::getRole)
                .map(role -> role == AlbumShare.Role.EDITOR || role == AlbumShare.Role.CO_OWNER)
                .orElse(false);
    }

    @Transactional
    public AlbumFavoriteResponse setFavorite(Long userId, Long albumId, boolean favorite) {
        Album album = albumRepository.findById(albumId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "ALBUM_NOT_FOUND"));

        if (!canAccessAlbum(userId, album)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "해당 앨범에 접근할 권한이 없습니다.");
        }

        boolean exists = albumFavoriteRepository.existsByAlbumIdAndUserId(albumId, userId);

        if (favorite) {
            if (!exists) {
                User userRef = em.getReference(User.class, userId);
                AlbumFavorite fav = AlbumFavorite.builder()
                        .album(album)
                        .user(userRef)
                        .build();
                albumFavoriteRepository.save(fav);
            }
            return AlbumFavoriteResponse.builder()
                    .albumId(albumId)
                    .favorited(true)
                    .message("앨범이 즐겨찾기에 추가되었습니다.")
                    .build();
        } else {
            if (exists) {
                albumFavoriteRepository.deleteByAlbumIdAndUserId(albumId, userId);
            }
            return AlbumFavoriteResponse.builder()
                    .albumId(albumId)
                    .favorited(false)
                    .message("앨범 즐겨찾기가 해제되었습니다.")
                    .build();
        }
    }

    // 8) 앨범 전체 사진 다운로드 URL 조회
    public AlbumDownloadUrlsResponse getAlbumDownloadUrls(Long userId, Long albumId) {
        Album album = albumRepository.findById(albumId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.ALBUM_NOT_FOUND,
                        "해당 앨범을 찾을 수 없습니다.")
                );

        if (!canAccessAlbum(userId, album)) {
            throw new ApiException(
                    ErrorCode.FORBIDDEN,
                    "해당 앨범의 사진을 다운로드할 권한이 없습니다."
            );
        }

        List<Photo> photos = (album.getPhotos() == null)
                ? List.of()
                : album.getPhotos().stream()
                .filter(p -> Boolean.FALSE.equals(p.getDeleted()))
                .sorted(Comparator.comparing(Photo::getCreatedAt))
                .toList();

        int seq = 0;   // ✅ 명세: 0부터 시작
        List<AlbumPhotoDownloadUrlDto> photoDtos = new ArrayList<>();

        for (Photo p : photos) {
            String downloadUrl = p.getImageUrl();
            String filename = buildDownloadFilename(p);
            Long fileSize = resolveFileSize(p);

            photoDtos.add(AlbumPhotoDownloadUrlDto.builder()
                    .photoId(p.getId())
                    .sequence(seq++)
                    .downloadUrl(downloadUrl)
                    .filename(filename)
                    .fileSize(fileSize)
                    .build());
        }

        return AlbumDownloadUrlsResponse.builder()
                .albumId(album.getId())
                .albumTitle(album.getName())
                .photoCount(photoDtos.size())
                .photos(photoDtos)
                .build();
    }


    // 내부 유틸
    private String toPublicUrl(String key) {
        if (key == null) return null;
        if (key.startsWith("http://") || key.startsWith("https://")) {
            return key;
        }
        return String.format("%s/files/%s", publicBaseUrl, key);
    }

    /**
     * 목록 화면에 보여줄 커버 URL을 계산한다. (엔티티를 건드리지 않는 읽기 전용 버전)
     *
     * {@link #autoSetThumbnailIfMissing(Album)}과 결과 규칙은 같다.
     *   1) 살아있는 사진이 없으면 커버도 없다
     *   2) 저장된 커버가 아직 살아있는 사진을 가리키면 그대로 쓴다
     *   3) 아니면 가장 최근 사진을 커버로 쓴다
     *
     * 차이는 "앨범 엔티티를 수정하지 않는다"는 점이다. 목록 조회는 읽기인데
     * 기존 코드는 조회하면서 엔티티의 coverPhotoUrl을 바꾸고 있었다.
     */
    private String resolveCoverUrl(String storedCoverUrl, List<AlbumPhotoRow> alivePhotoRows) {
        if (alivePhotoRows.isEmpty()) {
            return null;
        }

        if (storedCoverUrl != null && !storedCoverUrl.isBlank()) {
            boolean stillValid = alivePhotoRows.stream()
                    .anyMatch(row -> storedCoverUrl.equals(row.displayUrl()));
            if (stillValid) {
                return storedCoverUrl;
            }
        }

        return alivePhotoRows.stream()
                .filter(row -> row.createdAt() != null)
                .max(Comparator.comparing(AlbumPhotoRow::createdAt))
                .or(() -> alivePhotoRows.stream().findFirst())
                .map(AlbumPhotoRow::displayUrl)
                .orElse(null);
    }

    /** 앨범의 coverPhotoUrl 자동 설정 로직 */
    private void autoSetThumbnailIfMissing(Album album) {
        // 사진이 아예 없으면 썸네일 제거
        if (album.getPhotos() == null || album.getPhotos().isEmpty()) {
            album.setCoverPhotoUrl(null);
            return;
        }

        // 살아있는 사진만 필터링
        List<Photo> alivePhotos = album.getPhotos().stream()
                .filter(p -> Boolean.FALSE.equals(p.getDeleted()))
                .toList();

        // 살아있는 사진 없으면 썸네일 제거
        if (alivePhotos.isEmpty()) {
            album.setCoverPhotoUrl(null);
            return;
        }

        String cover = album.getCoverPhotoUrl();
        final String coverUrl = cover;   // ← lambda에서 사용할 final 변수

        // 기존 커버가 살아있는 사진을 가리키는지 검증
        if (coverUrl != null && !coverUrl.isBlank()) {
            boolean stillValid = alivePhotos.stream().anyMatch(p -> {
                String candidate = (p.getThumbnailUrl() != null && !p.getThumbnailUrl().isBlank())
                        ? p.getThumbnailUrl()
                        : p.getImageUrl();
                return coverUrl.equals(candidate);
            });

            // 커버가 더 이상 유효하지 않으면 제거
            if (!stillValid) {
                cover = null;
                album.setCoverPhotoUrl(null);
            }
        }

        // cover가 비어 있으면 자동 선정
        if (cover == null || cover.isBlank()) {
            album.setCoverPhotoUrl(pickAutoThumbnailUrl(album));
        }
    }

    private String pickAutoThumbnailUrl(Album album) {
        if (album.getPhotos() == null || album.getPhotos().isEmpty()) return null;

        return album.getPhotos().stream()
                .filter(p -> Boolean.FALSE.equals(p.getDeleted()))
                .sorted(Comparator.comparing(Photo::getCreatedAt).reversed())
                .map(p -> (p.getThumbnailUrl() != null && !p.getThumbnailUrl().isBlank())
                        ? p.getThumbnailUrl()
                        : p.getImageUrl())
                .findFirst()
                .orElse(null);
    }

    private AlbumDetailResponse toDetail(Album album, String role) {
        List<AlbumDetailResponse.PhotoSummary> photoList =
                (album.getPhotos() == null) ? List.of() :
                        album.getPhotos().stream()
                                .filter(p -> Boolean.FALSE.equals(p.getDeleted()))
                                .map(p -> new AlbumDetailResponse.PhotoSummary(
                                        p.getId(),
                                        p.getImageUrl(),
                                        p.getTakenAt(),
                                        p.getLocation(),
                                        p.getBrand()
                                ))
                                .toList();

        int photoCount = photoList.size();

        // ✅ 이 앨범이 현재 다른 사용자와 공유 중인지 여부 (ACCEPTED && active=true 기준)
        boolean sharedFlag = albumShareRepository
                .existsByAlbumIdAndStatusAndActiveTrue(album.getId(), Status.ACCEPTED);

        return AlbumDetailResponse.builder()
                .albumId(album.getId())
                .title(album.getName())
                .description(album.getDescription())
                .coverPhotoUrl(album.getCoverPhotoUrl())
                .photoCount(photoCount)
                .createdAt(album.getCreatedAt())
                .role(role)
                .shared(sharedFlag)
                .photoList(photoList)
                .build();

    }

    /** imageUrl → S3 key 추출 */
    private String extractStorageKeyFromUrl(String url) {
        if (url == null || url.isBlank()) return null;

        String base = publicBaseUrl.replaceAll("/+$", "");
        if (!url.startsWith(base)) {
            return null;
        }

        String path = url.substring(base.length()); // "/files/..."
        if (!path.startsWith("/files/")) {
            return null;
        }
        return path.substring("/files/".length());
    }

    /** photo.imageUrl 기준 파일 크기 조회 */
    private Long resolveFileSize(Photo photo) {
        String key = extractStorageKeyFromUrl(photo.getImageUrl());
        if (key == null) return null;

        if (photoStorage instanceof S3PhotoStorage s3) {
            try {
                return s3.getObjectSize(key);
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    /** 다운로드용 파일 이름 생성 */
    private String buildDownloadFilename(Photo photo) {
        String url = photo.getImageUrl();
        String ext = "jpg";
        if (url != null) {
            try {
                String path = new java.net.URL(url).getPath();
                String name = path.substring(path.lastIndexOf('/') + 1);
                int dot = name.lastIndexOf('.');
                if (dot > 0 && dot < name.length() - 1) {
                    ext = name.substring(dot + 1);
                }
            } catch (Exception ignored) {}
        }
        return "nemo_photo_" + photo.getId() + "." + ext;
    }

}
