package com.youtube.analytics.videoanalysis.timeline;

import com.youtube.analytics.videoanalysis.model.ClipCandidate;
import com.youtube.analytics.videoanalysis.model.EditPlan;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class TimelineOptimizer {

    public EditPlan buildPlan(String projectId, String storyIntent, List<ClipCandidate> orderedCandidates) {
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
        List<String> warnings = orderedCandidates.isEmpty()
                ? List.of("No usable scene candidates were supplied")
                : List.of();
        return new EditPlan(projectId, storyIntent, List.copyOf(sequence), cursor, warnings);
    }

    private String placementReason(int index, ClipCandidate clip) {
        return index == 0
                ? "Placed first as the strongest opening candidate for its assigned role"
                : "Placed according to structural role order, then candidate score";
    }
}
