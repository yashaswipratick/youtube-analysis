package com.youtube.analytics.videoanalysis.sequencing;

import com.youtube.analytics.videoanalysis.model.CandidateRole;
import com.youtube.analytics.videoanalysis.model.ClipCandidate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalCandidateOptimizerTest {

    private final GlobalCandidateOptimizer optimizer =
            new GlobalCandidateOptimizer(new StoryIntentMatcher(), new TransitionCoherenceOptimizer());

    @Test
    void replacesWeakCandidateWithGloballyBetterIntentAlignedAlternative() {
        ClipCandidate hook = candidate("hook", CandidateRole.HOOK, 0.90, "city", "welcome");
        ClipCandidate weak = candidate("weak", CandidateRole.JOURNEY, 0.55, "generic road", "");
        ClipCandidate better = candidate("better", CandidateRole.JOURNEY, 0.80, "mountain road drive", "driving to the mountain");

        List<ClipCandidate> result = optimizer.optimize(
                "mountain drive", List.of(hook, weak), List.of(hook, weak, better));

        assertThat(result).containsExactly(hook, better);
    }

    @Test
    void preservesNarrativeAnchors() {
        ClipCandidate hook = candidate("hook", CandidateRole.HOOK, 0.40, "city", "welcome");
        ClipCandidate payoff = candidate("payoff", CandidateRole.PAYOFF, 0.40, "view", "arrived");
        ClipCandidate ending = candidate("ending", CandidateRole.ENDING, 0.40, "sunset", "goodbye");
        ClipCandidate replacement = candidate("better", CandidateRole.PAYOFF, 0.99, "destination", "arrived");

        List<ClipCandidate> result = optimizer.optimize(
                "destination", List.of(hook, payoff, ending), List.of(hook, payoff, ending, replacement));

        assertThat(result).containsExactly(hook, payoff, ending);
    }

    private ClipCandidate candidate(String file, CandidateRole role, double score, String visual, String speech) {
        return new ClipCandidate(file, 0, 5_000, role, score, speech, visual, List.of());
    }
}
