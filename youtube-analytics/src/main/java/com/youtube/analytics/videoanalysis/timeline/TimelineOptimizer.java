package com.youtube.analytics.videoanalysis.timeline;

import com.youtube.analytics.videoanalysis.model.CandidateRole;
import com.youtube.analytics.videoanalysis.model.ClipCandidate;
import com.youtube.analytics.videoanalysis.model.EditPlan;
import com.youtube.analytics.videoanalysis.sequencing.NarrativeArcEvaluator;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

@Service
public class TimelineOptimizer {

    private final NarrativeArcEvaluator narrativeArcEvaluator;

    public TimelineOptimizer(NarrativeArcEvaluator narrativeArcEvaluator) {
        this.narrativeArcEvaluator = narrativeArcEvaluator;
    }

    public EditPlan buildPlan(String projectId, String storyIntent, List<ClipCandidate> orderedCandidates) {
        return buildPlan(projectId, storyIntent, orderedCandidates, null);
    }

    public EditPlan buildPlan(String projectId, String storyIntent,
                              List<ClipCandidate> orderedCandidates, Long targetDurationMinutes) {
        long cursor = 0;
        List<EditPlan.EditSequenceItem> sequence = new ArrayList<>();
        for (int i = 0; i < orderedCandidates.size(); i++) {
            ClipCandidate clip = orderedCandidates.get(i);
            long duration = clip.durationMs();
            long start = cursor;
            long end = cursor + duration;
            sequence.add(new EditPlan.EditSequenceItem(i + 1, clip, start, end,
                    placementReason(i, clip)));
            cursor = end;
        }
        List<String> warnings = new ArrayList<>(narrativeWarnings(orderedCandidates));
        warnings.addAll(narrativeArcEvaluator.evaluate(storyIntent, orderedCandidates));
        addDurationWarning(warnings, cursor, targetDurationMinutes);

        return new EditPlan(projectId, storyIntent, List.copyOf(sequence), cursor, List.copyOf(warnings));
    }

    private void addDurationWarning(List<String> warnings, long actualDurationMs, Long targetDurationMinutes) {
        if (targetDurationMinutes == null) return;

        long targetDurationMs = Math.multiplyExact(targetDurationMinutes, 60_000L);
        if (actualDurationMs > targetDurationMs) {
            warnings.add("Selected edit exceeds the target duration of " + targetDurationMinutes
                    + " minutes; narrative anchors were preserved");
        }
    }

    private String placementReason(int index, ClipCandidate clip) {
        return index == 0
                ? "Placed first as the strongest opening candidate for its assigned role"
                : "Placed according to structural role order, then candidate score";
    }

    private List<String> narrativeWarnings(List<ClipCandidate> candidates) {
        if (candidates.isEmpty()) {
            return List.of("No usable scene candidates were supplied");
        }

        Set<CandidateRole> roles = EnumSet.noneOf(CandidateRole.class);
        candidates.stream()
                .map(ClipCandidate::role)
                .forEach(roles::add);

        List<String> warnings = new ArrayList<>();
        if (!roles.contains(CandidateRole.HOOK)) {
            warnings.add("No hook candidate was identified; the edit may lack a strong opening");
        }
        if (!roles.contains(CandidateRole.PAYOFF)) {
            warnings.add("No payoff candidate was identified; the edit may lack a clear destination or conclusion");
        }
        if (!roles.contains(CandidateRole.ENDING)) {
            warnings.add("No ending candidate was identified; the edit may need an explicit close");
        }
        return List.copyOf(warnings);
    }
}
