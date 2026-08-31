package com.youtube.analytics.service;

import com.youtube.analytics.model.RetentionAnalysisResult;
import com.youtube.analytics.model.VideoRetentionAnalyticsResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RetentionAnalysisServiceTest {

    private final RetentionAnalysisService service = new RetentionAnalysisService();

    @Test
    void identifiesCriticalEarlyDropAndMeaningfulSegments() {
        VideoRetentionAnalyticsResult retention = retention(15.57, List.of(
                point(0.01, 0.9234),
                point(0.02, 0.6699),
                point(0.05, 0.4928),
                point(0.10, 0.3589),
                point(0.20, 0.2967),
                point(0.30, 0.2440),
                point(0.40, 0.1770),
                point(0.50, 0.1866),
                point(0.60, 0.1435),
                point(0.70, 0.1340),
                point(0.80, 0.1100),
                point(0.90, 0.0909),
                point(1.00, 0.0622)));

        RetentionAnalysisResult result = service.analyze(retention);

        assertThat(result.overallSeverity()).isEqualTo(RetentionAnalysisResult.RetentionSeverity.CRITICAL);
        assertThat(result.earlyDrop()).isNotNull();
        assertThat(result.earlyDrop().changePercentagePoints()).isEqualTo(-43.06);
        assertThat(result.segments()).hasSize(11);
        assertThat(result.segments().get(0).signal()).isEqualTo("MEANINGFUL_DROP");
        assertThat(result.recommendations()).anyMatch(r -> r.contains("Prioritize the opening"));
    }

    @Test
    void doesNotCallLastWindowTheBestStableSectionWhenItIsOnlyEndRetention() {
        VideoRetentionAnalyticsResult retention = retention(42.0, List.of(
                point(0.01, 0.90), point(0.05, 0.82), point(0.10, 0.70),
                point(0.20, 0.62), point(0.30, 0.60), point(0.40, 0.58),
                point(0.50, 0.55), point(0.60, 0.54), point(0.70, 0.53),
                point(0.80, 0.52), point(0.90, 0.50), point(1.00, 0.20)));

        RetentionAnalysisResult result = service.analyze(retention);

        assertThat(result.strongestSection()).isNotNull();
        assertThat(result.strongestSection().fromVideoPercent()).isLessThan(50.0);
        assertThat(result.weakestSection()).isNotNull();
        assertThat(result.endRetention()).isNotNull();
    }

    @Test
    void ignoresSmallRecoveryNoiseButKeepsMeaningfulRecovery() {
        VideoRetentionAnalyticsResult retention = retention(40.0, List.of(
                point(0.01, 0.80), point(0.10, 0.50), point(0.20, 0.40),
                point(0.30, 0.42), point(0.40, 0.44), point(0.50, 0.43),
                point(0.60, 0.35), point(0.70, 0.30)));

        RetentionAnalysisResult result = service.analyze(retention);

        assertThat(result.recoverySections()).hasSize(1);
        assertThat(result.recoverySections().get(0).changePercentagePoints()).isEqualTo(4.0);
    }

    @Test
    void handlesMissingCurve() {
        VideoRetentionAnalyticsResult retention = retention(24.0, List.of());

        RetentionAnalysisResult result = service.analyze(retention);

        assertThat(result.overallSeverity()).isEqualTo(RetentionAnalysisResult.RetentionSeverity.WEAK);
        assertThat(result.segments()).isEmpty();
        assertThat(result.missingData()).contains("At least two retention curve points are required for drop, recovery, and section analysis.");
    }

    private VideoRetentionAnalyticsResult retention(double average, List<VideoRetentionAnalyticsResult.RetentionPoint> points) {
        return VideoRetentionAnalyticsResult.builder()
                .videoId("video-1")
                .averageViewPercentage(average)
                .averageViewDurationSeconds(127.0)
                .retention(points)
                .build();
    }

    private VideoRetentionAnalyticsResult.RetentionPoint point(double elapsed, double audience) {
        return VideoRetentionAnalyticsResult.RetentionPoint.builder()
                .elapsedVideoTimeRatio(elapsed)
                .audienceWatchRatio(audience)
                .build();
    }
}
