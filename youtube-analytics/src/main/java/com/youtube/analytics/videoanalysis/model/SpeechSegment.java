package com.youtube.analytics.videoanalysis.model;

import jakarta.validation.constraints.PositiveOrZero;

public record SpeechSegment(
        @PositiveOrZero long startMs,
        @PositiveOrZero long endMs,
        String text,
        @PositiveOrZero double clarityScore) {

    public SpeechSegment {
        if (endMs < startMs) throw new IllegalArgumentException("Speech endMs must be >= startMs");
        if (clarityScore > 1.0) throw new IllegalArgumentException("clarityScore must be <= 1.0");
    }
}
