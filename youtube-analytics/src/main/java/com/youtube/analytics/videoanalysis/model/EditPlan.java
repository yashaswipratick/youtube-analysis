package com.youtube.analytics.videoanalysis.model;

import java.util.List;

public record EditPlan(
        String projectId,
        String storyIntent,
        List<EditSequenceItem> sequence,
        long totalDurationMs,
        List<String> warnings) {

    public record EditSequenceItem(
            int sequenceNumber,
            ClipCandidate clip,
            long timelineStartMs,
            long timelineEndMs,
            String placementReason) {
    }
}
