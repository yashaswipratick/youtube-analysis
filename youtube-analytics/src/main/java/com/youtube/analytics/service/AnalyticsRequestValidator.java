package com.youtube.analytics.service;

import com.youtube.analytics.exception.InvalidAnalyticsRequestException;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Validation rules shared by controller and service before a YouTube API request is made. */
public final class AnalyticsRequestValidator {
    private static final String DATE_FORMAT = "uuuu-MM-dd";
    private static final DateTimeFormatter STRICT_DATE = DateTimeFormatter
            .ofPattern(DATE_FORMAT)
            .withResolverStyle(ResolverStyle.STRICT);
    private static final Set<String> VIDEO_METRICS = Set.of(
            "views", "engagedViews", "estimatedMinutesWatched", "averageViewDuration",
            "averageViewPercentage", "likes", "comments", "shares", "subscribersGained",
            "subscribersLost");

    private AnalyticsRequestValidator() {
    }

    public static void validateVideoId(String videoId) {
        if (videoId == null || !videoId.matches("[A-Za-z0-9_-]{6,128}")) {
            throw new InvalidAnalyticsRequestException("videoId must be a non-blank YouTube video ID");
        }
    }

    public static void validateProvidedDates(String startDate, String endDate) {
        LocalDate start = startDate == null || startDate.isBlank() ? null : parseDate("startDate", startDate);
        LocalDate end = endDate == null || endDate.isBlank() ? null : parseDate("endDate", endDate);
        if (start != null && end != null && start.isAfter(end)) {
            throw new InvalidAnalyticsRequestException("startDate must be on or before endDate");
        }
    }

    public static LocalDate parseDate(String field, String value) {
        try {
            if (!value.matches("\\d{4}-\\d{2}-\\d{2}")) {
                throw new InvalidAnalyticsRequestException(field + " must be yyyy-MM-dd");
            }
            return LocalDate.parse(value, STRICT_DATE);
        } catch (DateTimeParseException ex) {
            throw new InvalidAnalyticsRequestException(field + " must be a valid calendar date in yyyy-MM-dd format");
        }
    }

    public static List<String> validateVideoMetrics(List<String> metrics) {
        LinkedHashSet<String> uniqueMetrics = new LinkedHashSet<>();
        for (String metric : metrics) {
            if (metric == null || metric.isBlank() || !VIDEO_METRICS.contains(metric.trim())) {
                throw new InvalidAnalyticsRequestException("Unsupported video analytics metric: " + metric);
            }
            uniqueMetrics.add(metric.trim());
        }
        if (uniqueMetrics.isEmpty()) {
            throw new InvalidAnalyticsRequestException("At least one analytics metric is required");
        }
        return List.copyOf(uniqueMetrics);
    }
}
