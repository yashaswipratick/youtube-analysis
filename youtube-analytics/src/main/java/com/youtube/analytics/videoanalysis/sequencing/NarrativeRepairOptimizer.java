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
public class NarrativeRepairOptimizer {

    private static final Set<CandidateRole> DEVELOPMENT_ROLES = EnumSet.of(
            CandidateRole.SETUP, CandidateRole.JOURNEY, CandidateRole.EXPERIENCE, CandidateRole.VOICE_BRIDGE);

    public List<ClipCandidate> repair(String storyIntent,
                                      List<ClipCandidate> orderedCandidates,
                                      List<ClipCandidate> candidatePool) {
        if (orderedCandidates == null || orderedCandidates.isEmpty()
                || candidatePool == null || candidatePool.isEmpty()) {
            return orderedCandidates == null ? List.of() : List.copyOf(orderedCandidates);
        }

        List<ClipCandidate> repaired = new ArrayList<>(orderedCandidates);
        Set<ClipCandidate> selected = new HashSet<>(repaired);
        CandidateRole missingRole = missingDevelopmentRole(repaired, candidatePool);
        if (missingRole != null) {
            ClipCandidate replacement = bestUnused(candidatePool, selected, storyIntent, missingRole, Set.of());
            if (replacement != null) {
                repaired.add(replacement);
                selected.add(replacement);
            }
        }

        if (hasWeakIntentAlignment(storyIntent, repaired)) {
            replaceWeakIntentCandidate(storyIntent, repaired, candidatePool, selected);
        }

        repairRepeatedSpeech(repaired, candidatePool, selected, storyIntent);
        repairContradictoryHandoff(repaired, candidatePool, selected, storyIntent);
        return List.copyOf(repaired);
    }

    private CandidateRole missingDevelopmentRole(List<ClipCandidate> candidates, List<ClipCandidate> candidatePool) {
        Set<CandidateRole> roles = candidates.stream().map(ClipCandidate::role).collect(Collectors.toSet());
        boolean hasAnchors = roles.contains(CandidateRole.HOOK)
                && roles.contains(CandidateRole.PAYOFF)
                && roles.contains(CandidateRole.ENDING);
        if (!hasAnchors || DEVELOPMENT_ROLES.stream().anyMatch(roles::contains)) {
            return null;
        }
        return DEVELOPMENT_ROLES.stream()
                .filter(role -> candidatePool.stream().anyMatch(candidate -> candidate.role() == role))
                .findFirst()
                .orElse(null);
    }

    private void replaceWeakIntentCandidate(String storyIntent,
                                             List<ClipCandidate> repaired,
                                             List<ClipCandidate> candidatePool,
                                             Set<ClipCandidate> selected) {
        int replaceIndex = -1;
        double lowestScore = Double.MAX_VALUE;
        for (int i = 0; i < repaired.size(); i++) {
            ClipCandidate candidate = repaired.get(i);
            if (isAnchor(candidate.role())) continue;
            if (candidate.score() < lowestScore) {
                lowestScore = candidate.score();
                replaceIndex = i;
            }
        }
        if (replaceIndex < 0) return;

        ClipCandidate current = repaired.get(replaceIndex);
        ClipCandidate replacement = bestUnused(candidatePool, selected, storyIntent,
                current.role(), Set.of(current));
        if (replacement != null && intentEvidenceScore(storyIntent, replacement)
                > intentEvidenceScore(storyIntent, current)
                && replacement.score() >= current.score() * 0.85) {
            repaired.set(replaceIndex, replacement);
            selected.remove(current);
            selected.add(replacement);
        }
    }

    private void repairRepeatedSpeech(List<ClipCandidate> repaired,
                                      List<ClipCandidate> candidatePool,
                                      Set<ClipCandidate> selected,
                                      String storyIntent) {
        for (int i = 1; i < repaired.size(); i++) {
            ClipCandidate previous = repaired.get(i - 1);
            ClipCandidate current = repaired.get(i);
            if (!hasRepeatedInformation(previous, current)) continue;

            ClipCandidate replacement = bestUnused(candidatePool, selected, storyIntent,
                    current.role(), Set.of(current));
            if (replacement != null && !hasRepeatedInformation(previous, replacement)
                    && replacement.score() >= current.score() * 0.80) {
                repaired.set(i, replacement);
                selected.remove(current);
                selected.add(replacement);
            }
        }
    }

