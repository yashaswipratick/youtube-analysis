package com.youtube.analytics.service;

import com.youtube.analytics.model.ContentClassification;
import com.youtube.analytics.model.NormalizedVideoAnalytics;
import com.youtube.analytics.model.PatternDetectionResult;
import com.youtube.analytics.model.VideoMeta;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Finds deterministic channel-wide patterns. This service deliberately does
 * not use an LLM; its output becomes structured evidence for the AI layer.
 */
@Service
public class PatternDetectionService {

    private final ContentClassificationService classificationService;

    public PatternDetectionService(ContentClassificationService classificationService) {
        this.classificationService = classificationService;
    }

    public PatternDetectionResult detect(List<NormalizedVideoAnalytics> videos) {
        List<NormalizedVideoAnalytics> input = videos == null ? List.of() : videos.stream()
                .filter(Objects::nonNull)
                .toList();

        List<NormalizedVideoAnalytics> withAnalytics = input.stream()
                .filter(v -> number(v, "views") != null)
                .toList();

        Map<ContentClassification.Category, List<NormalizedVideoAnalytics>> categories = withAnalytics.stream()
                .collect(Collectors.groupingBy(this::category, LinkedHashMap::new, Collectors.toList()));
        Map<String, List<NormalizedVideoAnalytics>> topics = withAnalytics.stream()
                .flatMap(v -> classificationService.classify(toVideoMeta(v)).getTopics().stream()
                        .map(topic -> Map.entry(topic, v)))
                .collect(Collectors.groupingBy(Map.Entry::getKey,
                        LinkedHashMap::new,
                        Collectors.mapping(Map.Entry::getValue, Collectors.toList())));

        Group bestCategory = bestGroup(categories.entrySet().stream()
                .map(e -> new Group(e.getKey().name(), averageViews(e.getValue()))).toList());
        Group bestTopic = bestGroup(topics.entrySet().stream()
                .map(e -> new Group(e.getKey(), averageViews(e.getValue()))).toList());

        double shortAverage = averageViewsByType(withAnalytics, ContentClassification.ContentType.SHORT);
        double longAverage = averageViewsByType(withAnalytics, ContentClassification.ContentType.LONG_FORM);

        PatternDetectionResult.Finding typeFinding = typeFinding(shortAverage, longAverage);
        List<PatternDetectionResult.Finding> findings = new ArrayList<>();
        if (bestCategory != null) {
            findings.add(finding("TOP_CATEGORY", bestCategory.name,
                    "This category has the highest average views among categories with analytics.",
                    Map.of("averageViews", bestCategory.averageViews)));
        }
        if (bestTopic != null) {
            findings.add(finding("TOP_TOPIC", bestTopic.name,
                    "This topic has the highest average views among detected topics.",
                    Map.of("averageViews", bestTopic.averageViews)));
        }
        if (typeFinding != null) findings.add(typeFinding);

        return PatternDetectionResult.builder()
                .analyzedVideos(input.size())
                .videosWithAnalytics(withAnalytics.size())
                .topCategory(bestCategory == null ? null : bestCategory.name)
                .topCategoryAverageViews(bestCategory == null ? null : bestCategory.averageViews)
                .topTopic(bestTopic == null ? null : bestTopic.name)
                .topTopicAverageViews(bestTopic == null ? null : bestTopic.averageViews)
                .shortAverageViews(Double.isFinite(shortAverage) ? shortAverage : null)
                .longFormAverageViews(Double.isFinite(longAverage) ? longAverage : null)
                .strongestTrafficSource(null)
                .strongestTrafficSourceShare(null)
                .findings(findings)
                .build();
    }

    private PatternDetectionResult.Finding typeFinding(double shorts, double longForm) {
        if (!Double.isFinite(shorts) || !Double.isFinite(longForm)) return null;
        String type = shorts >= longForm ? "SHORT" : "LONG_FORM";
        double ratio = Math.max(shorts, longForm) == 0 ? 0 : Math.min(shorts, longForm) / Math.max(shorts, longForm);
        return finding("FORMAT_COMPARISON", type,
                "This format has the higher average views in the comparison set.",
                Map.of("shortAverageViews", shorts, "longFormAverageViews", longForm, "relativeRatio", ratio));
    }

    private PatternDetectionResult.Finding finding(String type, String subject, String description, Map<String, Object> evidence) {
        return PatternDetectionResult.Finding.builder().type(type).subject(subject).description(description).evidence(evidence).build();
    }

    private ContentClassification.Category category(NormalizedVideoAnalytics video) {
        return classificationService.classify(toVideoMeta(video)).getCategory();
    }

    private VideoMeta toVideoMeta(NormalizedVideoAnalytics video) {
        return new VideoMeta(video.getVideoId(), video.getTitle(), video.getPublishedAt());
    }

    private double averageViewsByType(List<NormalizedVideoAnalytics> videos, ContentClassification.ContentType type) {
        List<NormalizedVideoAnalytics> matching = videos.stream()
                .filter(v -> classificationService.classify(toVideoMeta(v)).getContentType() == type).toList();
        return averageViews(matching);
    }

    private double averageViews(List<NormalizedVideoAnalytics> videos) {
        if (videos == null || videos.isEmpty()) return Double.NaN;
        return videos.stream().map(v -> number(v, "views")).filter(Objects::nonNull)
                .mapToDouble(Number::doubleValue).average().orElse(Double.NaN);
    }

    private Group bestGroup(List<Group> groups) {
        return groups.stream().filter(g -> Double.isFinite(g.averageViews))
                .max(Comparator.comparingDouble(Group::averageViews)).orElse(null);
    }

    private Number number(NormalizedVideoAnalytics video, String key) {
        if (video.getAggregateMetrics() == null) return null;
        Object value = video.getAggregateMetrics().get(key);
        return value instanceof Number ? (Number) value : null;
    }

    private record Group(String name, double averageViews) {}
}
