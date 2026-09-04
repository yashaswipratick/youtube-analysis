package com.youtube.analytics.videoanalysis.sequencing;

import com.youtube.analytics.videoanalysis.model.CandidateRole;
import com.youtube.analytics.videoanalysis.model.ClipCandidate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class SequenceOptimizer {

    private static final Map<CandidateRole, Integer> ROLE_ORDER = roleOrder();

    private final TransitionCoherenceOptimizer transitionCoherenceOptimizer;

    public SequenceOptimizer(TransitionCoherenceOptimizer transitionCoherenceOptimizer) {
        this.transitionCoherenceOptimizer = transitionCoherenceOptimizer;
    }

    public List<ClipCandidate> optimize(List<ClipCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) return List.of();

        List<ClipCandidate> ordered = deduplicateOverlaps(candidates);
        ordered = limitRepeatedSourceRoles(ordered);
        ordered.sort(Comparator.comparingInt((ClipCandidate c) -> ROLE_ORDER.getOrDefault(c.role(), 99))
                .thenComparing(Comparator.comparingDouble(ClipCandidate::score).reversed())
                .thenComparingLong(ClipCandidate::sourceStartMs));

        ordered = optimizeTransitionsWithinRoles(ordered);
        moveStrongestEndingToEnd(ordered);
        return List.copyOf(ordered);
    }

    private List<ClipCandidate> deduplicateOverlaps(List<ClipCandidate> candidates) {
        List<ClipCandidate> ranked = new ArrayList<>(candidates);
        ranked.sort(Comparator.comparingDouble(ClipCandidate::score).reversed()
                .thenComparingLong(ClipCandidate::sourceStartMs));

        Map<String, List<ClipCandidate>> keptBySource = new HashMap<>();
        List<ClipCandidate> kept = new ArrayList<>();
        for (ClipCandidate candidate : ranked) {
            List<ClipCandidate> sourceCandidates = keptBySource.computeIfAbsent(
                    candidate.sourceFileName(), ignored -> new ArrayList<>());
            boolean overlaps = sourceCandidates.stream().anyMatch(existing -> overlaps(existing, candidate));
            if (!overlaps) {
                sourceCandidates.add(candidate);
                kept.add(candidate);
            }
        }
        return kept;
    }

    private boolean overlaps(ClipCandidate first, ClipCandidate second) {
        return first.sourceStartMs() < second.sourceEndMs()
                && second.sourceStartMs() < first.sourceEndMs();
    }

    private List<ClipCandidate> limitRepeatedSourceRoles(List<ClipCandidate> candidates) {
        Map<CandidateRole, Set<String>> sourcesByRole = new EnumMap<>(CandidateRole.class);
        candidates.forEach(candidate -> sourcesByRole
                .computeIfAbsent(candidate.role(), ignored -> new HashSet<>())
                .add(candidate.sourceFileName()));

        Map<String, Integer> sourceRoleCounts = new HashMap<>();
        List<ClipCandidate> selected = new ArrayList<>();
        List<ClipCandidate> ranked = new ArrayList<>(candidates);
        ranked.sort(Comparator.comparingDouble(ClipCandidate::score).reversed()
                .thenComparingLong(ClipCandidate::sourceStartMs));

        for (ClipCandidate candidate : ranked) {
            int sourceCount = sourcesByRole.getOrDefault(candidate.role(), Set.of()).size();
            String key = candidate.role().name() + "\\u0000" + candidate.sourceFileName();
            int currentCount = sourceRoleCounts.getOrDefault(key, 0);
            if (sourceCount > 1 && currentCount >= 2) continue;
            sourceRoleCounts.put(key, currentCount + 1);
            selected.add(candidate);
        }
        return selected;
    }

    private List<ClipCandidate> optimizeTransitionsWithinRoles(List<ClipCandidate> candidates) {
        List<ClipCandidate> optimized = new ArrayList<>();
        int index = 0;
        while (index < candidates.size()) {
            CandidateRole role = candidates.get(index).role();
            int end = index + 1;
            while (end < candidates.size() && candidates.get(end).role() == role) end++;
            List<ClipCandidate> group = new ArrayList<>(candidates.subList(index, end));
            optimized.addAll(transitionCoherenceOptimizer.optimize(group));
            index = end;
        }
        return optimized;
    }

    private void moveStrongestEndingToEnd(List<ClipCandidate> candidates) {
        int endingIndex = -1;
        for (int i = 0; i < candidates.size(); i++) {
            if (candidates.get(i).role() == CandidateRole.ENDING) {
                endingIndex = i;
                break;
            }
        }
        if (endingIndex >= 0 && endingIndex < candidates.size() - 1) {
            candidates.add(candidates.remove(endingIndex));
        }
    }

    private static Map<CandidateRole, Integer> roleOrder() {
        Map<CandidateRole, Integer> order = new EnumMap<>(CandidateRole.class);
        order.put(CandidateRole.HOOK, 1);
        order.put(CandidateRole.SETUP, 2);
        order.put(CandidateRole.JOURNEY, 3);
        order.put(CandidateRole.VOICE_BRIDGE, 4);
        order.put(CandidateRole.EXPERIENCE, 5);
        order.put(CandidateRole.PAYOFF, 6);
        order.put(CandidateRole.B_ROLL, 7);
        order.put(CandidateRole.ENDING, 8);
        order.put(CandidateRole.UNKNOWN, 9);
        return Map.copyOf(order);
    }
}
