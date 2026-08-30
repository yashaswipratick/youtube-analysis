package com.youtube.analytics.service;

import com.youtube.analytics.model.RecommendationResult;
import com.youtube.analytics.model.VideoAnalyticsResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
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
        addMissingData(metrics, missingData);

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
                        "Preserve the engagement-oriented elements of the video in future content.",
                        RecommendationResult.RecommendationType.EVIDENCE_BASED,
                        "The supplied like-to-view ratio is " + formatPercent(likeRate) + ".",
                        RecommendationResult.Confidence.MEDIUM));
            } else {
                recommendations.add(new RecommendationResult.Recommendation(
                        "Test a stronger viewer-engagement prompt or story payoff in future videos.",
                        RecommendationResult.RecommendationType.EXPERIMENTAL,
                        "The supplied like-to-view ratio is " + formatPercent(likeRate) + "; no benchmark was supplied.",
                        RecommendationResult.Confidence.LOW));
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
                    "Use subscriber conversion as a success signal when comparing future videos.",
                    RecommendationResult.RecommendationType.EVIDENCE_BASED,
                    evidence + ".",
                    RecommendationResult.Confidence.MEDIUM));
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
            recommendations.add(new RecommendationResult.Recommendation(
                    "Track estimated watch time per view when comparing future videos.",
                    RecommendationResult.RecommendationType.EVIDENCE_BASED,
                    "Estimated minutes watched and views are both available, allowing watch minutes per view to be calculated without assuming video duration.",
                    RecommendationResult.Confidence.HIGH));
        } else {
            missingData.add("Estimated minutes watched and views are required for watch-time-per-view analysis.");
        }
    }

    private void addDiscoveryRecommendation(Map<String, Object> metrics,
                                             List<RecommendationResult.Recommendation> recommendations,
                                             List<String> missingData) {
        if (metrics.containsKey("impressions") && metrics.containsKey("impressionsClickThroughRate")) {
            recommendations.add(new RecommendationResult.Recommendation(
                    "Use impressions and click-through rate to evaluate title and thumbnail packaging.",
                    RecommendationResult.RecommendationType.EVIDENCE_BASED,
                    "The supplied analytics include impressions and click-through rate.",
                    RecommendationResult.Confidence.HIGH));
        } else {
            missingData.add("Impressions and click-through rate are required to evaluate title/thumbnail packaging performance.");
        }
    }

    private void addMissingData(Map<String, Object> metrics, List<String> missingData) {
        if (!metrics.containsKey("averageViewDuration")) missingData.add("Average view duration is not available.");
        if (!metrics.containsKey("averageViewPercentage")) missingData.add("Average view percentage is not available.");
        if (!metrics.containsKey("trafficSourceType")) missingData.add("Detailed traffic-source data is not available in the video metrics response.");
    }

    private Number number(Object value) {
        return value instanceof Number number ? number : null;
    }

    private String formatPercent(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f%%", value * 100);
    }
}
