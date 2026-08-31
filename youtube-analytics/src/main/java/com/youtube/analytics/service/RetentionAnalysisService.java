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
    private static final double RECOVERY_THRESHOLD = 3.0;
    private static final double MEANINGFUL_SECTION_DROP = 10.0;
    private static final double MEANINGFUL_RECOVERY = 3.0;
    private static final double VOLATILITY_NOISE_THRESHOLD = 5.0;
    private static final double[] SEGMENT_BOUNDARIES = {0, 5, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100};

    public RetentionAnalysisResult analyze(VideoRetentionAnalyticsResult retention) {
        List<String> missing = new ArrayList<>();
        if (retention == null) {
            return new RetentionAnalysisResult(null, null, null,
                    RetentionAnalysisResult.RetentionSeverity.UNKNOWN, null, null, null, null,
                    List.of(), null, List.of(), List.of(), List.of("Retention data is unavailable."));
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
        List<RetentionAnalysisResult.RetentionSegment> segments = buildSegments(points);
        RetentionAnalysisResult.RetentionFinding strongest = findStrongestMeaningfulSegment(segments);
        RetentionAnalysisResult.RetentionFinding weakest = findWeakestMeaningfulSegment(segments);
        List<RetentionAnalysisResult.RetentionFinding> recoveries = findMeaningfulRecoveries(points);
        RetentionAnalysisResult.RetentionFinding ending = findEnding(points);

        List<String> recommendations = buildRecommendations(severity, earlyDrop, largestDrop, strongest, ending, segments);

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
                segments,
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

    private List<RetentionAnalysisResult.RetentionSegment> buildSegments(List<VideoRetentionAnalyticsResult.RetentionPoint> points) {
        List<RetentionAnalysisResult.RetentionSegment> segments = new ArrayList<>();
        if (points.size() < 2) return segments;

        for (int i = 0; i < SEGMENT_BOUNDARIES.length - 1; i++) {
            double start = SEGMENT_BOUNDARIES[i];
            double end = SEGMENT_BOUNDARIES[i + 1];
            List<VideoRetentionAnalyticsResult.RetentionPoint> window = points.stream()
                    .filter(p -> percentage(p.getElapsedVideoTimeRatio()) >= start
                            && percentage(p.getElapsedVideoTimeRatio()) <= end)
                    .toList();
            if (window.size() < 2) continue;

            VideoRetentionAnalyticsResult.RetentionPoint first = window.get(0);
            VideoRetentionAnalyticsResult.RetentionPoint last = window.get(window.size() - 1);
            double startAudience = percentage(first.getAudienceWatchRatio());
            double endAudience = percentage(last.getAudienceWatchRatio());
            double change = endAudience - startAudience;
            double average = window.stream()
                    .mapToDouble(p -> percentage(p.getAudienceWatchRatio()))
                    .average().orElse(endAudience);
            double volatility = calculateVolatility(window);
            RetentionAnalysisResult.RetentionSeverity segmentSeverity = severityForSegment(change, volatility);
            String signal = buildSegmentSignal(change, volatility, startAudience, endAudience);

            segments.add(new RetentionAnalysisResult.RetentionSegment(
                    start,
                    end,
                    startAudience,
                    endAudience,
                    average,
                    change,
                    volatility,
                    segmentSeverity,
                    signal));
        }
        return segments;
    }

    private double calculateVolatility(List<VideoRetentionAnalyticsResult.RetentionPoint> points) {
        if (points.size() < 2) return 0;
        double total = 0;
        for (int i = 1; i < points.size(); i++) {
            total += Math.abs(percentage(points.get(i).getAudienceWatchRatio())
                    - percentage(points.get(i - 1).getAudienceWatchRatio()));
        }
        return total / (points.size() - 1);
    }

    private String buildSegmentSignal(double change, double volatility, double startAudience, double endAudience) {
        if (change <= -MEANINGFUL_SECTION_DROP) return "MEANINGFUL_DROP";
        if (change >= MEANINGFUL_RECOVERY) return "MEANINGFUL_RECOVERY";
        if (volatility <= VOLATILITY_NOISE_THRESHOLD) return "STABLE";
        if (endAudience > startAudience) return "RECOVERY_WITH_VARIATION";
        return "DECLINE_WITH_VARIATION";
    }

    private RetentionAnalysisResult.RetentionSeverity severityForSegment(double change, double volatility) {
        if (change <= -30) return RetentionAnalysisResult.RetentionSeverity.CRITICAL;
        if (change <= -15) return RetentionAnalysisResult.RetentionSeverity.WEAK;
        return RetentionAnalysisResult.RetentionSeverity.HEALTHY;
    }

    private RetentionAnalysisResult.RetentionFinding findStrongestMeaningfulSegment(List<RetentionAnalysisResult.RetentionSegment> segments) {
        if (segments.isEmpty()) return null;
        RetentionAnalysisResult.RetentionSegment best = segments.stream()
                .filter(s -> !"MEANINGFUL_DROP".equals(s.signal()))
                .max(Comparator.comparing(RetentionAnalysisResult.RetentionSegment::averageAudiencePercent))
                .orElse(segments.get(0));
        return segmentFinding("STRONGEST_SECTION", best,
                "Highest meaningful segment retention averages " + format(best.averageAudiencePercent()) + "%. "
                        + "This identifies a section to investigate, not a causal explanation for performance.");
    }

    private RetentionAnalysisResult.RetentionFinding findWeakestMeaningfulSegment(List<RetentionAnalysisResult.RetentionSegment> segments) {
        if (segments.isEmpty()) return null;
        RetentionAnalysisResult.RetentionSegment worst = segments.stream()
                .filter(s -> s.fromVideoPercent() < 90.0)
                .min(Comparator.comparing(RetentionAnalysisResult.RetentionSegment::averageAudiencePercent))
                .orElse(null);
        if (worst == null) return null;
        return segmentFinding("WEAKEST_SECTION", worst,
                "Lowest pre-ending segment retention averages " + format(worst.averageAudiencePercent()) + "%. "
                        + "The final 10% is excluded because low absolute retention there is expected from cumulative audience loss.");
    }

    private RetentionAnalysisResult.RetentionFinding segmentFinding(String type, RetentionAnalysisResult.RetentionSegment segment, String evidence) {
        return new RetentionAnalysisResult.RetentionFinding(
                type,
                segment.fromVideoPercent(),
                segment.toVideoPercent(),
                segment.startAudiencePercent(),
                segment.endAudiencePercent(),
                segment.changePercentagePoints(),
                segment.severity(),
                evidence);
    }

    private List<RetentionAnalysisResult.RetentionFinding> findMeaningfulRecoveries(List<VideoRetentionAnalyticsResult.RetentionPoint> points) {
        List<RetentionAnalysisResult.RetentionFinding> results = new ArrayList<>();
        if (points.size() < 2) return results;

        VideoRetentionAnalyticsResult.RetentionPoint recoveryStart = null;
        VideoRetentionAnalyticsResult.RetentionPoint recoveryEnd = null;
        double accumulatedRecovery = 0;

        for (int i = 1; i < points.size(); i++) {
            VideoRetentionAnalyticsResult.RetentionPoint previous = points.get(i - 1);
            VideoRetentionAnalyticsResult.RetentionPoint current = points.get(i);
            double change = percentage(current.getAudienceWatchRatio()) - percentage(previous.getAudienceWatchRatio());

            if (change > 0) {
                if (recoveryStart == null) recoveryStart = previous;
                recoveryEnd = current;
                accumulatedRecovery += change;
            } else {
                if (recoveryStart != null && accumulatedRecovery >= MEANINGFUL_RECOVERY) {
                    results.add(finding("RECOVERY", recoveryStart, recoveryEnd, accumulatedRecovery,
                            "Retention recovers by " + format(accumulatedRecovery) + " percentage points across a consecutive rising run; treat this as a candidate recovery signal, not proof of why viewers returned or stayed."));
                }
                recoveryStart = null;
                recoveryEnd = null;
                accumulatedRecovery = 0;
            }
        }

        if (recoveryStart != null && accumulatedRecovery >= MEANINGFUL_RECOVERY) {
            results.add(finding("RECOVERY", recoveryStart, recoveryEnd, accumulatedRecovery,
                    "Retention recovers by " + format(accumulatedRecovery) + " percentage points across a consecutive rising run; treat this as a candidate recovery signal, not proof of why viewers returned or stayed."));
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
                                               RetentionAnalysisResult.RetentionFinding ending,
                                               List<RetentionAnalysisResult.RetentionSegment> segments) {
        List<String> recommendations = new ArrayList<>();
        if (earlyDrop != null) {
            recommendations.add("Prioritize the opening: reduce setup and surface the strongest outcome or visual within the first 5% of the video.");
        } else if (largestDrop != null && Math.abs(largestDrop.changePercentagePoints()) >= 10) {
            recommendations.add("Inspect the section around the largest retention drop and test removing or shortening the content immediately before that point.");
        }
        RetentionAnalysisResult.RetentionSegment largestSegmentDrop = segments.stream()
                .filter(s -> s.changePercentagePoints() <= -MEANINGFUL_SECTION_DROP)
                .min(Comparator.comparing(RetentionAnalysisResult.RetentionSegment::changePercentagePoints))
                .orElse(null);
        if (largestSegmentDrop != null && earlyDrop == null) {
            recommendations.add("Inspect the " + format(largestSegmentDrop.fromVideoPercent()) + "–"
                    + format(largestSegmentDrop.toVideoPercent())
                    + "% segment around the largest meaningful decline and test shortening or restructuring that section.");
        }
        if (strongest != null) {
            recommendations.add("Review the strongest meaningful section and reuse only observable presentation patterns in future videos; the retention curve does not identify which creative element caused the result.");
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
