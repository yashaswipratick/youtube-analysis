package com.youtube.analytics.service;

import com.youtube.analytics.model.DailyVideoAnalyticsResult;
import com.youtube.analytics.model.DiscoveryOptimizationResult;
import com.youtube.analytics.model.RetentionAnalysisResult;
import com.youtube.analytics.model.VideoAnalyticsResult;
import com.youtube.analytics.model.VideoRetentionAnalyticsResult;
import com.youtube.analytics.model.YouTubeReachReportResult;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DiscoveryOptimizationServiceTest {

    private final YouTubeAnalyticsService analyticsService = mock(YouTubeAnalyticsService.class);
    private final YouTubeReachReportingService reachReportingService = mock(YouTubeReachReportingService.class);
    private final RetentionAnalysisService retentionAnalysisService = mock(RetentionAnalysisService.class);
    private final DiscoveryOptimizationService service =
            new DiscoveryOptimizationService(analyticsService, reachReportingService, retentionAnalysisService);

    @Test
    void identifiesLowCtrWhenReachIsStrong() {
        stubVideo(5000L);
        stubReach(new YouTubeReachReportResult(100000L, 2.0, true));

        DiscoveryOptimizationResult result = service.analyze("abc123", "2026-08-01", "2026-08-10");

        assertThat(result.getReachStatus()).isEqualTo(DiscoveryOptimizationResult.DiscoveryStatus.STRONG);
        assertThat(result.getPackagingStatus()).isEqualTo(DiscoveryOptimizationResult.DiscoveryStatus.CRITICAL);
        assertThat(result.getPrimaryDiagnosis()).isEqualTo(DiscoveryOptimizationResult.DiscoveryDiagnosis.LOW_CTR);
        assertThat(result.getMissingData()).isEmpty();
    }

    @Test
    void identifiesLowReachWhenCtrIsHealthy() {
        stubVideo(100L);
        stubReach(new YouTubeReachReportResult(1000L, 6.0, true));

        DiscoveryOptimizationResult result = service.analyze("abc123", "2026-08-01", "2026-08-10");

        assertThat(result.getReachStatus()).isEqualTo(DiscoveryOptimizationResult.DiscoveryStatus.WEAK);
        assertThat(result.getPackagingStatus()).isEqualTo(DiscoveryOptimizationResult.DiscoveryStatus.HEALTHY);
        assertThat(result.getPrimaryDiagnosis()).isEqualTo(DiscoveryOptimizationResult.DiscoveryDiagnosis.LOW_REACH);
    }

    @Test
    void identifiesLowReachAndLowCtrTogether() {
        stubVideo(100L);
        stubReach(new YouTubeReachReportResult(500L, 2.0, true));

        DiscoveryOptimizationResult result = service.analyze("abc123", "2026-08-01", "2026-08-10");

        assertThat(result.getPrimaryDiagnosis()).isEqualTo(DiscoveryOptimizationResult.DiscoveryDiagnosis.LOW_REACH_AND_LOW_CTR);
    }

    @Test
    void identifiesHealthyDiscoveryWhenReachAndCtrAreStrong() {
        stubVideo(5000L);
        stubReach(new YouTubeReachReportResult(100000L, 9.0, true));

        DiscoveryOptimizationResult result = service.analyze("abc123", "2026-08-01", "2026-08-10");

        assertThat(result.getReachStatus()).isEqualTo(DiscoveryOptimizationResult.DiscoveryStatus.STRONG);
        assertThat(result.getPackagingStatus()).isEqualTo(DiscoveryOptimizationResult.DiscoveryStatus.STRONG);
        assertThat(result.getPrimaryDiagnosis()).isEqualTo(DiscoveryOptimizationResult.DiscoveryDiagnosis.HEALTHY_DISCOVERY);
    }

    @Test
    void reportsInsufficientDataWhenReachReportIsNotAvailable() {
        stubVideo(100L);
        stubReach(YouTubeReachReportResult.unavailable());

        DiscoveryOptimizationResult result = service.analyze("abc123", "2026-08-01", "2026-08-10");

        assertThat(result.getPrimaryDiagnosis()).isEqualTo(DiscoveryOptimizationResult.DiscoveryDiagnosis.INSUFFICIENT_DATA);
        assertThat(result.getMissingData()).containsExactly("impressions", "impressionsClickThroughRate");
    }

    @Test
    void combinesStrongRetentionWithLowCtrToPrioritizePackaging() {
        stubVideo(500L);
        stubReach(new YouTubeReachReportResult(100000L, 2.0, true));
        RetentionAnalysisResult retention = retentionAnalysis(RetentionAnalysisResult.RetentionSeverity.STRONG);
        when(analyticsService.getVideoRetentionAnalytics("abc123", "2026-08-01", "2026-08-10"))
                .thenReturn(VideoRetentionAnalyticsResult.builder().videoId("abc123").averageViewPercentage(60.0).build());
        when(retentionAnalysisService.analyze(org.mockito.ArgumentMatchers.any(VideoRetentionAnalyticsResult.class)))
                .thenReturn(retention);

        DiscoveryOptimizationResult result = service.analyze("abc123", "2026-08-01", "2026-08-10");

        assertThat(result.getRetentionAnalysis()).isSameAs(retention);
        assertThat(result.getRecommendations()).anyMatch(r -> r.contains("prioritize title and thumbnail"));
    }

    @Test
    void calculatesAcceleratingViewMomentumFromDailyViews() {
        stubVideo(1000L);
        stubReach(new YouTubeReachReportResult(100000L, 9.0, true));
        when(analyticsService.getDailyVideoAnalytics("abc123", "2026-08-01", "2026-08-10", List.of("views")))
                .thenReturn(daily(100, 100, 100, 100, 200, 200, 200, 200));

        DiscoveryOptimizationResult result = service.analyze("abc123", "2026-08-01", "2026-08-10");

        assertThat(result.getViewVelocity()).isNotNull();
        assertThat(result.getViewVelocity().previousViewsPerDay()).isEqualTo(100.0);
        assertThat(result.getViewVelocity().recentViewsPerDay()).isEqualTo(200.0);
        assertThat(result.getViewVelocity().changePercent()).isEqualTo(100.0);
        assertThat(result.getViewVelocity().status()).isEqualTo(DiscoveryOptimizationResult.MomentumStatus.ACCELERATING);
    }

    private void stubVideo(Long views) {
        when(analyticsService.getSingleVideoAnalytics("abc123", "2026-08-01", "2026-08-10", List.of("views")))
                .thenReturn(video(views, "2026-08-01", "2026-08-10"));
    }

    private void stubReach(YouTubeReachReportResult reach) {
        when(reachReportingService.getVideoReach("abc123", "2026-08-01", "2026-08-10"))
                .thenReturn(reach);
    }

    private VideoAnalyticsResult video(Long views, String startDate, String endDate) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("views", views);
        return VideoAnalyticsResult.builder()
                .videoId("abc123")
                .startDate(startDate)
                .endDate(endDate)
                .metrics(metrics)
                .build();
    }

    private RetentionAnalysisResult retentionAnalysis(RetentionAnalysisResult.RetentionSeverity severity) {
        return new RetentionAnalysisResult("abc123", 120.0, 60.0, severity, null, null, null, null, List.of(), null, List.of(), List.of(), List.of());
    }

    private DailyVideoAnalyticsResult daily(long... views) {
        List<DailyVideoAnalyticsResult.DailyMetricRow> rows = new java.util.ArrayList<>();
        for (int i = 0; i < views.length; i++) {
            rows.add(DailyVideoAnalyticsResult.DailyMetricRow.builder()
                    .date("2026-08-" + String.format("%02d", i + 1))
                    .metrics(Map.of("views", views[i]))
                    .build());
        }
        return DailyVideoAnalyticsResult.builder().videoId("abc123").days(rows).build();
    }
}
