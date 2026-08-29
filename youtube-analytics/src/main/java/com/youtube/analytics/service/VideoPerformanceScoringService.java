package com.youtube.analytics.service;

import com.youtube.analytics.model.DerivedVideoMetrics;
import com.youtube.analytics.model.NormalizedVideoAnalytics;
import com.youtube.analytics.model.VideoPerformanceScore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.ToDoubleFunction;

/**
 * Calculates a deterministic channel-relative performance score.
 *
 * Each component is percentile-ranked against the supplied comparison set.
 * The final score is the weighted average of available components.
 */
@Service
@RequiredArgsConstructor
public class VideoPerformanceScoringService {

    private static final double VIEWS_WEIGHT = 0.40;
    private static final double ENGAGEMENT_WEIGHT = 0.25;
    private static final double WATCH_TIME_WEIGHT = 0.20;
    private static final double SUBSCRIBER_WEIGHT = 0.15;

    private final DerivedMetricsCalculator derivedMetricsCalculator;

    public VideoPerformanceScore score(
            NormalizedVideoAnalytics video,
            List<NormalizedVideoAnalytics> comparisonSet) {

        if (video == null || comparisonSet == null || comparisonSet.isEmpty()) {
            return insufficientData();
        }

        List<NormalizedVideoAnalytics> valid = comparisonSet.stream()
                .filter(Objects::nonNull)
                .toList();
        if (valid.isEmpty()) {
            return insufficientData();
        }

        double viewsPercentile = percentile(video, valid, this::views);
        DerivedVideoMetrics derived = derivedMetricsCalculator.calculate(video);

        double engagement = engagementScore(derived);
        double watchTime = percentile(video, valid,
                v -> safeDouble(derivedMetricsCalculator.calculate(v).getAverageWatchTimePerViewSeconds()));
        double subscriber = percentile(video, valid,
                v -> safeDouble(derivedMetricsCalculator.calculate(v).getSubscriberConversionRate()));

        double score = weightedAverage(
                viewsPercentile, engagement, watchTime, subscriber);
        double percentile = score;

        return VideoPerformanceScore.builder()
                .score(round(score))
                .percentile(round(percentile))
                .performance(band(score))
                .build();
    }

    private double engagementScore(DerivedVideoMetrics metrics) {
        if (metrics == null || metrics.getLikeRate() == null || metrics.getCommentRate() == null) {
            return Double.NaN;
        }
        return clamp((metrics.getLikeRate() + metrics.getCommentRate()) * 10.0);
    }

    private double percentile(NormalizedVideoAnalytics target,
                              List<NormalizedVideoAnalytics> values,
                              ToDoubleFunction<NormalizedVideoAnalytics> extractor) {
        double targetValue = extractor.applyAsDouble(target);
        if (!Double.isFinite(targetValue)) {
            return Double.NaN;
        }

        List<Double> scores = values.stream()
                .map(extractor::applyAsDouble)
                .filter(Double::isFinite)
                .sorted(Comparator.naturalOrder())
                .toList();

        if (scores.isEmpty()) {
            return Double.NaN;
        }
        if (scores.size() == 1) {
            return 100.0;
        }

        long lessOrEqual = scores.stream().filter(value -> value <= targetValue).count();
        return ((lessOrEqual - 1) * 100.0) / (scores.size() - 1);
    }

    private double weightedAverage(double views, double engagement,
                                   double watchTime, double subscriber) {
        double total = 0;
        double weight = 0;
        if (Double.isFinite(views)) { total += views * VIEWS_WEIGHT; weight += VIEWS_WEIGHT; }
        if (Double.isFinite(engagement)) { total += engagement * ENGAGEMENT_WEIGHT; weight += ENGAGEMENT_WEIGHT; }
        if (Double.isFinite(watchTime)) { total += watchTime * WATCH_TIME_WEIGHT; weight += WATCH_TIME_WEIGHT; }
        if (Double.isFinite(subscriber)) { total += subscriber * SUBSCRIBER_WEIGHT; weight += SUBSCRIBER_WEIGHT; }
        return weight == 0 ? Double.NaN : total / weight;
    }

    private double views(NormalizedVideoAnalytics video) {
        return number(video, "views");
    }

    private double number(NormalizedVideoAnalytics video, String key) {
        if (video == null || video.getAggregateMetrics() == null) return Double.NaN;
        Object value = video.getAggregateMetrics().get(key);
        return value instanceof Number number ? number.doubleValue() : Double.NaN;
    }

    private double safeDouble(Double value) {
        return value == null ? Double.NaN : value;
    }

    private double clamp(double value) {
        return Math.max(0, Math.min(100, value));
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private VideoPerformanceScore.PerformanceBand band(double score) {
        if (!Double.isFinite(score)) return VideoPerformanceScore.PerformanceBand.INSUFFICIENT_DATA;
        if (score >= 90) return VideoPerformanceScore.PerformanceBand.TOP_PERFORMER;
        if (score >= 60) return VideoPerformanceScore.PerformanceBand.ABOVE_AVERAGE;
        if (score >= 40) return VideoPerformanceScore.PerformanceBand.AVERAGE;
        if (score >= 20) return VideoPerformanceScore.PerformanceBand.BELOW_AVERAGE;
        return VideoPerformanceScore.PerformanceBand.LOW_PERFORMER;
    }

    private VideoPerformanceScore insufficientData() {
        return VideoPerformanceScore.builder()
                .score(0)
                .percentile(0)
                .performance(VideoPerformanceScore.PerformanceBand.INSUFFICIENT_DATA)
                .build();
    }
}
