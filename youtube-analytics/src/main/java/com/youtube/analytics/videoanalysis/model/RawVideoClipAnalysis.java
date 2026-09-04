package com.youtube.analytics.videoanalysis.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.List;

public record RawVideoClipAnalysis(
        @NotBlank String sourceFileName,
        @PositiveOrZero long durationMs,
        @Valid List<SceneSegment> scenes,
        @Valid List<SpeechSegment> speechSegments,
        @Valid AudioProfile audio,
        @PositiveOrZero double visualQualityScore) {
}
