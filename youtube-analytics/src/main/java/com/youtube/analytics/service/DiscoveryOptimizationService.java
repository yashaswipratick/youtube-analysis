package com.youtube.analytics.service;

import com.youtube.analytics.model.DailyVideoAnalyticsResult;
import com.youtube.analytics.model.DiscoveryOptimizationResult;
import com.youtube.analytics.model.RetentionAnalysisResult;
import com.youtube.analytics.model.VideoAnalyticsResult;
import com.youtube.analytics.model.VideoRetentionAnalyticsResult;
import com.youtube.analytics.model.YouTubeReachReportResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Diagnoses video discovery using reach, packaging, retention, and view momentum signals. */
@Service
@RequiredArgsConstructor
public class DiscoveryOptimizationService {

    private static final double STRONG_CTR = 8.0;
    private static final double HEALTHY_CTR = 5.0;
    private static final double WEAK_CTR = 2.5;
    private static final double STRONG_REACH_IMPRESSIONS_PER_DAY = 10000.0;
    private static final double HEALTHY_REACH_IMPRESSIONS_PER_DAY = 3000.0;
    private static final double WEAK_REACH_IMPRESSIONS_PER_DAY = 100.0;
    private static final double MOMENTUM_STABLE_THRESHOLD_PERCENT = 10.0;

    private final YouTubeAnalyticsService analyticsService;
    private final YouTubeReachReportingService reachReportingService;
    private final RetentionAnalysisService retentionAnalysisService;

    public DiscoveryOptimizationResult analyze(String videoId, String startDate, String endDate) {
        VideoAnalyticsResult analytics = analyticsService.getSingleVideoAnalytics(
                videoId, startDate, endDate, List.of("views"));
        YouTubeReachReportResult reach = reachReportingService.getVideoReach(videoId, startDate, endDate);

        Map<String, Object> metrics = analytics.getMetrics();
        Long views = toLong(metrics.get("views"));
        Long impressions = reach.impressions();
        Double ctr = reach.impressionsClickThroughRate();

        List<String> missingData = new ArrayList<>();
        if (views == null) missingData.add("views");
        if (impressions == null) missingData.add("impressions");
        if (ctr == null) missingData.add("impressionsClickThroughRate");

        double days = inclusiveDays(startDate, endDate);
        Double viewsPerDay = views == null ? null : views / days;
        Double impressionsPerDay = impressions == null ? null : impressions / days;
        DiscoveryOptimizationResult.DiscoveryStatus reachStatus = classifyReach(impressionsPerDay);
        DiscoveryOptimizationResult.DiscoveryStatus packagingStatus = classifyCtr(ctr);
        DiscoveryOptimizationResult.DiscoveryDiagnosis diagnosis = diagnose(reachStatus, packagingStatus, missingData);

        RetentionAnalysisResult retentionAnalysis = analyzeRetention(videoId, startDate, endDate);
        appendRetentionMissingData(missingData, retentionAnalysis);
        DailyVideoAnalyticsResult dailyAnalytics = null;
        try {
            dailyAnalytics = analyticsService.getDailyVideoAnalytics(videoId, startDate, endDate, List.of("views"));
        } catch (RuntimeException ex) {
            // Momentum is supplemental; discovery diagnosis should remain available when the daily report is unavailable.
        }
        DiscoveryOptimizationResult.ViewVelocity viewVelocity = calculateViewVelocity(dailyAnalytics);

        return DiscoveryOptimizationResult.builder()
                .videoId(videoId).views(views).impressions(impressions)
                .impressionsClickThroughRate(ctr).viewsPerDay(viewsPerDay)
                .impressionsPerDay(impressionsPerDay)
                .reachStatus(reachStatus).packagingStatus(packagingStatus)
                .primaryDiagnosis(diagnosis)
                .retentionAnalysis(retentionAnalysis)
                .viewVelocity(viewVelocity)
                .recommendations(buildRecommendations(diagnosis, ctr, impressions, retentionAnalysis, viewVelocity))
                .missingData(missingData)
                .build();
    }

    private RetentionAnalysisResult analyzeRetention(String videoId, String startDate, String endDate) {
        try {
            VideoRetentionAnalyticsResult retention = analyticsService.getVideoRetentionAnalytics(videoId, startDate, endDate);
            return retentionAnalysisService.analyze(retention);
        } catch (RuntimeException ex) {
            return retentionAnalysisService.analyze(null);
        }
    }

    private void appendRetentionMissingData(List<String> missingData, RetentionAnalysisResult retentionAnalysis) {
        if (retentionAnalysis == null || retentionAnalysis.missingData() == null) return;
        retentionAnalysis.missingData().forEach(item -> {
            String normalized = "retention:" + item;
            if (!missingData.contains(normalized)) missingData.add(normalized);
        });
    }

