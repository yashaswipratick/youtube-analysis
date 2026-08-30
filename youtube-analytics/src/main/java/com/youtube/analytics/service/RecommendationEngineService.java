package com.youtube.analytics.service;

import com.youtube.analytics.model.RecommendationResult;
import com.youtube.analytics.model.VideoAnalyticsResult;
import com.youtube.analytics.model.VideoRetentionAnalyticsResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Deterministic 7G recommendation engine. AI interpretation is intentionally separate. */
@Service
@RequiredArgsConstructor
public class RecommendationEngineService {

    private final YouTubeAnalyticsService analyticsService;

    public RecommendationResult recommend(String videoId, String startDate, String endDate) {
        VideoAnalyticsResult video = analyticsService.getSingleVideoAnalytics(videoId, startDate, endDate, null);
        Map<String, Object> metrics = video.getMetrics() == null ? Map.of() : video.getMetrics();
        List<RecommendationResult.Recommendation> recommendations = new ArrayList<>();
        List<String> missingData = new ArrayList<>();

        addEngagementRecommendation(metrics, recommendations, missingData);
        addSubscriberRecommendation(metrics, recommendations, missingData);
        addWatchTimeRecommendation(metrics, recommendations, missingData);
        addDiscoveryRecommendation(metrics, recommendations, missingData);

        VideoRetentionAnalyticsResult retention = null;
        try {
            retention = analyticsService.getVideoRetentionAnalytics(videoId, startDate, endDate);
        } catch (RuntimeException ex) {
            missingData.add("Retention data could not be retrieved for this recommendation run.");
        }

        addRetentionRecommendation(retention, recommendations, missingData);
        addMissingData(metrics, retention, missingData);

        recommendations.sort(Comparator.comparing(RecommendationResult.Recommendation::priority));

        String summary = recommendations.isEmpty()
                ? "No evidence-based recommendation can be generated from the supplied analytics metrics."
                : "Recommendations are based only on metrics returned by YouTube Analytics; missing metrics are not inferred.";

        return new RecommendationResult(videoId, summary, recommendations, missingData);
    }

    private void addEngagementRecommendation(Map<String, Object> metrics,
                                              List<RecommendationResult.Recommendation> recommendations,
                                              List<String> missingData) {
        Number likes = number(metrics.get("likes"));
        Number views = number(metrics.get("views"));
        if (likes != null && views != null && views.doubleValue() > 0) {
            double likeRate = likes.doubleValue() / views.doubleValue();
            if (likeRate >= 0.05) {
                recommendations.add(new RecommendationResult.Recommendation(
                        RecommendationResult.Priority.MEDIUM,
                        RecommendationResult.Area.ENGAGEMENT,
                        "Preserve the engagement-oriented elements of the video in future content.",
                        RecommendationResult.RecommendationType.EVIDENCE_BASED,
                        "The supplied like-to-view ratio is " + formatPercent(likeRate) + ".",
                        RecommendationResult.Confidence.MEDIUM,
                        "Compare like-to-view rate across future videos with the same content style."));
            } else {
                recommendations.add(new RecommendationResult.Recommendation(
                        RecommendationResult.Priority.MEDIUM,
                        RecommendationResult.Area.ENGAGEMENT,
                        "Test a stronger viewer-engagement prompt or story payoff in future videos.",
                        RecommendationResult.RecommendationType.EXPERIMENTAL,
                        "The supplied like-to-view ratio is " + formatPercent(likeRate) + "; no benchmark was supplied.",
                        RecommendationResult.Confidence.LOW,
                        "Compare engagement rate against future videos without assuming a causal lift."));
            }
        } else {
            missingData.add("Views and likes are required to evaluate like-to-view engagement.");
        }
    }

    private void addSubscriberRecommendation(Map<String, Object> metrics,
                                              List<RecommendationResult.Recommendation> recommendations,
                                              List<String> missingData) {
        Number gained = number(metrics.get("subscribersGained"));
        Number lost = number(metrics.get("subscribersLost"));
        if (gained != null) {
            String evidence = "Subscribers gained: " + gained;
            if (lost != null) evidence += "; subscribers lost: " + lost;
            recommendations.add(new RecommendationResult.Recommendation(
                    RecommendationResult.Priority.LOW,
                    RecommendationResult.Area.SUBSCRIBER_CONVERSION,
                    "Use subscriber conversion as a success signal when comparing future videos.",
                    RecommendationResult.RecommendationType.EVIDENCE_BASED,
                    evidence + ".",
                    RecommendationResult.Confidence.MEDIUM,
                    "Compare net subscriber change per video over a consistent reporting period."));
        } else {
            missingData.add("Subscribers gained/lost are not available, so subscriber conversion cannot be evaluated.");
        }
    }

    private void addWatchTimeRecommendation(Map<String, Object> metrics,
                                             List<RecommendationResult.Recommendation> recommendations,
                                             List<String> missingData) {
        Number watchMinutes = number(metrics.get("estimatedMinutesWatched"));
        Number views = number(metrics.get("views"));
        if (watchMinutes != null && views != null && views.doubleValue() > 0) {
            double minutesPerView = watchMinutes.doubleValue() / views.doubleValue();
            recommendations.add(new RecommendationResult.Recommendation(
                    RecommendationResult.Priority.MEDIUM,
                    RecommendationResult.Area.WATCH_TIME,
                    "Track estimated watch time per view when comparing future videos.",
                    RecommendationResult.RecommendationType.EVIDENCE_BASED,
                    String.format(Locale.ROOT, "Estimated watch time is %.2f minutes per view.", minutesPerView),
                    RecommendationResult.Confidence.HIGH,
                    "Compare watch minutes per view across comparable videos."));
        } else {
            missingData.add("Estimated minutes watched and views are required for watch-time-per-view analysis.");
        }
    }

