package com.youtube.analytics.videoanalysis.sequencing;

import com.youtube.analytics.videoanalysis.model.CandidateRole;
import com.youtube.analytics.videoanalysis.model.ClipCandidate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TransitionCoherenceOptimizerTest {

    private final TransitionCoherenceOptimizer optimizer = new TransitionCoherenceOptimizer();

    @Test
    void prefersSemanticallyRelatedNextClip() {
        ClipCandidate previous = candidate("mountain road", CandidateRole.JOURNEY, 0);
        ClipCandidate related = candidate("mountain road drive", CandidateRole.JOURNEY, 1000);
        ClipCandidate unrelated = candidate("restaurant food", CandidateRole.JOURNEY, 2000);

        List<ClipCandidate> ordered = optimizer.optimize(List.of(previous, unrelated, related));

        assertThat(ordered).extracting(ClipCandidate::sourceStartMs)
                .containsExactly(0L, 1000L, 2000L);
    }

    @Test
    void prefersSameSourceWhenSemanticEvidenceIsOtherwiseEqual() {
        ClipCandidate previous = candidate("road drive", CandidateRole.JOURNEY, 0);
        ClipCandidate sameSource = candidate("road drive", CandidateRole.JOURNEY, 1000);
        ClipCandidate otherSource = new ClipCandidate(
                "camera-b.mp4", 2000, 3000, CandidateRole.JOURNEY, 0.8,
                "", "road drive", List.of());

        List<ClipCandidate> ordered = optimizer.optimize(List.of(previous, otherSource, sameSource));

        assertThat(ordered).extracting(ClipCandidate::sourceFileName)
                .containsExactly("trip.mp4", "trip.mp4", "camera-b.mp4");
    }

    @Test
    void prefersSpokenContinuityWhenVisualSummariesDiffer() {
        ClipCandidate previous = candidateWithSpeech("road", "we are heading to the hills", CandidateRole.JOURNEY, 0);
        ClipCandidate related = candidateWithSpeech("car", "we are heading to the hills now", CandidateRole.JOURNEY, 1000);
        ClipCandidate unrelated = candidateWithSpeech("car", "we are eating lunch now", CandidateRole.JOURNEY, 2000);

        List<ClipCandidate> ordered = optimizer.optimize(List.of(previous, unrelated, related));

        assertThat(ordered).extracting(ClipCandidate::sourceStartMs)
                .containsExactly(0L, 1000L, 2000L);
    }

    @Test
    void penalizesContradictorySpokenHandoff() {
        ClipCandidate previous = candidateWithSpeech("road", "we are not going to the hills", CandidateRole.JOURNEY, 0);
        ClipCandidate contradictory = candidateWithSpeech("hills", "we are going to the hills", CandidateRole.JOURNEY, 1000);
        ClipCandidate consistent = candidateWithSpeech("road", "we are still driving", CandidateRole.JOURNEY, 2000);

        List<ClipCandidate> ordered = optimizer.optimize(List.of(previous, contradictory, consistent));

        assertThat(ordered).extracting(ClipCandidate::sourceStartMs)
                .containsExactly(0L, 2000L, 1000L);
    }

    @Test
    void handlesNullAndSingleCandidate() {
        assertThat(optimizer.optimize(null)).isEmpty();
        ClipCandidate candidate = candidate("road", CandidateRole.JOURNEY, 0);
        assertThat(optimizer.optimize(List.of(candidate))).containsExactly(candidate);
    }

    private ClipCandidate candidate(String visualSummary, CandidateRole role, long startMs) {
        return candidateWithSpeech(visualSummary, "", role, startMs);
    }

    private ClipCandidate candidateWithSpeech(String visualSummary, String spokenText,
                                              CandidateRole role, long startMs) {
        return new ClipCandidate("trip.mp4", startMs, startMs + 1000, role, 0.8,
                spokenText, visualSummary, List.of());
    }
}
