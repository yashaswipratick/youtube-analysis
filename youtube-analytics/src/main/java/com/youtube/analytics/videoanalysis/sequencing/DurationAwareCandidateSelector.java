package com.youtube.analytics.videoanalysis.sequencing;

import com.youtube.analytics.videoanalysis.model.CandidateRole;
import com.youtube.analytics.videoanalysis.model.ClipCandidate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class DurationAwareCandidateSelector {

    private static final Set<CandidateRole> NARRATIVE_ANCHORS = EnumSet.of(
            CandidateRole.HOOK, CandidateRole.PAYOFF, CandidateRole.ENDING);

    public List<ClipCandidate> select(List<ClipCandidate> candidates, Long targetDurationMinutes) {
        if (candidates == null || candidates.isEmpty() || targetDurationMinutes == null) {
            return candidates == null ? List.of() : List.copyOf(candidates);
        }

        long targetDurationMs = Math.multiplyExact(targetDurationMinutes, 60_000L);
        List<ClipCandidate> ranked = new ArrayList<>(candidates);
        ranked.sort(Comparator.comparingDouble(ClipCandidate::score).reversed()
                .thenComparingLong(ClipCandidate::sourceStartMs));

        List<ClipCandidate> selected = new ArrayList<>();
        Set<ClipCandidate> selectedSet = new HashSet<>();
        long selectedDurationMs = 0;

        for (CandidateRole anchor : NARRATIVE_ANCHORS) {
            ClipCandidate strongest = ranked.stream()
                    .filter(candidate -> candidate.role() == anchor)
                    .findFirst()
                    .orElse(null);
            if (strongest != null && selectedSet.add(strongest)) {
                selected.add(strongest);
                selectedDurationMs += strongest.durationMs();
            }
        }

        List<ClipCandidate> fillCandidates = ranked.stream()
                .filter(candidate -> !selectedSet.contains(candidate))
                .sorted(Comparator.comparingDouble(this::durationEfficiency).reversed()
                        .thenComparing(Comparator.comparingDouble(ClipCandidate::score).reversed())
                        .thenComparingLong(ClipCandidate::sourceStartMs))
                .toList();

        for (ClipCandidate candidate : fillCandidates) {
            long duration = candidate.durationMs();
            if (duration <= 0) continue;
            if (selectedDurationMs + duration <= targetDurationMs) {
                selected.add(candidate);
                selectedSet.add(candidate);
                selectedDurationMs += duration;
            }
        }

        return List.copyOf(selected);
    }

    private double durationEfficiency(ClipCandidate candidate) {
        return candidate.score() / Math.max(1L, candidate.durationMs());
    }
}