    private void addDiscoveryRecommendation(Map<String, Object> metrics,
                                             List<RecommendationResult.Recommendation> recommendations,
                                             List<String> missingData) {
        if (metrics.containsKey("impressions") && metrics.containsKey("impressionsClickThroughRate")) {
            recommendations.add(new RecommendationResult.Recommendation(
                    RecommendationResult.Priority.HIGH,
                    RecommendationResult.Area.PACKAGING,
                    "Use impressions and click-through rate to evaluate title and thumbnail packaging.",
                    RecommendationResult.RecommendationType.EVIDENCE_BASED,
                    "The supplied analytics include impressions and click-through rate.",
                    RecommendationResult.Confidence.HIGH,
                    "Compare CTR before and after a packaging experiment while holding the reporting window consistent."));
        } else {
            missingData.add("Impressions and click-through rate are required to evaluate title/thumbnail packaging performance.");
        }
    }

    private void addRetentionRecommendation(VideoRetentionAnalyticsResult retention,
                                             List<RecommendationResult.Recommendation> recommendations,
                                             List<String> missingData) {
        if (retention == null) return;

        if (retention.getAverageViewPercentage() != null) {
            Double average = retention.getAverageViewPercentage();
            List<VideoRetentionAnalyticsResult.RetentionPoint> points = retention.getRetention();
            VideoRetentionAnalyticsResult.RetentionPoint firstPoint = points == null || points.isEmpty() ? null : points.get(0);
            VideoRetentionAnalyticsResult.RetentionPoint earlyPoint = findPointAtOrAfter(points, 0.05);

            boolean earlyDrop = firstPoint != null && earlyPoint != null
                    && firstPoint.getAudienceWatchRatio() != null
                    && earlyPoint.getAudienceWatchRatio() != null
                    && firstPoint.getAudienceWatchRatio() - earlyPoint.getAudienceWatchRatio() >= 0.20;

            String evidence;
            String recommendation;
            RecommendationResult.Confidence confidence;
            if (earlyDrop) {
                evidence = String.format(Locale.ROOT,
                        "Early retention drops from %.2f%% at %.0f%% of video elapsed to %.2f%% at %.0f%% elapsed; average view percentage is %.2f%%.",
                        firstPoint.getAudienceWatchRatio() * 100,
                        firstPoint.getElapsedVideoTimeRatio() * 100,
                        earlyPoint.getAudienceWatchRatio() * 100,
                        earlyPoint.getElapsedVideoTimeRatio() * 100,
                        average);
                recommendation = "Test a stronger outcome-first opening and move the strongest payoff earlier in future videos.";
                confidence = RecommendationResult.Confidence.HIGH;
            } else if (average < 35.0) {
                evidence = String.format(Locale.ROOT, "Average view percentage is %.2f%%; the available retention curve does not establish a specific early-drop pattern.", average);
                recommendation = "Test improvements to the opening and overall pacing, while using retention segments to measure the result.";
                confidence = RecommendationResult.Confidence.MEDIUM;
            } else {
                evidence = String.format(Locale.ROOT, "Average view percentage is %.2f%% and no high-confidence early-drop pattern was detected from the available curve.", average);
                recommendation = "Preserve the current pacing while testing small improvements to the opening and structure.";
                confidence = RecommendationResult.Confidence.MEDIUM;
            }

            recommendations.add(new RecommendationResult.Recommendation(
                    earlyDrop || average < 35.0 ? RecommendationResult.Priority.HIGH : RecommendationResult.Priority.MEDIUM,
                    RecommendationResult.Area.RETENTION,
                    recommendation,
                    RecommendationResult.RecommendationType.EXPERIMENTAL,
                    evidence,
                    confidence,
                    "Compare average view percentage and the first retention segment against the current video baseline."));
        } else {
            missingData.add("Average view percentage is not available in the retention response.");
        }
    }

    private VideoRetentionAnalyticsResult.RetentionPoint findPointAtOrAfter(
            List<VideoRetentionAnalyticsResult.RetentionPoint> points, double targetRatio) {
        if (points == null) return null;
        for (VideoRetentionAnalyticsResult.RetentionPoint point : points) {
            if (point.getElapsedVideoTimeRatio() != null && point.getElapsedVideoTimeRatio() >= targetRatio) {
                return point;
            }
        }
        return null;
    }

    private void addMissingData(Map<String, Object> metrics,
                                VideoRetentionAnalyticsResult retention,
                                List<String> missingData) {
        if (!metrics.containsKey("trafficSourceType")) {
            missingData.add("Detailed traffic-source data is not available in the video metrics response.");
        }
        if (retention == null) {
            missingData.add("Audience-retention data is not available for this recommendation run.");
        }
    }

    private Number number(Object value) {
        return value instanceof Number number ? number : null;
    }

    private String formatPercent(double value) {
        return String.format(Locale.ROOT, "%.2f%%", value * 100);
    }
}