    private void repairContradictoryHandoff(List<ClipCandidate> repaired,
                                             List<ClipCandidate> candidatePool,
                                             Set<ClipCandidate> selected,
                                             String storyIntent) {
        for (int i = 1; i < repaired.size(); i++) {
            ClipCandidate previous = repaired.get(i - 1);
            ClipCandidate current = repaired.get(i);
            if (!hasContradictoryHandoff(previous, current)) continue;

            ClipCandidate replacement = bestUnused(candidatePool, selected, storyIntent,
                    current.role(), Set.of(current));
            if (replacement != null && !hasContradictoryHandoff(previous, replacement)
                    && replacement.score() >= current.score() * 0.80) {
                repaired.set(i, replacement);
                selected.remove(current);
                selected.add(replacement);
            }
        }
    }

    private boolean hasContradictoryHandoff(ClipCandidate previous, ClipCandidate current) {
        String previousText = normalize(previous.spokenText());
        String currentText = normalize(current.spokenText());
        return hasPair(previousText, currentText, "not going", "going to")
                || hasPair(previousText, currentText, "haven't arrived", "arrived")
                || hasPair(previousText, currentText, "didn't arrive", "arrived")
                || hasPair(previousText, currentText, "not here", "here")
                || hasPair(previousText, currentText, "leaving", "already arrived");
    }

    private boolean hasPair(String previous, String current, String first, String second) {
        return previous.contains(first) && current.contains(second);
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private ClipCandidate bestUnused(List<ClipCandidate> pool,
                                     Set<ClipCandidate> selected,
                                     String storyIntent,
                                     CandidateRole role,
                                     Set<ClipCandidate> excluded) {
        return pool.stream()
                .filter(candidate -> !selected.contains(candidate))
                .filter(candidate -> !excluded.contains(candidate))
                .filter(candidate -> role == null || candidate.role() == role)
                .max((first, second) -> Double.compare(
                        repairScore(storyIntent, first), repairScore(storyIntent, second)))
                .orElse(null);
    }

    private double repairScore(String storyIntent, ClipCandidate candidate) {
        return candidate.score() + 0.35 * intentEvidenceScore(storyIntent, candidate);
    }

    private double intentEvidenceScore(String storyIntent, ClipCandidate candidate) {
        Set<String> intent = tokens(storyIntent);
        if (intent.isEmpty()) return 0.0;
        Set<String> evidence = new HashSet<>(tokens(candidate.visualSummary()));
        evidence.addAll(tokens(candidate.spokenText()));
        Set<String> matched = new HashSet<>(intent);
        matched.retainAll(evidence);
        return (double) matched.size() / intent.size();
    }

    private boolean hasWeakIntentAlignment(String storyIntent, List<ClipCandidate> candidates) {
        if (storyIntent == null || storyIntent.isBlank()) return false;
        Set<String> intent = tokens(storyIntent);
        if (intent.isEmpty()) return false;
        Set<String> evidence = new HashSet<>();
        candidates.forEach(candidate -> {
            evidence.addAll(tokens(candidate.visualSummary()));
            evidence.addAll(tokens(candidate.spokenText()));
        });
        Set<String> matched = new HashSet<>(intent);
        matched.retainAll(evidence);
        double coverage = (double) matched.size() / intent.size();
        return coverage > 0.0 && coverage < 0.25;
    }

    private boolean isAnchor(CandidateRole role) {
        return role == CandidateRole.HOOK || role == CandidateRole.PAYOFF || role == CandidateRole.ENDING;
    }

    private boolean hasRepeatedInformation(ClipCandidate previous, ClipCandidate current) {
        Set<String> previousTokens = tokens(previous.spokenText());
        Set<String> currentTokens = tokens(current.spokenText());
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
