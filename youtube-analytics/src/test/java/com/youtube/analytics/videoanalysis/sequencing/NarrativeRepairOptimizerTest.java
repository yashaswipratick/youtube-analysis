package com.youtube.analytics.videoanalysis.sequencing;

import com.youtube.analytics.videoanalysis.model.CandidateRole;
import com.youtube.analytics.videoanalysis.model.ClipCandidate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NarrativeRepairOptimizerTest {

    private final NarrativeRepairOptimizer optimizer = new NarrativeRepairOptimizer();

    @Test
    void addsUnusedDevelopmentCandidateWhenNarrativeArcHasNoDevelopment() {
        ClipCandidate hook = candidate(CandidateRole.HOOK, 0.90, "welcome to the trip", "opening road");
        ClipCandidate payoff = candidate(CandidateRole.PAYOFF, 0.90, "finally reached the hills", "mountain view");
        ClipCandidate ending = candidate(CandidateRole.ENDING, 0.85, "see you next time", "sunset");
        ClipCandidate journey = candidate(CandidateRole.JOURNEY, 0.75, "we are driving to the hills", "road mountain");

        List<ClipCandidate> result = optimizer.repair("mountain road trip", List.of(hook, payoff, ending),
                List.of(hook, payoff, ending, journey));

        assertEquals(4, result.size());
        assertTrue(result.contains(journey));
    }

    @Test
    void replacesWeakNonAnchorCandidateWithBetterIntentAlignedAlternative() {
        ClipCandidate hook = candidate(CandidateRole.HOOK, 0.90, "welcome", "city");
        ClipCandidate setup = candidate(CandidateRole.SETUP, 0.50, "we are starting", "office");
        ClipCandidate alignedSetup = candidate(CandidateRole.SETUP, 0.45, "we are heading to the mountain", "mountain road");
        ClipCandidate payoff = candidate(CandidateRole.PAYOFF, 0.90, "finally arrived", "mountain view");
        ClipCandidate ending = candidate(CandidateRole.ENDING, 0.85, "see you", "sunset");

        List<ClipCandidate> result = optimizer.repair("mountain forest beach weekend city ocean village water trip", List.of(hook, setup, payoff, ending),
                List.of(hook, setup, alignedSetup, payoff, ending));

        assertTrue(result.contains(alignedSetup));
        assertTrue(!result.contains(setup));
    }

    @Test
    void repairsRepeatedAdjacentSpeechWhenAlternativeIsStrongEnough() {
        ClipCandidate first = candidate(CandidateRole.SETUP, 0.80, "we are going to the mountain today", "road");
        ClipCandidate repeated = candidate(CandidateRole.JOURNEY, 0.80, "we are going to the mountain today", "car");
        ClipCandidate alternative = candidate(CandidateRole.JOURNEY, 0.65, "now we are driving through the forest", "forest road");

        List<ClipCandidate> result = optimizer.repair("mountain drive", List.of(first, repeated),
                List.of(first, repeated, alternative));

        assertEquals(alternative, result.get(1));
    }

    @Test
    void repairsContradictoryAdjacentSpeechWhenAlternativeExists() {
        ClipCandidate previous = candidate(CandidateRole.SETUP, 0.80, "we are not going to the mountain", "road");
        ClipCandidate contradictory = candidate(CandidateRole.JOURNEY, 0.80, "we are going to the mountain", "car");
        ClipCandidate alternative = candidate(CandidateRole.JOURNEY, 0.65, "now we are driving through the forest", "forest road");

        List<ClipCandidate> result = optimizer.repair("mountain drive", List.of(previous, contradictory),
                List.of(previous, contradictory, alternative));

        assertEquals(alternative, result.get(1));
    }

    @Test
    void leavesSequenceUnchangedWhenNoRepairCandidateExists() {
        ClipCandidate hook = candidate(CandidateRole.HOOK, 0.90, "welcome", "road");
        ClipCandidate payoff = candidate(CandidateRole.PAYOFF, 0.90, "arrived", "mountain");
        ClipCandidate ending = candidate(CandidateRole.ENDING, 0.85, "goodbye", "sunset");

        assertEquals(List.of(hook, payoff, ending), optimizer.repair("mountain trip",
                List.of(hook, payoff, ending), List.of(hook, payoff, ending)));
    }

    private ClipCandidate candidate(CandidateRole role, double score, String speech, String visual) {
        return new ClipCandidate("clip.mp4", (long) (score * 1000), (long) (score * 1000) + 5000,
                role, score, speech, visual, List.of());
    }
}
