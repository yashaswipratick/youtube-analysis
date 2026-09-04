package com.youtube.analytics.videoanalysis.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record RawVideoAnalysisRequest(
        @NotBlank String projectId,
        @NotBlank String storyIntent,
        @NotEmpty List<@Valid RawVideoClipAnalysis> clips) {
}
