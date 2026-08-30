package com.youtube.analytics.service;

import com.youtube.analytics.model.RetentionAnalysisResult;
import com.youtube.analytics.model.VideoRetentionAnalyticsResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Deterministic analyzer for audience-retention curves. */
@Service
@RequiredArgsConstructor
public class RetentionAnalysisService {

    private static final double CRITICAL_AVERAGE_PERCENTAGE = 20.0;
    private static final double WEAK_AVERAGE_PERCENTAGE = 35.0;
    private static final double HEALTHY_AVERAGE_PERCENTAGE = 50.0;
    private static final double EARLY_DROP_THRESHOLD = 20.0;
    private static final double RECOVERY_THRESHOLD = 2.0;

    public RetentionAnalysisResult analyze(VideoRetentionAnalyticsResult retention) {
        List<String> missing = new ArrayList<>();
        if (retention == null) {
            return new RetentionAnalysisResult(null, null, null,
                    RetentionAnalysisResult.RetentionSeverity.UNKNOWN, null, null, null, null,
                    List.of(), null, List.of(), List.of("Retention data is unavailable."));
        }

        List<VideoRetentionAnalyticsResult.RetentionPoint> points = retention.getRetention() == null
                ? List.of()
                : retention.getRetention().stream()
                .filter(p -> p != null && p.getElapsedVideoTimeRatio() != null && p.getAudienceWatchRatio() != null)
                .sorted(Comparator.comparing(VideoRetentionAnalyticsResult.RetentionPoint::getElapsedVideoTimeRatio))
                .toList();

        if (retention.getAverageViewPercentage() == null) {
            missing.add("Average view percentage is unavailable; overall retention severity cannot be scored from the average.");
        }
        if (points.size() < 2) {
            missing.add("At least two retention curve points are required for drop, recovery, and section analysis.");
        }

        RetentionAnalysisResult.RetentionSeverity severity = classify(retention.getAverageViewPercentage());
        RetentionAnalysisResult.RetentionFinding earlyDrop = findEarlyDrop(points);
        RetentionAnalysisResult.RetentionFinding largestDrop = findLargestDrop(points);
        RetentionAnalysisResult.RetentionFinding strongest = findStrongestSection(points);
        RetentionAnalysisResult.RetentionFinding weakest = findWeakestSection(points);
        List<RetentionAnalysisResult.RetentionFinding> recoveries = findRecoveries(points);
        RetentionAnalysisResult.RetentionFinding ending = findEnding(points);

        List<String> recommendations = buildRecommendations(severity, earlyDrop, largestDrop, strongest, weakest, ending);

        return new RetentionAnalysisResult(
                retention.getVideoId(),
                retention.getAverageViewDurationSeconds(),
                retention.getAverageViewPercentage(),
                severity,
                earlyDrop,
                largestDrop,
                strongest,
                weakest,
                recoveries,
                ending,
                recommendations,
                missing);
    }

    private RetentionAnalysisResult.RetentionSeverity classify(Double average) {
        if (average == null) return RetentionAnalysisResult.RetentionSeverity.UNKNOWN;
        if (average < CRITICAL_AVERAGE_PERCENTAGE) return RetentionAnalysisResult.RetentionSeverity.CRITICAL;
        if (average < WEAK_AVERAGE_PERCENTAGE) return RetentionAnalysisResult.RetentionSeverity.WEAK;
        if (average < HEALTHY_AVERAGE_PERCENTAGE) return RetentionAnalysisResult.RetentionSeverity.HEALTHY;
        return RetentionAnalysisResult.RetentionSeverity.STRONG;
    }

