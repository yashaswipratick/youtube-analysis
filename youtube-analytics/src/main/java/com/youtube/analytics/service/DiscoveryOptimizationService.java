package com.youtube.analytics.service;

import com.youtube.analytics.model.DiscoveryOptimizationResult;
import com.youtube.analytics.model.VideoAnalyticsResult;
import com.youtube.analytics.model.YouTubeReachReportResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Diagnoses whether weak video discovery is primarily caused by reach or packaging. */
@Service
@RequiredArgsConstructor
public class DiscoveryOptimizationService {

    private static final double STRONG_CTR = 8.0;
    private static final double HEALTHY_CTR = 5.0;
    private static final double WEAK_CTR = 2.5;
    private static final double STRONG_REACH_IMPRESSIONS_PER_DAY = 10000.0;
    private static final double HEALTHY_REACH_IMPRESSIONS_PER_DAY = 3000.0;
    private static final double WEAK_REACH_IMPRESSIONS_PER_DAY = 100.0;

    private final YouTubeAnalyticsService analyticsService;
    private final YouTubeReachReportingService reachReportingService;

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

        return DiscoveryOptimizationResult.builder()
                .videoId(videoId).views(views).impressions(impressions)
                .impressionsClickThroughRate(ctr).viewsPerDay(viewsPerDay)
                .impressionsPerDay(impressionsPerDay)
                .reachStatus(reachStatus).packagingStatus(packagingStatus)
                .primaryDiagnosis(diagnosis)
                .recommendations(buildRecommendations(diagnosis, ctr, impressions))
                .missingData(missingData)
                .build();
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
        if (!missingData.isEmpty()) return DiscoveryOptimizationResult.DiscoveryDiagnosis.INSUFFICIENT_DATA;
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
            Long impressions) {
        List<String> recommendations = new ArrayList<>();
        switch (diagnosis) {
            case LOW_REACH -> recommendations.add("Improve distribution: strengthen the topic, audience targeting, and early viewer response before changing the title or thumbnail.");
            case LOW_CTR -> recommendations.add("Improve packaging: test a clearer title and thumbnail that communicate the video's strongest outcome.");
            case LOW_REACH_AND_LOW_CTR -> {
                recommendations.add("First improve the topic/distribution signal so the video earns more relevant impressions.");
                recommendations.add("Then test a stronger title and thumbnail to improve impression-to-view conversion.");
            }
            case HEALTHY_DISCOVERY -> recommendations.add("Discovery signals are healthy; preserve the current packaging pattern and focus next on retention and watch time.");
            case INSUFFICIENT_DATA -> recommendations.add("Collect views, impressions, and impression click-through rate before making a discovery or packaging decision.");
        }
        if (impressions != null && impressions == 0) {
            recommendations.add("No impressions were recorded, so CTR is not actionable yet.");
        } else if (ctr != null && ctr >= STRONG_CTR) {
            recommendations.add("CTR is strong; avoid changing the packaging solely to chase more clicks.");
        }
        return recommendations;
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
