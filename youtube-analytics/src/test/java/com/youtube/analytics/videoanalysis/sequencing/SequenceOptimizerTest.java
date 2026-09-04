package com.youtube.analytics.videoanalysis.sequencing;

import com.youtube.analytics.videoanalysis.model.CandidateRole;
import com.youtube.analytics.videoanalysis.model.ClipCandidate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SequenceOptimizerTest {

    private final SequenceOptimizer optimizer = new SequenceOptimizer();

    @Test
    void keepsNarrativeRoleOrderButAlwaysClosesWithEnding() {
        List<ClipCandidate> candidates = List.of(
                candidate(CandidateRole.ENDING, 0, 1000, 0.95),
                candidate(CandidateRole.PAYOFF, 1000, 3000, 0.70),
                candidate(CandidateRole.HOOK, 3000, 4000, 0.80),
                candidate(CandidateRole.JOURNEY, 4000, 6000, 0.90));

        List<ClipCandidate> ordered = optimizer.optimize(candidates);

        assertThat(ordered).extracting(ClipCandidate::role)
                .containsExactly(CandidateRole.HOOK, CandidateRole.JOURNEY, CandidateRole.PAYOFF, CandidateRole.ENDING);
    }

    @Test
    void usesTimestampAsDeterministicTieBreakerWithinRole() {
        List<ClipCandidate> candidates = List.of(
                candidate(CandidateRole.JOURNEY, 5000, 6000, 0.80),
                candidate(CandidateRole.JOURNEY, 1000, 2000, 0.80));

        List<ClipCandidate> ordered = optimizer.optimize(candidates);

        assertThat(ordered).extracting(ClipCandidate::sourceStartMs).containsExactly(1000L, 5000L);
    }

    @Test
    void dropsOverlappingCandidatesFromSameSourceAndKeepsHigherScore() {
        List<ClipCandidate> candidates = List.of(
                candidate(CandidateRole.JOURNEY, 1000, 4000, 0.80),
                candidate(CandidateRole.B_ROLL, 3000, 5000, 0.60),
                candidate(CandidateRole.EXPERIENCE, 5000, 7000, 0.70));

        List<ClipCandidate> ordered = optimizer.optimize(candidates);

        assertThat(ordered).extracting(ClipCandidate::sourceStartMs).containsExactly(1000L, 5000L);
        assertThat(ordered).extracting(ClipCandidate::score).containsExactly(0.80, 0.70);
    }

    @Test
    void keepsOverlappingCandidatesFromDifferentSources() {
        List<ClipCandidate> candidates = List.of(
                candidate("camera-a.mp4", CandidateRole.JOURNEY, 1000, 4000, 0.80),
                candidate("camera-b.mp4", CandidateRole.JOURNEY, 2000, 5000, 0.70));

        List<ClipCandidate> ordered = optimizer.optimize(candidates);

        assertThat(ordered).hasSize(2);
    }

    @Test
    void limitsRepeatedSourceWithinRoleWhenAlternativeSourceExists() {
        List<ClipCandidate> candidates = List.of(
                candidate("camera-a.mp4", CandidateRole.JOURNEY, 1000, 1500, 0.95),
                candidate("camera-a.mp4", CandidateRole.JOURNEY, 2000, 2500, 0.90),
                candidate("camera-a.mp4", CandidateRole.JOURNEY, 3000, 3500, 0.85),
                candidate("camera-b.mp4", CandidateRole.JOURNEY, 4000, 4500, 0.80));

        List<ClipCandidate> ordered = optimizer.optimize(candidates);

        assertThat(ordered).hasSize(3);
        assertThat(ordered).extracting(ClipCandidate::sourceFileName)
                .containsExactly("camera-a.mp4", "camera-a.mp4", "camera-b.mp4");
    }

    @Test
    void doesNotLimitSourceWhenItIsTheOnlySourceForRole() {
        List<ClipCandidate> candidates = List.of(
                candidate("camera-a.mp4", CandidateRole.JOURNEY, 1000, 1500, 0.95),
                candidate("camera-a.mp4", CandidateRole.JOURNEY, 2000, 2500, 0.90),
                candidate("camera-a.mp4", CandidateRole.JOURNEY, 3000, 3500, 0.85));

        assertThat(optimizer.optimize(candidates)).hasSize(3);
    }

    @Test
    void returnsEmptyListForNullOrEmptyInput() {
        assertThat(optimizer.optimize(List.of())).isEmpty();
        assertThat(optimizer.optimize(null)).isEmpty();
    }

    private ClipCandidate candidate(CandidateRole role, long startMs, long endMs, double score) {
        return candidate("trip.mp4", role, startMs, endMs, score);
    }

    private ClipCandidate candidate(String sourceFileName, CandidateRole role,
                                    long startMs, long endMs, double score) {
        return new ClipCandidate(sourceFileName, startMs, endMs, role, score, "", "", List.of());
    }
}
