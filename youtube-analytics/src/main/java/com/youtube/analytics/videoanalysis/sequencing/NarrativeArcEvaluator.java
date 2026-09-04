package com.youtube.analytics.videoanalysis.sequencing;

import com.youtube.analytics.videoanalysis.model.CandidateRole;
import com.youtube.analytics.videoanalysis.model.ClipCandidate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class NarrativeArcEvaluator {

    private static final Set<CandidateRole> DEVELOPMENT_ROLES = EnumSet.of(
            CandidateRole.SETUP, CandidateRole.JOURNEY, CandidateRole.EXPERIENCE, CandidateRole.VOICE_BRIDGE);

    public List<String> evaluate(String storyIntent, List<ClipCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        List<String> warnings = new ArrayList<>();
        Set<CandidateRole> roles = candidates.stream()
                .map(ClipCandidate::role)
                .collect(Collectors.toSet());

        if (DEVELOPMENT_ROLES.stream().noneMatch(roles::contains)
                && roles.contains(CandidateRole.HOOK)
                && roles.contains(CandidateRole.PAYOFF)
                && roles.contains(CandidateRole.ENDING)) {
            warnings.add("Narrative arc has no clear development beat between opening and payoff");
        }

        double intentCoverage = intentCoverage(storyIntent, candidates);
        if (!storyIntent.isBlank() && intentCoverage > 0.0 && intentCoverage < 0.25) {
            warnings.add("Selected narrative has weak alignment with the requested story intent");
        }

        for (int i = 1; i < candidates.size(); i++) {
            ClipCandidate previous = candidates.get(i - 1);
            ClipCandidate current = candidates.get(i);
            if (hasRepeatedInformation(previous, current)) {
                warnings.add("Adjacent clips repeat substantially the same spoken information");
                break;
            }
        }

        return List.copyOf(warnings);
    }

    double intentCoverage(String storyIntent, List<ClipCandidate> candidates) {
        if (storyIntent == null || storyIntent.isBlank()) return 1.0;
        Set<String> intentTerms = tokens(storyIntent);
        if (intentTerms.isEmpty()) return 1.0;

        Set<String> evidence = new HashSet<>();
        for (ClipCandidate candidate : candidates) {
            evidence.addAll(tokens(candidate.visualSummary()));
            evidence.addAll(tokens(candidate.spokenText()));
        }
        Set<String> matched = new HashSet<>(intentTerms);
        matched.retainAll(evidence);
        return (double) matched.size() / intentTerms.size();
    }

    private boolean hasRepeatedInformation(ClipCandidate previous, ClipCandidate current) {
        String previousText = previous.spokenText();
        String currentText = current.spokenText();
        if (previousText == null || currentText == null || previousText.isBlank() || currentText.isBlank()) {
            return false;
        }
        Set<String> previousTokens = tokens(previousText);
        Set<String> currentTokens = tokens(currentText);
        if (previousTokens.size() < 3 || currentTokens.size() < 3) return false;

        Set<String> intersection = new HashSet<>(previousTokens);
        intersection.retainAll(currentTokens);
        return (double) intersection.size() / Math.min(previousTokens.size(), currentTokens.size()) >= 0.75;
    }

    private Set<String> tokens(String value) {
        if (value == null || value.isBlank()) return Set.of();
        return java.util.Arrays.stream(value.toLowerCase(Locale.ROOT).split("\\W+"))
                .filter(token -> token.length() > 2)
                .collect(Collectors.toSet());
    }
}
