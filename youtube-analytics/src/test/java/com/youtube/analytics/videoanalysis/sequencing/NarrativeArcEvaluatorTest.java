package com.youtube.analytics.videoanalysis.sequencing;

import com.youtube.analytics.videoanalysis.model.CandidateRole;
import com.youtube.analytics.videoanalysis.model.ClipCandidate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NarrativeArcEvaluatorTest {

    private final NarrativeArcEvaluator evaluator = new NarrativeArcEvaluator();

    @Test
    void identifiesMissingDevelopmentAndWeakIntentAlignment() {
        List<ClipCandidate> candidates = List.of(
                candidate(CandidateRole.HOOK, "welcome to the trip", "mountain"),
                candidate(CandidateRole.PAYOFF, "we arrived", "destination"),
                candidate(CandidateRole.ENDING, "goodbye", "sunset"));

        List<String> warnings = evaluator.evaluate("mountain food city drive weekend", candidates);

        assertThat(warnings).contains(
                "Narrative arc has no clear development beat between opening and payoff",
                "Selected narrative has weak alignment with the requested story intent");
    }

    @Test
    void detectsRepeatedSpokenInformationAcrossAdjacentClips() {
        List<ClipCandidate> candidates = List.of(
                candidate(CandidateRole.SETUP, "we are driving to the mountain today", "road"),
                candidate(CandidateRole.JOURNEY, "we are driving to the mountain today", "road"),
                candidate(CandidateRole.PAYOFF, "finally arrived", "view"));

        assertThat(evaluator.evaluate("mountain drive", candidates))
                .contains("Adjacent clips repeat substantially the same spoken information");
    }

    @Test
    void acceptsCompleteIntentAlignedArc() {
        List<ClipCandidate> candidates = List.of(
                candidate(CandidateRole.HOOK, "welcome to our mountain drive", "mountain road"),
                candidate(CandidateRole.JOURNEY, "we are driving to the mountain", "road mountain"),
                candidate(CandidateRole.PAYOFF, "finally we reached the mountain view", "mountain view"),
                candidate(CandidateRole.ENDING, "goodbye from the mountain", "mountain sunset"));

        assertThat(evaluator.evaluate("mountain drive", candidates)).isEmpty();
    }

    private ClipCandidate candidate(CandidateRole role, String spokenText, String visualSummary) {
        return new ClipCandidate("trip.mp4", 0, 5_000, role, 0.8, spokenText, visualSummary, List.of());
    }
}
