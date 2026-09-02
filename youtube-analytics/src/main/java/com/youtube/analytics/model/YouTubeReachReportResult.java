package com.youtube.analytics.model;

/** Aggregated YouTube Reporting API reach metrics for a video and date range. */
public record YouTubeReachReportResult(
        Long impressions,
        Double impressionsClickThroughRate,
        boolean available) {

    public static YouTubeReachReportResult unavailable() {
        return new YouTubeReachReportResult(null, null, false);
    }
}
