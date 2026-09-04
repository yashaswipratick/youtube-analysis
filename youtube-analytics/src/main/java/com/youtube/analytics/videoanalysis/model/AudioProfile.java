package com.youtube.analytics.videoanalysis.model;

import jakarta.validation.constraints.PositiveOrZero;

public record AudioProfile(
        boolean speechPresent,
        @PositiveOrZero double speechClarityScore,
        @PositiveOrZero double backgroundNoiseScore,
        boolean musicPresent) {

    public AudioProfile {
        if (speechClarityScore > 1.0 || backgroundNoiseScore > 1.0) {
            throw new IllegalArgumentException("Audio scores must be <= 1.0");
        }
    }
}
