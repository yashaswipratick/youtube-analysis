package com.youtube.analytics.service;

import com.youtube.analytics.model.DerivedVideoMetrics;
import com.youtube.analytics.model.NormalizedVideoAnalytics;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/** Calculates safe, deterministic metrics from normalized analytics data. */
@Component
public class DerivedMetricsCalculator {

    private static final int SCALE = 2;

    public DerivedVideoMetrics calculate(NormalizedVideoAnalytics analytics) {
        Map<String, Object> metrics = analytics == null || analytics.getAggregateMetrics() == null
                ? Map.of()
                : analytics.getAggregateMetrics();

        Long views = number(metrics.get("views"));
        Long likes = number(metrics.get("likes"));
        Long comments = number(metrics.get("comments"));
        Long subscribersGained = number(metrics.get("subscribersGained"));
        Long subscribersLost = number(metrics.get("subscribersLost"));
        Long estimatedMinutesWatched = number(metrics.get("estimatedMinutesWatched"));

        return DerivedVideoMetrics.builder()
                .likeRate(percentage(likes, views))
                .commentRate(percentage(comments, views))
                .subscriberConversionRate(percentage(subscribersGained, views))
                .netSubscribers(subscribersGained == null && subscribersLost == null
                        ? null : valueOrZero(subscribersGained) - valueOrZero(subscribersLost))
                .averageWatchTimePerViewSeconds(averageWatchTime(estimatedMinutesWatched, views))
                .build();
    }

    private Double percentage(Long numerator, Long denominator) {
        if (numerator == null || denominator == null || denominator <= 0) return null;
        return round((double) numerator * 100.0 / denominator);
    }

    private Double averageWatchTime(Long estimatedMinutesWatched, Long views) {
        if (estimatedMinutesWatched == null || views == null || views <= 0) return null;
        return round((double) estimatedMinutesWatched * 60.0 / views);
    }

    private Long number(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value instanceof String string) {
            try {
                return Long.parseLong(string);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private long valueOrZero(Long value) {
        return value == null ? 0L : value;
    }

    private double round(double value) {
        return BigDecimal.valueOf(value).setScale(SCALE, RoundingMode.HALF_UP).doubleValue();
    }
}
