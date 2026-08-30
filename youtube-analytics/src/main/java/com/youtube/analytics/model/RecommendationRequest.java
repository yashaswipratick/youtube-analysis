package com.youtube.analytics.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Request for generating evidence-based recommendations for a video. */
public record RecommendationRequest(
        @NotBlank(message = "videoId is required")
        String videoId,
        @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "startDate must be yyyy-MM-dd")
        String startDate,
        @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "endDate must be yyyy-MM-dd")
        String endDate) {
}