    private DiscoveryOptimizationResult.DiscoveryStatus classifyReach(Double impressionsPerDay) {
        if (impressionsPerDay == null) return DiscoveryOptimizationResult.DiscoveryStatus.UNKNOWN;
        if (impressionsPerDay >= STRONG_REACH_IMPRESSIONS_PER_DAY) return DiscoveryOptimizationResult.DiscoveryStatus.STRONG;
        if (impressionsPerDay >= HEALTHY_REACH_IMPRESSIONS_PER_DAY) return DiscoveryOptimizationResult.DiscoveryStatus.HEALTHY;
        if (impressionsPerDay >= WEAK_REACH_IMPRESSIONS_PER_DAY) return DiscoveryOptimizationResult.DiscoveryStatus.WEAK;
        return DiscoveryOptimizationResult.DiscoveryStatus.CRITICAL;
    }

    private DiscoveryOptimizationResult.DiscoveryStatus classifyCtr(Double ctr) {
        if (ctr == null) return DiscoveryOptimizationResult.DiscoveryStatus.UNKNOWN;
        if (ctr >= STRONG_CTR) return DiscoveryOptimizationResult.DiscoveryStatus.STRONG;
        if (ctr >= HEALTHY_CTR) return DiscoveryOptimizationResult.DiscoveryStatus.HEALTHY;
        if (ctr >= WEAK_CTR) return DiscoveryOptimizationResult.DiscoveryStatus.WEAK;
        return DiscoveryOptimizationResult.DiscoveryStatus.CRITICAL;
    }

    private DiscoveryOptimizationResult.DiscoveryDiagnosis diagnose(
            DiscoveryOptimizationResult.DiscoveryStatus reach,
            DiscoveryOptimizationResult.DiscoveryStatus packaging,
            List<String> missingData) {
        if (missingData.stream().anyMatch(item -> !item.startsWith("retention:"))) {
            return DiscoveryOptimizationResult.DiscoveryDiagnosis.INSUFFICIENT_DATA;
        }
        boolean lowReach = reach == DiscoveryOptimizationResult.DiscoveryStatus.WEAK
                || reach == DiscoveryOptimizationResult.DiscoveryStatus.CRITICAL;
        boolean lowCtr = packaging == DiscoveryOptimizationResult.DiscoveryStatus.WEAK
                || packaging == DiscoveryOptimizationResult.DiscoveryStatus.CRITICAL;
        if (lowReach && lowCtr) return DiscoveryOptimizationResult.DiscoveryDiagnosis.LOW_REACH_AND_LOW_CTR;
        if (lowReach) return DiscoveryOptimizationResult.DiscoveryDiagnosis.LOW_REACH;
        if (lowCtr) return DiscoveryOptimizationResult.DiscoveryDiagnosis.LOW_CTR;
        return DiscoveryOptimizationResult.DiscoveryDiagnosis.HEALTHY_DISCOVERY;
    }

    private List<String> buildRecommendations(
            DiscoveryOptimizationResult.DiscoveryDiagnosis diagnosis,
            Double ctr,
            Long impressions,
            RetentionAnalysisResult retentionAnalysis,
            DiscoveryOptimizationResult.ViewVelocity viewVelocity) {
        List<String> recommendations = new ArrayList<>();
        switch (diagnosis) {
            case LOW_REACH -> recommendations.add("Improve distribution: strengthen the topic, audience targeting, and early viewer response before changing the title or thumbnail.");
            case LOW_CTR -> recommendations.add("Improve packaging: test a clearer title and thumbnail that communicate the video's strongest outcome.");
            case LOW_REACH_AND_LOW_CTR -> {
                recommendations.add("First improve the topic/distribution signal so the video earns more relevant impressions.");
                recommendations.add("Then test a stronger title and thumbnail to improve impression-to-view conversion.");
            }
            case HEALTHY_DISCOVERY -> recommendations.add("Discovery signals are healthy; preserve the current packaging pattern and focus next on retention and watch time.");
            case INSUFFICIENT_DATA -> recommendations.add("Reach report data is not available yet. Retry after the YouTube Reporting API has generated the report.");
        }

        addRetentionRecommendation(recommendations, retentionAnalysis, diagnosis);
        addMomentumRecommendation(recommendations, viewVelocity);

        if (impressions != null && impressions == 0) {
            recommendations.add("No impressions were recorded, so CTR is not actionable yet.");
        } else if (ctr != null && ctr >= STRONG_CTR) {
            recommendations.add("CTR is strong; avoid changing the packaging solely to chase more clicks.");
        }
        return recommendations;
    }

