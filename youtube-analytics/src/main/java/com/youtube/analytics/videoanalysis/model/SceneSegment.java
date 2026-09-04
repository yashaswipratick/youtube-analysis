package com.youtube.analytics.videoanalysis.model;

import jakarta.validation.constraints.PositiveOrZero;

public record SceneSegment(
        @PositiveOrZero long startMs,
        @PositiveOrZero long endMs,
        String visualSummary,
        @PositiveOrZero double visualScore) {

    public SceneSegment {
        if (endMs < startMs) throw new IllegalArgumentException("Scene endMs must be >= startMs");
        if (visualScore > 1.0) throw new IllegalArgumentException("visualScore must be <= 1.0");
    }
}
