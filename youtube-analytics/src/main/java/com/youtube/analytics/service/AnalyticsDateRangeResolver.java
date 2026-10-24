package com.youtube.analytics.service;

import com.youtube.analytics.exception.InvalidAnalyticsRequestException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Resolves a recent YouTube Analytics reporting window from lastNDays.
 * All windows are inclusive and use YouTube's America/Los_Angeles reporting day.
 */
@Service
@RequiredArgsConstructor
public class AnalyticsDateRangeResolver {

    public static final int DEFAULT_LAST_N_DAYS = 28;
    public static final int MAX_LAST_N_DAYS = 3650;
    public static final ZoneId REPORTING_ZONE = ZoneId.of("America/Los_Angeles");

    private final YouTubePublicApiService youtubePublicApiService;

    public DateRange resolve(Integer lastNDays) {
        int days = lastNDays == null ? DEFAULT_LAST_N_DAYS : lastNDays;
        if (days < 1 || days > MAX_LAST_N_DAYS) {
            throw new InvalidAnalyticsRequestException(
                    "lastNDays must be between 1 and " + MAX_LAST_N_DAYS);
        }
        LocalDate end = LocalDate.now(REPORTING_ZONE);
        return new DateRange(end.minusDays(days - 1L), end);
    }

    /**
     * Resolve the requested recent window and clamp its start to the video's
     * actual publication reporting day. The publication date comes from the
     * same local service that powers data.publish_date on video-details.
     */
    public DateRange resolveForVideo(String videoId, Integer lastNDays) {
        AnalyticsRequestValidator.validateVideoId(videoId);
        DateRange requested = resolve(lastNDays);
        String publishedAt = youtubePublicApiService.getVideoPublishDate(videoId);
        if (publishedAt == null || publishedAt.isBlank()) {
            return requested;
        }

        final LocalDate publishDay;
        try {
            publishDay = Instant.parse(publishedAt).atZone(REPORTING_ZONE).toLocalDate();
        } catch (RuntimeException ex) {
            throw new InvalidAnalyticsRequestException(
                    "Video publish_date returned by YouTube is not a valid timestamp");
        }

        if (publishDay.isAfter(requested.endDate())) {
            throw new InvalidAnalyticsRequestException("The video has not been published yet");
        }

        LocalDate start = requested.startDate().isBefore(publishDay)
                ? publishDay : requested.startDate();
        return new DateRange(start, requested.endDate());
    }

    public record DateRange(LocalDate startDate, LocalDate endDate) {
        public String startDateText() {
            return startDate.toString();
        }

        public String endDateText() {
            return endDate.toString();
        }
    }
}
