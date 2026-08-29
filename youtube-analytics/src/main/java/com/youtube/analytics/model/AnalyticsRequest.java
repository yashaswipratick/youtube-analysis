package com.youtube.analytics.model;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Data
public class AnalyticsRequest {

    @NotEmpty(message = "videoIds must not be empty")
    private List<@NotBlank(message = "videoIds must not contain blank values") String> videoIds;

    @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "startDate must be in yyyy-MM-dd format")
    private String startDate;

    @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "endDate must be in yyyy-MM-dd format")
    private String endDate;

    private List<String> metrics;

    private String dimensions;
}