    private RetentionAnalysisResult.RetentionFinding findEarlyDrop(List<VideoRetentionAnalyticsResult.RetentionPoint> points) {
        if (points.size() < 2) return null;
        VideoRetentionAnalyticsResult.RetentionPoint from = points.get(0);
        VideoRetentionAnalyticsResult.RetentionPoint to = points.stream()
                .filter(p -> p.getElapsedVideoTimeRatio() >= 0.05)
                .findFirst().orElse(null);
        if (to == null) return null;
        double drop = percentage(to.getAudienceWatchRatio()) - percentage(from.getAudienceWatchRatio());
        if (drop >= -EARLY_DROP_THRESHOLD) return null;
        return finding("EARLY_DROP", from, to, drop,
                "Audience retention drops by " + format(Math.abs(drop)) + " percentage points in the opening 5% of the video.");
    }

    private RetentionAnalysisResult.RetentionFinding findLargestDrop(List<VideoRetentionAnalyticsResult.RetentionPoint> points) {
        if (points.size() < 2) return null;
        VideoRetentionAnalyticsResult.RetentionPoint from = null, to = null;
        double largest = 0;
        for (int i = 1; i < points.size(); i++) {
            double change = percentage(points.get(i).getAudienceWatchRatio()) - percentage(points.get(i - 1).getAudienceWatchRatio());
            if (change < largest) {
                largest = change;
                from = points.get(i - 1);
                to = points.get(i);
            }
        }
        if (from == null) return null;
        return finding("LARGEST_DROP", from, to, largest,
                "The largest adjacent retention decline is " + format(Math.abs(largest)) + " percentage points.");
    }

    private RetentionAnalysisResult.RetentionFinding findStrongestSection(List<VideoRetentionAnalyticsResult.RetentionPoint> points) {
        if (points.size() < 2) return null;
        VideoRetentionAnalyticsResult.RetentionPoint bestFrom = null, bestTo = null;
        double bestAverage = Double.NEGATIVE_INFINITY;
        for (int i = 1; i < points.size(); i++) {
            double average = (percentage(points.get(i - 1).getAudienceWatchRatio()) + percentage(points.get(i).getAudienceWatchRatio())) / 2;
            if (average > bestAverage) {
                bestAverage = average;
                bestFrom = points.get(i - 1);
                bestTo = points.get(i);
            }
        }
        return finding("STRONGEST_SECTION", bestFrom, bestTo, 0,
                "Highest adjacent retention level is approximately " + format(bestAverage) + "%." );
    }

    private RetentionAnalysisResult.RetentionFinding findWeakestSection(List<VideoRetentionAnalyticsResult.RetentionPoint> points) {
        if (points.size() < 2) return null;
        VideoRetentionAnalyticsResult.RetentionPoint bestFrom = null, bestTo = null;
        double lowestAverage = Double.POSITIVE_INFINITY;
        for (int i = 1; i < points.size(); i++) {
            double average = (percentage(points.get(i - 1).getAudienceWatchRatio()) + percentage(points.get(i).getAudienceWatchRatio())) / 2;
            if (average < lowestAverage) {
                lowestAverage = average;
                bestFrom = points.get(i - 1);
                bestTo = points.get(i);
            }
        }
        return finding("WEAKEST_SECTION", bestFrom, bestTo, 0,
                "Lowest adjacent retention level is approximately " + format(lowestAverage) + "%." );
    }

    private List<RetentionAnalysisResult.RetentionFinding> findRecoveries(List<VideoRetentionAnalyticsResult.RetentionPoint> points) {
        List<RetentionAnalysisResult.RetentionFinding> results = new ArrayList<>();
        for (int i = 1; i < points.size(); i++) {
            double change = percentage(points.get(i).getAudienceWatchRatio()) - percentage(points.get(i - 1).getAudienceWatchRatio());
            if (change >= RECOVERY_THRESHOLD) {
                results.add(finding("RECOVERY", points.get(i - 1), points.get(i), change,
                        "Retention recovers by " + format(change) + " percentage points."));
            }
        }
        return results;
    }

