package com.youtube.analytics.videoanalysis.sequencing;

import com.youtube.analytics.videoanalysis.model.CandidateRole;
import com.youtube.analytics.videoanalysis.model.ClipCandidate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DurationAwareCandidateSelectorTest {

    private final DurationAwareCandidateSelector selector = new DurationAwareCandidateSelector();

    @Test
    void selectsNarrativeAnchorsAndFillsTowardTarget() {
        List<ClipCandidate> candidates = List.of(
                candidate(CandidateRole.HOOK, 0, 30_000, 0.90),
                candidate(CandidateRole.SETUP, 30_000, 60_000, 0.80),
                candidate(CandidateRole.JOURNEY, 60_000, 90_000, 0.70),
                candidate(CandidateRole.PAYOFF, 90_000, 120_000, 0.95),
                candidate(CandidateRole.ENDING, 120_000, 150_000, 0.88));

        List<ClipCandidate> selected = selector.select(candidates, 2L);

        assertThat(selected).extracting(ClipCandidate::role)
                .containsExactlyInAnyOrder(CandidateRole.HOOK, CandidateRole.SETUP,
                        CandidateRole.PAYOFF, CandidateRole.ENDING);
        assertThat(selected).extracting(ClipCandidate::durationMs)
                .allMatch(duration -> duration == 30_000L);
        assertThat(selected).hasSize(4);
    }

    @Test
    void preservesDevelopmentBeatBeforeEfficiencyBasedFill() {
        List<ClipCandidate> candidates = List.of(
                candidate(CandidateRole.HOOK, 0, 10_000, 0.80),
                candidate(CandidateRole.SETUP, 10_000, 20_000, 0.60),
                candidate(CandidateRole.B_ROLL, 20_000, 30_000, 0.99),
                candidate(CandidateRole.PAYOFF, 30_000, 40_000, 0.90),
                candidate(CandidateRole.ENDING, 40_000, 50_000, 0.85));

        List<ClipCandidate> selected = selector.select(candidates, 2L);

        assertThat(selected).extracting(ClipCandidate::role)
                .contains(CandidateRole.SETUP);
    }

    @Test
    void preservesAllCandidatesWhenTargetIsNotProvided() {
        List<ClipCandidate> candidates = List.of(
                candidate(CandidateRole.JOURNEY, 0, 30_000, 0.8),
                candidate(CandidateRole.PAYOFF, 30_000, 60_000, 0.9));

        assertThat(selector.select(candidates, null)).containsExactlyElementsOf(candidates);
    }

    @Test
    void preservesNarrativeAnchorsEvenWhenTheyExceedTarget() {
        List<ClipCandidate> candidates = List.of(
                candidate(CandidateRole.HOOK, 0, 90_000, 0.9),
                candidate(CandidateRole.PAYOFF, 90_000, 180_000, 0.9),
                candidate(CandidateRole.ENDING, 180_000, 270_000, 0.9));

        assertThat(selector.select(candidates, 1L)).hasSize(3);
    }

    private ClipCandidate candidate(CandidateRole role, long startMs, long endMs, double score) {
        return new ClipCandidate("trip.mp4", startMs, endMs, role, score, "", "", List.of());
    }
}
