package com.youtube.analytics.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request to compare a channel-owned video and a public competitor video. */
public record TranscriptComparisonRequest(
        @NotBlank(message = "myVideoId is required") String myVideoId,
        @NotBlank(message = "competitorVideoId is required") String competitorVideoId,
        @Size(max = 60000, message = "myTranscript must be at most 60,000 characters") String myTranscript,
        @Size(max = 60000, message = "competitorTranscript must be at most 60,000 characters") String competitorTranscript,
        boolean analyze,
        Integer lastNDays) {
}
