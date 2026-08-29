package com.youtube.analytics.service;

import com.youtube.analytics.model.DerivedVideoMetrics;
import com.youtube.analytics.model.NormalizedVideoAnalytics;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DerivedMetricsCalculatorTest {

    private final DerivedMetricsCalculator calculator = new DerivedMetricsCalculator();

    @Test
    void calculatesDerivedMetrics() {
        NormalizedVideoAnalytics analytics = NormalizedVideoAnalytics.builder()
                .aggregateMetrics(Map.of(
                        "views", 57,
                        "likes", 9,
                        "comments", 2,
                        "subscribersGained", 3,
                        "subscribersLost", 1,
                        "estimatedMinutesWatched", 116))
                .build();

        DerivedVideoMetrics result = calculator.calculate(analytics);

        assertThat(result.getLikeRate()).isEqualTo(15.79);
        assertThat(result.getCommentRate()).isEqualTo(3.51);
        assertThat(result.getSubscriberConversionRate()).isEqualTo(5.26);
        assertThat(result.getNetSubscribers()).isEqualTo(2L);
        assertThat(result.getAverageWatchTimePerViewSeconds()).isEqualTo(122.11);
    }

    @Test
    void returnsNullRateWhenViewsAreMissingOrZero() {
        NormalizedVideoAnalytics analytics = NormalizedVideoAnalytics.builder()
                .aggregateMetrics(Map.of("views", 0, "likes", 5))
                .build();

        DerivedVideoMetrics result = calculator.calculate(analytics);

        assertThat(result.getLikeRate()).isNull();
        assertThat(result.getCommentRate()).isNull();
        assertThat(result.getSubscriberConversionRate()).isNull();
        assertThat(result.getAverageWatchTimePerViewSeconds()).isNull();
    }

    @Test
    void handlesMissingAggregateMetrics() {
        DerivedVideoMetrics result = calculator.calculate(
                NormalizedVideoAnalytics.builder().aggregateMetrics(null).build());

        assertThat(result.getLikeRate()).isNull();
        assertThat(result.getCommentRate()).isNull();
        assertThat(result.getSubscriberConversionRate()).isNull();
        assertThat(result.getNetSubscribers()).isNull();
        assertThat(result.getAverageWatchTimePerViewSeconds()).isNull();
    }
}
