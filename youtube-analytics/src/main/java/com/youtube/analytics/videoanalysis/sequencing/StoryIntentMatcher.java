package com.youtube.analytics.videoanalysis.sequencing;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class StoryIntentMatcher {

    private static final Map<String, Set<String>> CONCEPTS = conceptMap();

    public double relevance(String storyIntent, String visualSummary, String spokenText) {
        List<String> intentTokens = tokens(storyIntent);
        if (intentTokens.isEmpty()) return 0.0;
        Set<String> evidence = tokens((visualSummary == null ? "" : visualSummary) + " "
                + (spokenText == null ? "" : spokenText)).stream().collect(Collectors.toSet());
        long matched = intentTokens.stream()
                .filter(token -> evidence.contains(token) || conceptMatch(token, evidence))
                .distinct()
                .count();
        return Math.min(1.0, (double) matched / intentTokens.stream().distinct().count());
    }

    private boolean conceptMatch(String intentToken, Set<String> evidence) {
        Set<String> related = CONCEPTS.get(intentToken);
        return related != null && related.stream().anyMatch(evidence::contains);
    }

    private List<String> tokens(String text) {
        if (text == null || text.isBlank()) return List.of();
        return List.of(text.toLowerCase(Locale.ROOT).split("\\W+"))
                .stream().filter(token -> token.length() > 2).toList();
    }

    private static Map<String, Set<String>> conceptMap() {
        Map<String, Set<String>> map = new LinkedHashMap<>();
        add(map, "drive", "driving", "road", "journey", "travel");
        add(map, "trip", "travel", "journey", "getaway", "drive");
        add(map, "journey", "travel", "drive", "driving", "road", "trip");
        add(map, "scenic", "mountain", "view", "landscape", "road", "sunset");
        add(map, "mountain", "hill", "hills", "mountainous");
        add(map, "sunrise", "morning", "dawn", "sun");
        add(map, "destination", "arrived", "arrival", "place", "location");
        add(map, "explore", "exploring", "visit", "visited", "discover");
        add(map, "food", "eat", "eating", "restaurant", "meal");
        add(map, "weekend", "saturday", "sunday", "getaway");
        add(map, "bangalore", "bengaluru");
        return Map.copyOf(map);
    }

    private static void add(Map<String, Set<String>> map, String key, String... values) {
        map.put(key, Set.of(values));
    }
}
