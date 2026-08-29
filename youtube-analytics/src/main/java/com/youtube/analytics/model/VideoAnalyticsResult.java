package com.youtube.analytics.model;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class VideoAnalyticsResult {

    private String videoId;
    private String title;
    private String publishedAt;
    private String startDate;
    private String endDate;
    private Map<String, Object> metrics;
}
