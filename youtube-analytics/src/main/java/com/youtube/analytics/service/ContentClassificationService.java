package com.youtube.analytics.service;

import com.youtube.analytics.model.ContentClassification;
import com.youtube.analytics.model.VideoMeta;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Deterministically classifies YouTube videos from their titles.
 * No external service or LLM is used; this keeps classification predictable
 * and inexpensive for the later analysis layer.
 */
@Service
public class ContentClassificationService {

    private static final List<String> SHORT_MARKERS = List.of(
            "#shorts", "#short", "shorts", "#travelshorts", "#ytshorts", "minivlog");

    private static final List<KeywordRule> CATEGORY_RULES = List.of(
            new KeywordRule(ContentClassification.Category.TRAVEL,
                    "travel", "trip", "travel vlog", "weekend getaway", "weekend trip",
                    "road trip", "roadtrip", "hill", "hills", "mountain", "mountains",
                    "beach", "trek", "trekking", "tour", "explore", "exploring",
                    "bangalore", "bengaluru", "nilgiris", "toy train", "train"),
            new KeywordRule(ContentClassification.Category.CORPORATE_LIFE,
                    "corporate", "office", "software engineer", "software developer",
                    "developer", "engineering", "work life", "worklife", "9 to 5", "9-5",
                    "employee", "tech life", "it life", "career", "meeting", "work from"),
            new KeywordRule(ContentClassification.Category.FOOD,
                    "food", "cook", "cooking", "recipe", "biryani", "biriyani", "rice",
                    "chole", "rajma", "restaurant", "cafe", "café", "breakfast", "lunch",
                    "dinner", "meal", "dish", "taste", "tasty", "recipe"),
            new KeywordRule(ContentClassification.Category.FITNESS,
                    "gym", "fitness", "workout", "work out", "weight loss", "exercise",
                    "training", "muscle", "cardio", "running", "steps"),
            new KeywordRule(ContentClassification.Category.LIFESTYLE,
                    "lifestyle", "daily life", "day in my life", "routine", "vlog", "life",
                    "weekend", "shopping", "home", "family")
    );

    private static final List<TopicRule> TOPIC_RULES = List.of(
            new TopicRule("BANGALORE", "bangalore", "bengaluru"),
            new TopicRule("TRAVEL", "travel", "trip", "getaway", "explore", "road trip", "roadtrip",
                    "hill", "hills", "mountain", "mountains", "beach", "trek", "trekking", "tour",
                    "nilgiris", "toy train", "train"),
            new TopicRule("CORPORATE_LIFE", "corporate", "office", "software engineer", "developer",
                    "work life", "worklife", "9 to 5", "9-5", "career"),
            new TopicRule("FOOD", "food", "cooking", "recipe", "biryani", "biriyani", "restaurant", "cafe", "meal"),
            new TopicRule("FITNESS", "gym", "fitness", "workout", "weight loss", "exercise", "cardio"),
            new TopicRule("NILGIRIS", "nilgiris", "toy train"),
            new TopicRule("MOUNTAINS", "mountain", "mountains", "hill", "hills", "trek", "trekking"),
            new TopicRule("ROAD_TRIP", "road trip", "roadtrip"),
            new TopicRule("WEEKEND", "weekend", "weekend trip", "weekend getaway"),
            new TopicRule("LIFESTYLE", "lifestyle", "daily life", "day in my life", "routine", "vlog")
    );

    public ContentClassification classify(VideoMeta video) {
        String title = video == null || video.getTitle() == null ? "" : video.getTitle();
        String normalized = normalize(title);

        ContentClassification.ContentType type = isShort(normalized)
                ? ContentClassification.ContentType.SHORT
                : ContentClassification.ContentType.LONG_FORM;

        ContentClassification.Category category = classifyCategory(normalized);
        List<String> topics = extractTopics(normalized);

        return ContentClassification.builder()
                .contentType(type)
                .category(category)
                .topics(topics)
                .build();
    }

    private boolean isShort(String title) {
        return SHORT_MARKERS.stream().anyMatch(title::contains);
    }

    private ContentClassification.Category classifyCategory(String title) {
        for (KeywordRule rule : CATEGORY_RULES) {
            if (rule.matches(title)) {
                return rule.category();
            }
        }
        return ContentClassification.Category.OTHER;
    }

    private List<String> extractTopics(String title) {
        Set<String> topics = new LinkedHashSet<>();
        for (TopicRule rule : TOPIC_RULES) {
            if (rule.matches(title)) {
                topics.add(rule.topic());
            }
        }
        return new ArrayList<>(topics);
    }

    private String normalize(String title) {
        return title.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private record KeywordRule(ContentClassification.Category category, String... keywords) {
        boolean matches(String title) {
            return Arrays.stream(keywords).anyMatch(title::contains);
        }
    }

    private record TopicRule(String topic, String... keywords) {
        boolean matches(String title) {
            return Arrays.stream(keywords).anyMatch(title::contains);
        }
    }
}
