// backend/src/main/java/com/nemo/backend/domain/album/service/AlbumShareService.java
package com.nemo.backend.domain.album.service;

import com.nemo.backend.domain.album.dto.*;
import com.nemo.backend.domain.album.entity.Album;
import com.nemo.backend.domain.album.entity.AlbumShare;
import com.nemo.backend.domain.album.entity.AlbumShare.Role;
import com.nemo.backend.domain.album.entity.AlbumShare.Status;
import com.nemo.backend.domain.album.repository.AlbumRepository;
import com.nemo.backend.domain.album.repository.AlbumShareRepository;
import com.nemo.backend.domain.friend.entity.FriendStatus;
import com.nemo.backend.domain.friend.repository.FriendRepository;
import com.nemo.backend.domain.user.entity.User;
import com.nemo.backend.domain.user.repository.UserRepository;
import com.nemo.backend.global.exception.ApiException;
import com.nemo.backend.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional
public class AlbumShareService {

    private final AlbumRepository albumRepository;
    private final AlbumShareRepository albumShareRepository;
    private final FriendRepository friendRepository;
    private final UserRepository userRepository;

    /**
     * 앨범 내에서의 실질적인 역할
     * - OWNER  : album.user (AlbumShare row 없음)
     * - CO_OWNER / EDITOR / VIEWER : AlbumShare.Role 기반
     */
    private enum EffectiveRole {
        OWNER,
        CO_OWNER,
        EDITOR,
        VIEWER
    }

