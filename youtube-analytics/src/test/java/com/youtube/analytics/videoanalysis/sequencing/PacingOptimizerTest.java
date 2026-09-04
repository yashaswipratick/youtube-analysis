package com.youtube.analytics.videoanalysis.sequencing;

import com.youtube.analytics.videoanalysis.model.CandidateRole;
import com.youtube.analytics.videoanalysis.model.ClipCandidate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PacingOptimizerTest {

    private final PacingOptimizer optimizer = new PacingOptimizer();

    @Test
    void trimsLongCandidatesAccordingToNarrativeRole() {
        ClipCandidate hook = candidate(CandidateRole.HOOK, 10_000, 30_000, 0.9);
        ClipCandidate journey = candidate(CandidateRole.JOURNEY, 40_000, 60_000, 0.8);
        ClipCandidate ending = candidate(CandidateRole.ENDING, 70_000, 90_000, 0.7);

        List<ClipCandidate> paced = optimizer.optimize(List.of(hook, journey, ending));

        assertThat(paced).extracting(ClipCandidate::durationMs)
                .containsExactly(8_000L, 6_000L, 5_000L);
        assertThat(paced).extracting(ClipCandidate::sourceStartMs)
                .containsExactly(10_000L, 40_000L, 70_000L);
    }

    @Test
    void preservesShortCandidatesAndCandidateMetadata() {
        ClipCandidate candidate = new ClipCandidate(
                "trip.mp4", 1_000, 5_000, CandidateRole.B_ROLL, 0.82,
                "spoken", "mountain road", List.of("strong visual quality"));

        assertThat(optimizer.optimize(List.of(candidate))).containsExactly(candidate);
    }

    @Test
    void handlesNullOrEmptyCandidates() {
        assertThat(optimizer.optimize(null)).isEmpty();
        assertThat(optimizer.optimize(List.of())).isEmpty();
    }

    private ClipCandidate candidate(CandidateRole role, long startMs, long endMs, double score) {
        return new ClipCandidate("trip.mp4", startMs, endMs, role, score, "spoken", "visual", List.of());
    }
}
