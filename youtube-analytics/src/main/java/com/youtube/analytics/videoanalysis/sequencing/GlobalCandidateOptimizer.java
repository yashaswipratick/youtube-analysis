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
public class GlobalCandidateOptimizer {

    private static final Set<CandidateRole> ANCHORS = EnumSet.of(
            CandidateRole.HOOK, CandidateRole.PAYOFF, CandidateRole.ENDING);

    private final StoryIntentMatcher storyIntentMatcher;
    private final TransitionCoherenceOptimizer transitionCoherenceOptimizer;

    public GlobalCandidateOptimizer(StoryIntentMatcher storyIntentMatcher,
                                    TransitionCoherenceOptimizer transitionCoherenceOptimizer) {
        this.storyIntentMatcher = storyIntentMatcher;
        this.transitionCoherenceOptimizer = transitionCoherenceOptimizer;
    }

    public List<ClipCandidate> optimize(String storyIntent,
                                         List<ClipCandidate> current,
                                         List<ClipCandidate> candidatePool) {
        if (current == null || current.isEmpty() || candidatePool == null || candidatePool.isEmpty()) {
            return current == null ? List.of() : List.copyOf(current);
        }

        List<ClipCandidate> optimized = new ArrayList<>(current);
        Set<ClipCandidate> used = new HashSet<>(optimized);
        for (int pass = 0; pass < 2; pass++) {
            boolean changed = false;
            for (int index = 0; index < optimized.size(); index++) {
                final int position = index;
                ClipCandidate currentCandidate = optimized.get(position);
                if (ANCHORS.contains(currentCandidate.role())) continue;

                ClipCandidate replacement = candidatePool.stream()
                        .filter(candidate -> candidate.role() == currentCandidate.role())
                        .filter(candidate -> !used.contains(candidate))
                        .filter(candidate -> objectiveGain(storyIntent, optimized, position, candidate, currentCandidate) > 0.05)
                        .max(Comparator.comparingDouble(candidate -> objectiveGain(
                                storyIntent, optimized, position, candidate, currentCandidate)))
                        .orElse(null);
                if (replacement != null) {
                    used.remove(currentCandidate);
                    used.add(replacement);
                    optimized.set(position, replacement);
                    changed = true;
                }
            }
            if (!changed) break;
        }
        return List.copyOf(optimized);
    }

    private double objectiveGain(String storyIntent, List<ClipCandidate> sequence, int index,
                                 ClipCandidate replacement, ClipCandidate current) {
        double scoreGain = replacement.score() - current.score();
        double intentGain = storyIntentMatcher.relevance(storyIntent, replacement.visualSummary(), replacement.spokenText())
                - storyIntentMatcher.relevance(storyIntent, current.visualSummary(), current.spokenText());
        double previousGain = index == 0 ? 0.0
                : transitionCoherenceOptimizer.coherence(sequence.get(index - 1), replacement)
                - transitionCoherenceOptimizer.coherence(sequence.get(index - 1), current);
        double nextGain = index == sequence.size() - 1 ? 0.0
                : transitionCoherenceOptimizer.coherence(replacement, sequence.get(index + 1))
                - transitionCoherenceOptimizer.coherence(current, sequence.get(index + 1));
        return 0.45 * scoreGain + 0.35 * intentGain + 0.20 * (previousGain + nextGain) / 2.0;
    }
}