    /**
     * 현재 사용자의 EffectiveRole 계산
     * - 앨범 소유자이면 OWNER
     * - 그렇지 않으면 ACCEPTED && active=true 인 AlbumShare 를 조회
     *   없으면 FORBIDDEN
     */
    private EffectiveRole resolveEffectiveRole(Album album, Long userId) {
        if (album.getUser() != null && album.getUser().getId().equals(userId)) {
            return EffectiveRole.OWNER;
        }

        AlbumShare myShare = albumShareRepository
                .findByAlbumIdAndUserIdAndStatusAndActiveTrue(album.getId(), userId, Status.ACCEPTED)
                .orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN, "해당 앨범의 공유 멤버가 아닙니다."));

        return switch (myShare.getRole()) {
            case CO_OWNER -> EffectiveRole.CO_OWNER;
            case EDITOR -> EffectiveRole.EDITOR;
            case VIEWER -> EffectiveRole.VIEWER;
        };
    }

    /**
     * 특정 공유 레코드의 EffectiveRole 계산
     * (원칙적으로 OWNER 는 AlbumShare 에 저장되지 않지만 방어적으로 한 번 더 체크)
     */
    private EffectiveRole resolveEffectiveRoleForShare(Album album, AlbumShare share) {
        if (album.getUser() != null && album.getUser().getId().equals(share.getUser().getId())) {
            return EffectiveRole.OWNER;
        }
        return switch (share.getRole()) {
            case CO_OWNER -> EffectiveRole.CO_OWNER;
            case EDITOR -> EffectiveRole.EDITOR;
            case VIEWER -> EffectiveRole.VIEWER;
        };
    }

    /**
     * 권한 변경 가능 여부
     * - OWNER  : CO_OWNER ~ VIEWER 모두 변경 가능
     * - CO_OWNER : EDITOR ~ VIEWER 변경 가능
     * - EDITOR / VIEWER : 변경 불가
     */
    private boolean canChangeMemberRole(EffectiveRole actor, EffectiveRole target) {
        return switch (actor) {
            case OWNER -> target == EffectiveRole.CO_OWNER
                    || target == EffectiveRole.EDITOR
                    || target == EffectiveRole.VIEWER;
            case CO_OWNER -> target == EffectiveRole.EDITOR
                    || target == EffectiveRole.VIEWER;
            default -> false;
        };
    }

    /**
     * 강퇴 가능 여부
     * - OWNER  : CO_OWNER ~ VIEWER 모두 강퇴 가능
     * - CO_OWNER : EDITOR ~ VIEWER 강퇴 가능
     * - EDITOR / VIEWER : 강퇴 불가
     */
    private boolean canKickMember(EffectiveRole actor, EffectiveRole target) {
        return switch (actor) {
            case OWNER -> target == EffectiveRole.CO_OWNER
                    || target == EffectiveRole.EDITOR
                    || target == EffectiveRole.VIEWER;
            case CO_OWNER -> target == EffectiveRole.EDITOR
                    || target == EffectiveRole.VIEWER;
            default -> false;
        };
    }

    @Transactional(readOnly = true)
    public Album getAlbum(Long albumId) {
        return albumRepository.findById(albumId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "ALBUM_NOT_FOUND"));
    }

    /**
     * "관리 권한"이 필요한 작업용 (공유 요청 보내기, 공유 링크 생성 등)
     * - OWNER
     * - CO_OWNER
     */
    private Album getAlbumWithManagePermission(Long albumId, Long meId) {
        Album album = getAlbum(albumId);

        if (album.getUser().getId().equals(meId)) {
            return album;
        }

        AlbumShare myShare = albumShareRepository
                .findByAlbumIdAndUserIdAndStatusAndActiveTrue(albumId, meId, Status.ACCEPTED)
                .orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN, "앨범 공유 관리 권한이 없습니다."));

        if (myShare.getRole() != Role.CO_OWNER) {
            throw new ApiException(ErrorCode.FORBIDDEN, "앨범 공유 관리 권한이 없습니다.");
        }

        return album;
    }

    // 공유 요청 보내기
    public AlbumShareResponse shareAlbum(Long albumId, Long meId, AlbumShareRequest req) {
        Album album = getAlbumWithManagePermission(albumId, meId);

        if (req.getFriendIdList() == null || req.getFriendIdList().isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "friendIdList 가 비어 있습니다.");
        }

        Role defaultRole = Role.VIEWER;

        List<Long> friendIds = req.getFriendIdList().stream().distinct().toList();

        List<AlbumShare> toSave = new ArrayList<>();

        for (Long targetId : friendIds) {
            User target = userRepository.findById(targetId)
                    .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "존재하지 않는 사용자가 포함되어 있습니다."));

            boolean isFriend =
                    friendRepository.existsByUserIdAndFriendIdAndStatus(meId, targetId, FriendStatus.ACCEPTED) ||
                            friendRepository.existsByUserIdAndFriendIdAndStatus(targetId, meId, FriendStatus.ACCEPTED);

            if (!isFriend) {
                throw new ApiException(
                        ErrorCode.INVALID_REQUEST,
                        "친구 관계가 아닌 사용자에게는 앨범을 공유할 수 없습니다. userId=" + targetId
                );
            }

            Optional<AlbumShare> existingOpt =
                    albumShareRepository.findByAlbumIdAndUserId(albumId, targetId);

            if (existingOpt.isPresent()) {
                AlbumShare existing = existingOpt.get();

                if (Boolean.TRUE.equals(existing.getActive()) &&
                        (existing.getStatus() == Status.PENDING || existing.getStatus() == Status.ACCEPTED)) {
                    continue;
                }

                existing.setActive(true);
                existing.setStatus(Status.PENDING);
                existing.setRole(defaultRole);

                toSave.add(existing);
            } else {
                AlbumShare share = AlbumShare.builder()
                        .album(album)
                        .user(target)
                        .role(defaultRole)
                        .status(Status.PENDING)
                        .active(true)
                        .build();
                toSave.add(share);
            }
        }

        if (toSave.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "이미 모두 공유된 사용자입니다.");
        }

        albumShareRepository.saveAll(toSave);

        List<AlbumShareResponse.SharedTarget> sharedTo = toSave.stream()
                .map(share -> AlbumShareResponse.SharedTarget.builder()
                        .userId(share.getUser().getId())
                        .nickname(share.getUser().getNickname())
                        .build())
                .toList();

        return AlbumShareResponse.builder()
                .albumId(album.getId())
                .sharedTo(sharedTo)
                .message("앨범이 선택한 친구들에게 성공적으로 공유되었습니다.")
                .build();
    }

    /**
     * 공유 멤버 목록 조회
     * - OWNER
     * - 공유 멤버(ACCEPTED && active=true) → role 이 OWNER / CO_OWNER / EDITOR / VIEWER 여도 모두 조회 가능
     */
    @Transactional(readOnly = true)
    public List<AlbumShareResponse.SharedUser> getShareTargets(Long albumId, Long meId) {
        Album album = getAlbum(albumId);

        // 🔐 멤버 조회 권한 체크 (예외 발생 시 403)
        resolveEffectiveRole(album, meId);

        List<AlbumShareResponse.SharedUser> result = new ArrayList<>();

        // 1) 소유자
        User owner = album.getUser();
        result.add(AlbumShareResponse.SharedUser.builder()
                .userId(owner.getId())
                .nickname(owner.getNickname())
                .role("OWNER")
                .build()
        );

        // 2) ACCEPTED 상태인 공유 멤버
        albumShareRepository.findByAlbumIdAndStatusAndActiveTrue(albumId, Status.ACCEPTED)
                .forEach(share -> result.add(
                        AlbumShareResponse.SharedUser.builder()
                                .userId(share.getUser().getId())
                                .nickname(share.getUser().getNickname())
                                .role(share.getRole().name())
                                .build()
                ));

        return result;
    }

    /**
     * 공유 멤버 권한 변경
     * - OWNER  : CO_OWNER ~ VIEWER 모두 변경 가능
     * - CO_OWNER : EDITOR ~ VIEWER 권한 변경 가능
     * - EDITOR / VIEWER : 변경 불가
     */
    public AlbumShare updateShareRoleByUserId(Long albumId, Long targetUserId, Long meId, Role newRole) {
        Album album = getAlbum(albumId);

        AlbumShare share = albumShareRepository
                .findByAlbumIdAndUserIdAndActiveTrue(albumId, targetUserId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "SHARE_NOT_FOUND"));

        if (!share.getAlbum().getId().equals(album.getId())) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "앨범 정보가 일치하지 않습니다.");
        }
        if (!Boolean.TRUE.equals(share.getActive()) || share.getStatus() != Status.ACCEPTED) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "활성화된 공유가 아닙니다.");
        }

        EffectiveRole actorRole = resolveEffectiveRole(album, meId);
        EffectiveRole targetRole = resolveEffectiveRoleForShare(album, share);

        // 🔒 CO_OWNER 는 다른 사용자를 CO_OWNER 로 승격시킬 수 없다
        if (actorRole == EffectiveRole.CO_OWNER && newRole == Role.CO_OWNER) {
            throw new ApiException(ErrorCode.FORBIDDEN, "CO_OWNER 는 다른 사용자를 CO_OWNER 로 변경할 수 없습니다.");
        }

        if (!canChangeMemberRole(actorRole, targetRole)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "공유 멤버 권한을 변경할 수 없습니다.");
        }

        share.setRole(newRole);
        return share;
    }


    /**
     * 공유 해제 / 강퇴
     * - 본인(target == meId) : 누구나 언제든지 나가기 가능
     * - OWNER  : CO_OWNER ~ VIEWER 강퇴 가능
     * - CO_OWNER : EDITOR ~ VIEWER 강퇴 가능
     * - EDITOR / VIEWER : 강퇴 불가
     */
    public Long unshare(Long albumId, Long targetUserId, Long meId) {
        Album album = getAlbum(albumId);

        AlbumShare share = albumShareRepository
                .findByAlbumIdAndUserIdAndActiveTrue(albumId, targetUserId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "SHARE_NOT_FOUND"));

        if (!Boolean.TRUE.equals(share.getActive())) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "이미 비활성화된 공유입니다.");
        }

        boolean selfUnshare = targetUserId.equals(meId);

        if (selfUnshare) {
            // ✅ 본인은 언제든지 나갈 수 있음
            if (!share.getUser().getId().equals(meId)) {
                throw new ApiException(ErrorCode.FORBIDDEN, "본인 공유가 아닙니다.");
            }
        } else {
            // ✅ 타인 강퇴
            EffectiveRole actorRole = resolveEffectiveRole(album, meId);
            EffectiveRole targetRole = resolveEffectiveRoleForShare(album, share);

            if (!canKickMember(actorRole, targetRole)) {
                throw new ApiException(ErrorCode.FORBIDDEN, "해당 사용자를 앨범에서 제거할 권한이 없습니다.");
            }
        }

        Long removedUserId = share.getUser().getId();
        share.setActive(false);
        share.setStatus(Status.REJECTED);

        return removedUserId;
    }

    @Transactional(readOnly = true)
    public List<PendingShareResponse> getPendingShares(Long meId) {
        return albumShareRepository
                .findByUserIdAndStatusAndActiveTrue(meId, Status.PENDING)
                .stream()
                .map(PendingShareResponse::from)
                .toList();
    }

    private void acceptShareInternal(AlbumShare share, Long meId) {
        if (!share.getUser().getId().equals(meId)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "본인에게 온 공유만 수락할 수 있습니다.");
        }
        if (share.getStatus() != Status.PENDING || !Boolean.TRUE.equals(share.getActive())) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "이미 처리된 공유 요청입니다.");
        }

        share.setStatus(Status.ACCEPTED);
    }

    private void rejectShareInternal(AlbumShare share, Long meId) {
        if (!share.getUser().getId().equals(meId)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "본인에게 온 공유만 거절할 수 있습니다.");
        }
        if (share.getStatus() != Status.PENDING || !Boolean.TRUE.equals(share.getActive())) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "이미 처리된 공유 요청입니다.");
        }

        share.setStatus(Status.REJECTED);
        share.setActive(false);
    }

    public AcceptShareResponse acceptShareByAlbum(Long albumId, Long meId) {
        AlbumShare share = albumShareRepository
                .findByAlbumIdAndUserIdAndStatusAndActiveTrue(albumId, meId, Status.PENDING)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "SHARE_NOT_FOUND"));

        acceptShareInternal(share, meId);

        return AcceptShareResponse.builder()
                .albumId(albumId)
                .role(share.getRole().name())
                .message("앨범 공유를 수락했습니다.")
                .build();
    }

    public RejectShareResponse rejectShareByAlbum(Long albumId, Long meId) {
        AlbumShare share = albumShareRepository
                .findByAlbumIdAndUserIdAndStatusAndActiveTrue(albumId, meId, Status.PENDING)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "SHARE_NOT_FOUND"));

        rejectShareInternal(share, meId);

        return RejectShareResponse.builder()
                .albumId(albumId)
                .message("앨범 공유 요청을 거절했습니다.")
                .build();
    }

    // 이 메서드는 내부용/추후용이라 HTTP 매핑은 제거했음
    @Transactional(readOnly = true)
    public List<SharedAlbumSummaryResponse> getMySharedAlbums(Long meId) {
        List<AlbumShare> shares = albumShareRepository
                .findByUserIdAndStatusAndActiveTrue(meId, Status.ACCEPTED);

        return shares.stream()
                .map(share -> {
                    Album album = share.getAlbum();
                    int photoCount = (album.getPhotos() == null) ? 0 : album.getPhotos().size();
                    String coverUrl = album.getCoverPhotoUrl();
                    return SharedAlbumSummaryResponse.from(album, share, coverUrl, photoCount);
                })
                .toList();
    }

    public AlbumShareLinkResponse createShareLink(Long albumId, Long meId) {
        Album album = getAlbumWithManagePermission(albumId, meId);
        String url = "https://nemo.app/share/albums/" + album.getId();
        return new AlbumShareLinkResponse(album.getId(), url);
    }
}
