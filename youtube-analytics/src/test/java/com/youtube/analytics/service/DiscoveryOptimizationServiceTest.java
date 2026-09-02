package com.youtube.analytics.service;

import com.youtube.analytics.model.DiscoveryOptimizationResult;
import com.youtube.analytics.model.VideoAnalyticsResult;
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
    private final DiscoveryOptimizationService service =
            new DiscoveryOptimizationService(analyticsService, reachReportingService);

    @Test
    void identifiesLowCtrWhenReachIsHealthy() {
        when(analyticsService.getSingleVideoAnalytics("abc123", "2026-08-01", "2026-08-10", List.of("views")))
                .thenReturn(video(5000L, "2026-08-01", "2026-08-10"));
        when(reachReportingService.getVideoReach("abc123", "2026-08-01", "2026-08-10"))
                .thenReturn(new YouTubeReachReportResult(100000L, 2.0, true));

        DiscoveryOptimizationResult result = service.analyze("abc123", "2026-08-01", "2026-08-10");

        assertThat(result.getReachStatus()).isEqualTo(DiscoveryOptimizationResult.DiscoveryStatus.STRONG);
        assertThat(result.getPackagingStatus()).isEqualTo(DiscoveryOptimizationResult.DiscoveryStatus.CRITICAL);
        assertThat(result.getPrimaryDiagnosis()).isEqualTo(DiscoveryOptimizationResult.DiscoveryDiagnosis.LOW_CTR);
        assertThat(result.getMissingData()).isEmpty();
    }

    @Test
    void identifiesLowReachWhenCtrIsHealthy() {
        when(analyticsService.getSingleVideoAnalytics("abc123", "2026-08-01", "2026-08-10", List.of("views")))
                .thenReturn(video(100L, "2026-08-01", "2026-08-10"));
        when(reachReportingService.getVideoReach("abc123", "2026-08-01", "2026-08-10"))
                .thenReturn(new YouTubeReachReportResult(1000L, 6.0, true));

        DiscoveryOptimizationResult result = service.analyze("abc123", "2026-08-01", "2026-08-10");

        assertThat(result.getReachStatus()).isEqualTo(DiscoveryOptimizationResult.DiscoveryStatus.WEAK);
        assertThat(result.getPackagingStatus()).isEqualTo(DiscoveryOptimizationResult.DiscoveryStatus.HEALTHY);
        assertThat(result.getPrimaryDiagnosis()).isEqualTo(DiscoveryOptimizationResult.DiscoveryDiagnosis.LOW_REACH);
    }

    @Test
    void reportsInsufficientDataWhenReachReportIsNotAvailable() {
        when(analyticsService.getSingleVideoAnalytics("abc123", "2026-08-01", "2026-08-10", List.of("views")))
                .thenReturn(video(100L, "2026-08-01", "2026-08-10"));
        when(reachReportingService.getVideoReach("abc123", "2026-08-01", "2026-08-10"))
                .thenReturn(YouTubeReachReportResult.unavailable());

        DiscoveryOptimizationResult result = service.analyze("abc123", "2026-08-01", "2026-08-10");

        assertThat(result.getPrimaryDiagnosis()).isEqualTo(DiscoveryOptimizationResult.DiscoveryDiagnosis.INSUFFICIENT_DATA);
        assertThat(result.getMissingData()).containsExactly("impressions", "impressionsClickThroughRate");
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
}
