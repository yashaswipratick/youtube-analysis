package com.youtube.analytics.service;

import com.youtube.analytics.model.RecommendationResult;
import com.youtube.analytics.model.VideoAnalyticsResult;
import com.youtube.analytics.model.VideoRetentionAnalyticsResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RecommendationEngineServiceTest {

    @Test
    void detectsEarlyRetentionDropFromCurve() {
        YouTubeAnalyticsService analyticsService = mock(YouTubeAnalyticsService.class);
        when(analyticsService.getSingleVideoAnalytics(any(), any(), any(), any()))
                .thenReturn(VideoAnalyticsResult.builder()
                        .videoId("video-1")
                        .metrics(Map.of(
                                "views", 57,
                                "likes", 9,
                                "estimatedMinutesWatched", 116,
                                "subscribersGained", 0,
                                "subscribersLost", 0))
                        .build());

        VideoRetentionAnalyticsResult retention = VideoRetentionAnalyticsResult.builder()
                .videoId("video-1")
                .averageViewPercentage(24.99)
                .averageViewDurationSeconds(122.0)
                .retention(List.of(
                        point(0.01, 0.9464),
                        point(0.02, 0.7143),
                        point(0.05, 0.5000),
                        point(0.08, 0.4107),
                        point(0.11, 0.4643),
                        point(0.49, 0.2857),
                        point(0.53, 0.3036)))
                .build();
        when(analyticsService.getVideoRetentionAnalytics(any(), any(), any())).thenReturn(retention);

        RecommendationEngineService engine = new RecommendationEngineService(analyticsService);
        RecommendationResult result = engine.recommend("video-1", "2026-07-27", "2026-08-29");

        RecommendationResult.Recommendation retentionRecommendation = result.recommendations().stream()
                .filter(r -> r.area() == RecommendationResult.Area.RETENTION)
                .findFirst()
                .orElseThrow();

        assertThat(retentionRecommendation.priority()).isEqualTo(RecommendationResult.Priority.HIGH);
        assertThat(retentionRecommendation.type()).isEqualTo(RecommendationResult.RecommendationType.EXPERIMENTAL);
        assertThat(retentionRecommendation.confidence()).isEqualTo(RecommendationResult.Confidence.HIGH);
        assertThat(retentionRecommendation.evidence())
                .contains("94.64%", "1%", "50.00%", "5%", "24.99%");
        assertThat(retentionRecommendation.recommendation()).contains("outcome-first opening");
        assertThat(retentionRecommendation.measurement()).contains("first retention segment");
    }

    @Test
    void fallsBackToAverageViewPercentageWhenRetentionCurveIsEmpty() {
        YouTubeAnalyticsService analyticsService = mock(YouTubeAnalyticsService.class);
        when(analyticsService.getSingleVideoAnalytics(any(), any(), any(), any()))
                .thenReturn(VideoAnalyticsResult.builder().videoId("video-1")
                        .metrics(Map.of("views", 57, "likes", 9, "estimatedMinutesWatched", 116)).build());
        when(analyticsService.getVideoRetentionAnalytics(any(), any(), any()))
                .thenReturn(VideoRetentionAnalyticsResult.builder()
                        .videoId("video-1")
                        .averageViewPercentage(24.99)
                        .retention(List.of())
                        .build());

        RecommendationEngineService engine = new RecommendationEngineService(analyticsService);
        RecommendationResult result = engine.recommend("video-1", "2026-07-27", "2026-08-29");

        RecommendationResult.Recommendation retentionRecommendation = result.recommendations().stream()
                .filter(r -> r.area() == RecommendationResult.Area.RETENTION)
                .findFirst()
                .orElseThrow();

        assertThat(retentionRecommendation.evidence()).contains("Average view percentage is 24.99%");
    }

    private VideoRetentionAnalyticsResult.RetentionPoint point(double ratio, double audienceWatchRatio) {
        return VideoRetentionAnalyticsResult.RetentionPoint.builder()
                .elapsedVideoTimeRatio(ratio)
                .audienceWatchRatio(audienceWatchRatio)
                .build();
    }
}
