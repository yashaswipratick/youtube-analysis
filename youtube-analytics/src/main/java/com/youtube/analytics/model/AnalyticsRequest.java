package com.youtube.analytics.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/** Request for analytics across a selected set of video IDs. */
@Data
public class AnalyticsRequest {

    @NotEmpty(message = "videoIds must not be empty")
    private List<@NotBlank(message = "videoIds must not contain blank values") String> videoIds;

    /** Number of recent YouTube reporting days to retrieve; defaults to 28 when omitted. */
    @Min(value = 1, message = "lastNDays must be at least 1")
    @Max(value = 3650, message = "lastNDays must not exceed 3650")
    private Integer lastNDays;

    private List<String> metrics;

    private String dimensions;
}