    private RetentionAnalysisResult.RetentionFinding findEnding(List<VideoRetentionAnalyticsResult.RetentionPoint> points) {
        if (points.size() < 2) return null;
        VideoRetentionAnalyticsResult.RetentionPoint from = points.stream()
                .filter(p -> p.getElapsedVideoTimeRatio() >= 0.90)
                .findFirst().orElse(null);
        VideoRetentionAnalyticsResult.RetentionPoint to = points.get(points.size() - 1);
        if (from == null) return null;
        double end = percentage(to.getAudienceWatchRatio());
        RetentionAnalysisResult.RetentionSeverity severity = end < 10 ? RetentionAnalysisResult.RetentionSeverity.CRITICAL
                : end < 20 ? RetentionAnalysisResult.RetentionSeverity.WEAK
                : RetentionAnalysisResult.RetentionSeverity.HEALTHY;
        double change = end - percentage(from.getAudienceWatchRatio());
        return new RetentionAnalysisResult.RetentionFinding(
                "END_RETENTION",
                from.getElapsedVideoTimeRatio() * 100,
                to.getElapsedVideoTimeRatio() * 100,
                from.getAudienceWatchRatio() * 100,
                to.getAudienceWatchRatio() * 100,
                change,
                severity,
                "Retention at the end of the available curve is " + format(end) + "%." );
    }

    private List<String> buildRecommendations(RetentionAnalysisResult.RetentionSeverity severity,
                                               RetentionAnalysisResult.RetentionFinding earlyDrop,
                                               RetentionAnalysisResult.RetentionFinding largestDrop,
                                               RetentionAnalysisResult.RetentionFinding strongest,
                                               RetentionAnalysisResult.RetentionFinding weakest,
                                               RetentionAnalysisResult.RetentionFinding ending) {
        List<String> recommendations = new ArrayList<>();
        if (earlyDrop != null) {
            recommendations.add("Prioritize the opening: reduce setup and surface the strongest outcome or visual within the first 5% of the video.");
        } else if (largestDrop != null && Math.abs(largestDrop.changePercentagePoints()) >= 10) {
            recommendations.add("Inspect the section around the largest retention drop and test removing or shortening the content immediately before that point.");
        }
        if (strongest != null) {
            recommendations.add("Review the strongest-retaining section and reuse its pacing, topic, or visual style in future videos where appropriate.");
        }
        if (ending != null && ending.severity() == RetentionAnalysisResult.RetentionSeverity.CRITICAL) {
            recommendations.add("Strengthen the final segment with a clear payoff or next-step hook instead of allowing the video to trail off.");
        }
        if (recommendations.isEmpty() && severity != RetentionAnalysisResult.RetentionSeverity.UNKNOWN) {
            recommendations.add("Run controlled pacing and opening experiments while comparing retention against this video's baseline.");
        }
        return recommendations;
    }

    private RetentionAnalysisResult.RetentionFinding finding(String type,
                                                              VideoRetentionAnalyticsResult.RetentionPoint from,
                                                              VideoRetentionAnalyticsResult.RetentionPoint to,
                                                              double change,
                                                              String evidence) {
        return new RetentionAnalysisResult.RetentionFinding(
                type,
                from.getElapsedVideoTimeRatio() * 100,
                to.getElapsedVideoTimeRatio() * 100,
                from.getAudienceWatchRatio() * 100,
                to.getAudienceWatchRatio() * 100,
                change,
                severityForChange(change),
                evidence);
    }

    private RetentionAnalysisResult.RetentionSeverity severityForChange(double change) {
        double magnitude = Math.abs(change);
        if (magnitude >= 30) return RetentionAnalysisResult.RetentionSeverity.CRITICAL;
        if (magnitude >= 15) return RetentionAnalysisResult.RetentionSeverity.WEAK;
        return RetentionAnalysisResult.RetentionSeverity.HEALTHY;
    }

    private double percentage(Double ratio) { return ratio * 100; }

    private String format(double value) { return String.format(Locale.ROOT, "%.2f", value); }
}
