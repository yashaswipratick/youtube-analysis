package com.youtube.analytics.model;

/**
 * Current public statistics from YouTube Data API v3 videos.list.
 * These counts are not date-filtered; they are the current totals for the video.
 */
public record VideoPublicStatistics(
        String videoId,
        Long viewCount,
        Long likeCount,
        Long commentCount) {
}
