package com.youtube.analytics.controller;

import com.youtube.analytics.model.AnalyticsRequest;
import com.youtube.analytics.model.ApiResponse;
import com.youtube.analytics.model.VideoAnalyticsResult;
import com.youtube.analytics.service.YouTubeAnalyticsService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST controller exposing YouTube Studio Analytics.
 *
 * Endpoints:
 *   GET  /api/youtube/analytics/video/{videoId}   — single video analytics
 *   POST /api/youtube/analytics/videos            — analytics for a list of videos
 *   GET  /api/youtube/analytics/videos/all        — analytics for ALL uploaded videos
 */
@Slf4j
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/youtube/analytics")
public class YouTubeAnalyticsController {

    private final YouTubeAnalyticsService analyticsService;

    /**
     * Fetch analytics for a single YouTube video.
     *
     * @param videoId   The YouTube video ID (e.g. dQw4w9WgXcQ)
     * @param startDate Start date yyyy-MM-dd (optional, defaults to 365 days ago)
     * @param endDate   End date yyyy-MM-dd (optional, defaults to today)
     * @param metrics   Comma-separated list of metrics (optional)
     */
    @GetMapping("/video/{videoId}")
    public ResponseEntity<ApiResponse<VideoAnalyticsResult>> getSingleVideoAnalytics(
            @PathVariable String videoId,
            @RequestParam(required = false)
            @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "startDate must be yyyy-MM-dd")
            String startDate,
            @RequestParam(required = false)
            @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "endDate must be yyyy-MM-dd")
            String endDate,
            @RequestParam(required = false) List<String> metrics) {

        log.info("GET /video/{} | {} → {}", videoId, startDate, endDate);

        VideoAnalyticsResult result = analyticsService.getSingleVideoAnalytics(
                videoId, startDate, endDate, metrics);

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /**
     * Fetch analytics for a given list of video IDs.
     *
     * Request body:
     * {
     *   "videoIds":  ["id1", "id2", ...],
     *   "startDate": "2024-01-01",
     *   "endDate":   "2024-12-31",
     *   "metrics":   ["views", "likes"]
     * }
     */
    @PostMapping("/videos")
    public ResponseEntity<ApiResponse<List<VideoAnalyticsResult>>> getMultipleVideoAnalytics(
            @Valid @RequestBody AnalyticsRequest request) {

        log.info("POST /videos | count={} | {} → {}",
                request.getVideoIds().size(), request.getStartDate(), request.getEndDate());

        List<VideoAnalyticsResult> results = analyticsService.getMultipleVideoAnalytics(
                request.getVideoIds(),
                request.getStartDate(),
                request.getEndDate(),
                request.getMetrics());

        return ResponseEntity.ok(ApiResponse.success(results));
    }

    /**
     * Fetch analytics for ALL videos ever uploaded to the authenticated channel.
     *
     * @param startDate Start date yyyy-MM-dd (optional, defaults to 365 days ago)
     * @param endDate   End date yyyy-MM-dd (optional, defaults to today)
     * @param metrics   Comma-separated list of metrics (optional)
     */
    @GetMapping("/videos/all")
    public ResponseEntity<ApiResponse<List<VideoAnalyticsResult>>> getAllVideosAnalytics(
            @RequestParam(required = false)
            @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "startDate must be yyyy-MM-dd")
            String startDate,
            @RequestParam(required = false)
            @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "endDate must be yyyy-MM-dd")
            String endDate,
            @RequestParam(required = false) List<String> metrics) {

        log.info("GET /videos/all | {} → {}", startDate, endDate);

        List<VideoAnalyticsResult> results = analyticsService.getAllVideosAnalytics(
                startDate, endDate, metrics);

        return ResponseEntity.ok(ApiResponse.success(results));
    }
}
