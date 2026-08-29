package com.youtube.analytics.service;

import com.youtube.analytics.model.NormalizedVideoAnalytics;
import com.youtube.analytics.model.VideoPerformanceScore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VideoPerformanceScoringServiceTest {

    private final DerivedMetricsCalculator calculator = new DerivedMetricsCalculator();
    private final VideoPerformanceScoringService service = new VideoPerformanceScoringService(calculator);

    @Test
    void scoresVideoRelativeToComparisonSet() {
        NormalizedVideoAnalytics low = video("low", 10, 1, 0, 0, 0, 1);
        NormalizedVideoAnalytics target = video("target", 50, 10, 2, 2, 0, 50);
        NormalizedVideoAnalytics high = video("high", 100, 10, 5, 5, 1, 150);

        VideoPerformanceScore score = service.score(target, List.of(low, target, high));

        assertThat(score.getScore()).isGreaterThan(50.0);
        assertThat(score.getPercentile()).isEqualTo(score.getScore());
        assertThat(score.getPerformance()).isIn(
                VideoPerformanceScore.PerformanceBand.ABOVE_AVERAGE,
                VideoPerformanceScore.PerformanceBand.TOP_PERFORMER);
    }

    @Test
    void returnsInsufficientDataForEmptyComparisonSet() {
        VideoPerformanceScore score = service.score(video("target", 50, 5, 1, 1, 0, 50), List.of());

        assertThat(score.getScore()).isZero();
        assertThat(score.getPercentile()).isZero();
        assertThat(score.getPerformance())
                .isEqualTo(VideoPerformanceScore.PerformanceBand.INSUFFICIENT_DATA);
    }

    @Test
    void handlesMissingMetricsWithoutFailing() {
        NormalizedVideoAnalytics video = NormalizedVideoAnalytics.builder()
                .videoId("missing")
                .aggregateMetrics(Map.of())
                .build();

        VideoPerformanceScore score = service.score(video, List.of(video));

        assertThat(score.getPerformance())
                .isEqualTo(VideoPerformanceScore.PerformanceBand.INSUFFICIENT_DATA);
    }

    private NormalizedVideoAnalytics video(String id, long views, long likes,
                                           long comments, long gained, long lost,
                                           long minutes) {
        return NormalizedVideoAnalytics.builder()
                .videoId(id)
                .aggregateMetrics(Map.of(
                        "views", views,
                        "likes", likes,
                        "comments", comments,
                        "subscribersGained", gained,
                        "subscribersLost", lost,
                        "estimatedMinutesWatched", minutes))
                .build();
    }
}
