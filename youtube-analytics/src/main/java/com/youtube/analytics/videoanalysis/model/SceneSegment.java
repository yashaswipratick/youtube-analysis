package com.youtube.analytics.videoanalysis.model;

import jakarta.validation.constraints.PositiveOrZero;

public record SceneSegment(
        @PositiveOrZero long startMs,
        @PositiveOrZero long endMs,
        String visualSummary,
        @PositiveOrZero double visualScore,
        VisualObservation deterministicVisual,
        VisualObservation aiVisual,
        VisualAnalysisStatus aiStatus) {

    public SceneSegment(long startMs, long endMs, String visualSummary, double visualScore) {
        this(startMs, endMs, visualSummary, visualScore, null, null, VisualAnalysisStatus.NOT_REQUESTED);
    }

    public SceneSegment {
        if (endMs < startMs) throw new IllegalArgumentException("Scene endMs must be >= startMs");
        if (visualScore > 1.0) throw new IllegalArgumentException("visualScore must be <= 1.0");
        aiStatus = aiStatus == null ? VisualAnalysisStatus.NOT_REQUESTED : aiStatus;
    }
}
