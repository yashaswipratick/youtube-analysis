package com.youtube.analytics.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request for a single owned-video storytelling review against the creator's own history. */
public record CreatorVideoAnalysisRequest(
        @NotBlank(message = "videoId is required") String videoId,
        @Size(max = 60000, message = "transcript must be at most 60,000 characters") String transcript,
        @Size(max = 1000, message = "thumbnailDescription must be at most 1,000 characters") String thumbnailDescription,
        @Size(max = 3000, message = "creatorNotes must be at most 3,000 characters") String creatorNotes,
        @Size(max = 12, message = "outputLanguage must be en, hi or hinglish") String outputLanguage,
        Integer lastNDays) {
}
