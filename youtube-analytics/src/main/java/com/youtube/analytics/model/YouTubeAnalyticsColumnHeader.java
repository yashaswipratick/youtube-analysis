package com.youtube.analytics.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/** A column definition returned by YouTube Analytics reports.query. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class YouTubeAnalyticsColumnHeader {
    private String name;
    private String columnType;
    private String dataType;
}
