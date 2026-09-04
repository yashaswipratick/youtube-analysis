package com.youtube.analytics.videoanalysis.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;

import java.util.List;

public record RawVideoAnalysisRequest(
        @NotBlank String projectId,
        @NotBlank String storyIntent,
        @Positive Long targetDurationMinutes,
        @NotEmpty List<@Valid RawVideoClipAnalysis> clips) {
}
