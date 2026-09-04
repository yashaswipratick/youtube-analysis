package com.youtube.analytics.videoanalysis.timeline;

import com.youtube.analytics.videoanalysis.model.CandidateRole;
import com.youtube.analytics.videoanalysis.model.ClipCandidate;
import com.youtube.analytics.videoanalysis.model.EditPlan;
import com.youtube.analytics.videoanalysis.sequencing.NarrativeArcEvaluator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TimelineOptimizerTest {

    private final TimelineOptimizer optimizer = new TimelineOptimizer(new NarrativeArcEvaluator());

    @Test
    void warnsWhenNarrativeAnchorsAreMissing() {
        List<ClipCandidate> candidates = List.of(
                candidate(CandidateRole.JOURNEY, 0, 5000),
                candidate(CandidateRole.EXPERIENCE, 5000, 9000));

        EditPlan plan = optimizer.buildPlan("trip", "weekend mountain trip", candidates);

        assertThat(plan.warnings()).containsExactly(
                "No hook candidate was identified; the edit may lack a strong opening",
                "No payoff candidate was identified; the edit may lack a clear destination or conclusion",
                "No ending candidate was identified; the edit may need an explicit close");
    }

    @Test
    void doesNotWarnWhenAllNarrativeAnchorsExist() {
        List<ClipCandidate> candidates = List.of(
                candidate(CandidateRole.HOOK, 0, 2000),
                candidate(CandidateRole.PAYOFF, 2000, 5000),
                candidate(CandidateRole.ENDING, 5000, 7000));

        EditPlan plan = optimizer.buildPlan("trip", "weekend mountain trip", candidates);

        assertThat(plan.warnings()).containsExactly(
                "Narrative arc has no clear development beat between opening and payoff");
        assertThat(plan.totalDurationMs()).isEqualTo(7000);
        assertThat(plan.sequence()).extracting(EditPlan.EditSequenceItem::timelineStartMs)
                .containsExactly(0L, 2000L, 5000L);
    }

    @Test
    void warnsWhenTargetDurationIsExceededButNarrativeAnchorsArePreserved() {
        List<ClipCandidate> candidates = List.of(
                candidate(CandidateRole.HOOK, 0, 40_000),
                candidate(CandidateRole.PAYOFF, 40_000, 80_000),
                candidate(CandidateRole.ENDING, 80_000, 120_000));

        EditPlan plan = optimizer.buildPlan("trip", "weekend mountain trip", candidates, 1L);

        assertThat(plan.totalDurationMs()).isEqualTo(120_000);
        assertThat(plan.warnings()).containsExactly(
                "Narrative arc has no clear development beat between opening and payoff",
                "Selected edit exceeds the target duration of 1 minutes; narrative anchors were preserved");
    }

    @Test
    void doesNotWarnWhenTargetDurationIsMet() {
        List<ClipCandidate> candidates = List.of(
                candidate(CandidateRole.HOOK, 0, 20_000),
                candidate(CandidateRole.PAYOFF, 20_000, 40_000),
                candidate(CandidateRole.ENDING, 40_000, 60_000));

        EditPlan plan = optimizer.buildPlan("trip", "weekend mountain trip", candidates, 1L);

        assertThat(plan.warnings()).containsExactly(
                "Narrative arc has no clear development beat between opening and payoff");
    }

    @Test
    void warnsWhenThereAreNoUsableCandidates() {
        EditPlan plan = optimizer.buildPlan("trip", "weekend mountain trip", List.of());

        assertThat(plan.warnings()).containsExactly("No usable scene candidates were supplied");
        assertThat(plan.sequence()).isEmpty();
        assertThat(plan.totalDurationMs()).isZero();
    }

    private ClipCandidate candidate(CandidateRole role, long startMs, long endMs) {
        return new ClipCandidate("trip.mp4", startMs, endMs, role, 0.8,
                "", "", List.of("test candidate"));
    }
}
