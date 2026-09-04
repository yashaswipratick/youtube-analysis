package com.youtube.analytics.videoanalysis.sequencing;

import com.youtube.analytics.videoanalysis.model.ClipCandidate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class TransitionCoherenceOptimizer {

    public List<ClipCandidate> optimize(List<ClipCandidate> candidates) {
        if (candidates == null || candidates.size() < 2) {
            return candidates == null ? List.of() : List.copyOf(candidates);
        }

        List<ClipCandidate> remaining = new ArrayList<>(candidates);
        List<ClipCandidate> ordered = new ArrayList<>();
        ordered.add(remaining.remove(0));

        while (!remaining.isEmpty()) {
            ClipCandidate previous = ordered.get(ordered.size() - 1);
            ClipCandidate next = remaining.stream()
                    .max((first, second) -> Double.compare(coherence(previous, first), coherence(previous, second)))
                    .orElseThrow();
            ordered.add(next);
            remaining.remove(next);
        }
        return List.copyOf(ordered);
    }

    double coherence(ClipCandidate previous, ClipCandidate next) {
        double score = 0.0;
        if (previous.sourceFileName().equals(next.sourceFileName())) score += 0.35;
        if (previous.role() != next.role()) score += 0.25;
        score += 0.40 * tokenOverlap(previous.visualSummary(), next.visualSummary());
        return score;
    }

    private double tokenOverlap(String first, String second) {
        Set<String> firstTokens = tokens(first);
        Set<String> secondTokens = tokens(second);
        if (firstTokens.isEmpty() || secondTokens.isEmpty()) return 0.0;

        Set<String> intersection = new HashSet<>(firstTokens);
        intersection.retainAll(secondTokens);
        Set<String> union = new HashSet<>(firstTokens);
        union.addAll(secondTokens);
        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }

    private Set<String> tokens(String value) {
        if (value == null || value.isBlank()) return Set.of();
        return java.util.Arrays.stream(value.toLowerCase(Locale.ROOT).split("\\W+"))
                .filter(token -> token.length() > 2)
                .collect(Collectors.toSet());
    }
}