    private void addRetentionRecommendation(List<String> recommendations, RetentionAnalysisResult retention,
                                             DiscoveryOptimizationResult.DiscoveryDiagnosis diagnosis) {
        if (retention == null || retention.overallSeverity() == RetentionAnalysisResult.RetentionSeverity.UNKNOWN) return;
        if (diagnosis == DiscoveryOptimizationResult.DiscoveryDiagnosis.LOW_CTR
                && (retention.overallSeverity() == RetentionAnalysisResult.RetentionSeverity.HEALTHY
                || retention.overallSeverity() == RetentionAnalysisResult.RetentionSeverity.STRONG)) {
            recommendations.add("Retention is relatively strong while CTR is weak; prioritize title and thumbnail experiments before changing the content itself.");
        } else if (diagnosis == DiscoveryOptimizationResult.DiscoveryDiagnosis.LOW_REACH
                && (retention.overallSeverity() == RetentionAnalysisResult.RetentionSeverity.HEALTHY
                || retention.overallSeverity() == RetentionAnalysisResult.RetentionSeverity.STRONG)) {
            recommendations.add("Retention is relatively strong while reach is weak; investigate topic fit and distribution before rewriting the content.");
        } else if (retention.overallSeverity() == RetentionAnalysisResult.RetentionSeverity.CRITICAL
                || retention.overallSeverity() == RetentionAnalysisResult.RetentionSeverity.WEAK) {
            recommendations.add("Retention is weak; address the strongest retention drops before relying on packaging changes to improve performance.");
        }
    }

    private void addMomentumRecommendation(List<String> recommendations, DiscoveryOptimizationResult.ViewVelocity velocity) {
        if (velocity == null) return;
        switch (velocity.status()) {
            case ACCELERATING -> recommendations.add("View momentum is accelerating; avoid premature changes while the current video is gaining pace.");
            case DECELERATING -> recommendations.add("View momentum is decelerating; use the retention and discovery signals to prioritize the next packaging or distribution experiment.");
            case STABLE, INSUFFICIENT_DATA -> { }
        }
    }

    private DiscoveryOptimizationResult.ViewVelocity calculateViewVelocity(DailyVideoAnalyticsResult dailyAnalytics) {
        if (dailyAnalytics == null || dailyAnalytics.getDays() == null) {
            return new DiscoveryOptimizationResult.ViewVelocity(null, null, null,
                    DiscoveryOptimizationResult.MomentumStatus.INSUFFICIENT_DATA);
        }
        List<DailyVideoAnalyticsResult.DailyMetricRow> rows = dailyAnalytics.getDays().stream()
                .filter(row -> row != null && row.getMetrics() != null && toLong(row.getMetrics().get("views")) != null)
                .sorted(Comparator.comparing(DailyVideoAnalyticsResult.DailyMetricRow::getDate, Comparator.nullsLast(String::compareTo)))
                .toList();
        if (rows.size() < 4) {
            return new DiscoveryOptimizationResult.ViewVelocity(null, null, null,
                    DiscoveryOptimizationResult.MomentumStatus.INSUFFICIENT_DATA);
        }
        int split = rows.size() / 2;
        double previousPerDay = rows.subList(0, split).stream().mapToLong(row -> toLong(row.getMetrics().get("views"))).average().orElse(0.0);
        double recentPerDay = rows.subList(split, rows.size()).stream().mapToLong(row -> toLong(row.getMetrics().get("views"))).average().orElse(0.0);
        if (previousPerDay == 0.0) {
            return new DiscoveryOptimizationResult.ViewVelocity(recentPerDay, previousPerDay, null,
                    recentPerDay > 0.0
                            ? DiscoveryOptimizationResult.MomentumStatus.ACCELERATING
                            : DiscoveryOptimizationResult.MomentumStatus.STABLE);
        }
        double change = ((recentPerDay - previousPerDay) / previousPerDay) * 100.0;
        DiscoveryOptimizationResult.MomentumStatus status = Math.abs(change) <= MOMENTUM_STABLE_THRESHOLD_PERCENT
                ? DiscoveryOptimizationResult.MomentumStatus.STABLE
                : change > 0
                ? DiscoveryOptimizationResult.MomentumStatus.ACCELERATING
                : DiscoveryOptimizationResult.MomentumStatus.DECELERATING;
        return new DiscoveryOptimizationResult.ViewVelocity(recentPerDay, previousPerDay, change, status);
    }

    private Long toLong(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value == null) return null;
        try { return Long.parseLong(String.valueOf(value)); }
        catch (NumberFormatException ex) { return null; }
    }

    private double inclusiveDays(String startDate, String endDate) {
        if (startDate == null || endDate == null) return 1.0;
        try {
            long days = java.time.temporal.ChronoUnit.DAYS.between(
                    java.time.LocalDate.parse(startDate), java.time.LocalDate.parse(endDate)) + 1;
            return Math.max(days, 1L);
        } catch (RuntimeException ex) {
            return 1.0;
        }
    }
}
