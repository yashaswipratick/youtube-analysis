package com.youtube.analytics.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** Typed representation of the JSON response from YouTube Analytics reports.query. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class YouTubeAnalyticsApiResponse {
    private List<YouTubeAnalyticsColumnHeader> columnHeaders = new ArrayList<>();
    private List<List<Object>> rows = new ArrayList<>();
}
