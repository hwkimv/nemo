// backend/src/main/java/com/nemo/backend/domain/timeline/service/TimelineServiceImpl.java
package com.nemo.backend.domain.timeline.service;

import com.nemo.backend.domain.photo.dto.PhotoResponseDto;
import com.nemo.backend.domain.photo.entity.Photo;
import com.nemo.backend.domain.photo.repository.PhotoRepository;
import com.nemo.backend.domain.timeline.dto.TimelineDayResponse;
import com.nemo.backend.domain.timeline.dto.TimelinePhotoItem;
import com.nemo.backend.domain.timeline.dto.TimelapseDayResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TimelineServiceImpl implements TimelineService {

    private final PhotoRepository photoRepository;

    @Override
    public List<TimelineDayResponse> getTimeline(Long userId, Integer year, Integer month) {
        // 기간이 정해져 있으면 DB에서 그 기간만 읽는다. (예전에는 전체를 읽고 Java에서 버렸다)
        List<Photo> photos = loadPhotosForTimeline(userId, year, month);

        Map<LocalDate, List<TimelinePhotoItem>> grouped = new LinkedHashMap<>();

        for (Photo photo : photos) {
            LocalDate date = resolveDate(photo);
            if (date == null) continue;

            // year / month 선택적 필터링 (day 없음)
            // DB 조회로 이미 좁혀진 경우 아래 조건은 통과만 하지만,
            // "월만 지정(연도 무관)" 같은 범위로 표현할 수 없는 경우를 위해 남겨둔다.
            if (year != null && date.getYear() != year) continue;
            if (month != null && date.getMonthValue() != month) continue;

            PhotoResponseDto dto = new PhotoResponseDto(photo);

            TimelinePhotoItem item = new TimelinePhotoItem(
                    dto.getId(),
                    dto.getImageUrl(),
                    dto.getLocation(),
                    dto.getBrand()
            );

            grouped.computeIfAbsent(date, d -> new ArrayList<>()).add(item);
        }

        // LinkedHashMap 순서 유지 → takenAt DESC 순으로 날짜 그룹 반환
        return grouped.entrySet().stream()
                .map(entry -> new TimelineDayResponse(
                        entry.getKey().toString(), // "YYYY-MM-DD"
                        entry.getValue()
                ))
                .collect(Collectors.toList());
    }

    @Override
    public List<TimelapseDayResponse> getTimelapse(Long userId, int year, int month) {
        // 타임랩스는 항상 특정 연·월이므로 언제나 DB 기간 조회로 끝난다.
        List<Photo> photos = photoRepository.findForPeriod(
                userId, monthStart(year, month), monthStart(year, month).plusMonths(1));

        Map<LocalDate, DayStats> statsMap = new HashMap<>();

        for (Photo photo : photos) {
            LocalDate date = resolveDate(photo);
            if (date == null) continue;

            PhotoResponseDto dto = new PhotoResponseDto(photo);

            DayStats stats = statsMap.computeIfAbsent(date, d -> new DayStats());
            stats.count++;

            if (stats.thumbnailUrl == null || stats.thumbnailUrl.isBlank()) {
                String candidate = dto.getThumbnailUrl();
                if (candidate == null || candidate.isBlank()) {
                    candidate = dto.getImageUrl();
                }
                stats.thumbnailUrl = candidate;
            }
        }

        LocalDate firstDay = LocalDate.of(year, month, 1);
        int daysInMonth = firstDay.lengthOfMonth();

        List<TimelapseDayResponse> result = new ArrayList<>(daysInMonth);
        for (int d = 1; d <= daysInMonth; d++) {
            LocalDate date = LocalDate.of(year, month, d);
            DayStats stats = statsMap.get(date);

            boolean hasPhoto = stats != null && stats.count > 0;
            String thumbnailUrl = hasPhoto ? stats.thumbnailUrl : null;
            int photoCount = hasPhoto ? stats.count : 0;

            result.add(new TimelapseDayResponse(
                    date.toString(),   // "YYYY-MM-DD"
                    hasPhoto,
                    thumbnailUrl,
                    photoCount
            ));
        }

        return result;
    }

    /**
     * 타임라인이 실제로 필요한 사진만 읽어온다.
     *
     * - 연·월이 모두 있으면: 그 달 한 달치만
     * - 연도만 있으면: 그 해 1년치만
     * - 연도 없이 월만 있으면(= 모든 해의 N월): 하나의 연속 구간으로 표현할 수 없어 기존처럼 전체 조회
     * - 둘 다 없으면: 원래 의미가 전체 조회
     *
     * 12월 다음은 다음 해 1월이라는 경계 처리는 LocalDateTime.plusMonths/plusYears가 처리한다.
     * 직접 month+1 로 계산했다면 12월에서 13월이 되어 터졌을 자리다.
     */
    private List<Photo> loadPhotosForTimeline(Long userId, Integer year, Integer month) {
        if (year != null && month != null) {
            LocalDateTime start = monthStart(year, month);
            return photoRepository.findForPeriod(userId, start, start.plusMonths(1));
        }
        if (year != null) {
            LocalDateTime start = LocalDate.of(year, 1, 1).atStartOfDay();
            return photoRepository.findForPeriod(userId, start, start.plusYears(1));
        }
        return photoRepository.findByUserIdAndDeletedIsFalseOrderByTakenAtDesc(userId);
    }

    /** 해당 연·월 1일 00:00 */
    private LocalDateTime monthStart(int year, int month) {
        return LocalDate.of(year, month, 1).atStartOfDay();
    }

    private LocalDate resolveDate(Photo photo) {
        if (photo.getTakenAt() != null) {
            return photo.getTakenAt().toLocalDate();
        }
        if (photo.getCreatedAt() != null) {
            return photo.getCreatedAt().toLocalDate();
        }
        return null;
    }

    private static class DayStats {
        int count = 0;
        String thumbnailUrl;
    }
}
