package com.youtube.analytics.controller;

import com.youtube.analytics.model.ApiResponse;
import com.youtube.analytics.model.RetentionAnalysisResult;
import com.youtube.analytics.model.VideoRetentionAnalyticsResult;
import com.youtube.analytics.service.AnalyticsRequestValidator;
import com.youtube.analytics.service.RetentionAnalysisService;
import com.youtube.analytics.service.YouTubeAnalyticsService;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** REST endpoint for dedicated 7H audience-retention analysis. */
@Slf4j
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/youtube/analytics/video")
public class YouTubeRetentionAnalysisController {

    private final YouTubeAnalyticsService analyticsService;
    private final RetentionAnalysisService retentionAnalysisService;

    @GetMapping("/{videoId}/retention/analysis")
    public ResponseEntity<ApiResponse<RetentionAnalysisResult>> analyzeRetention(
            @PathVariable String videoId,
            @RequestParam(required = false)
            @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "startDate must be yyyy-MM-dd")
            String startDate,
            @RequestParam(required = false)
            @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "endDate must be yyyy-MM-dd")
            String endDate) {

        AnalyticsRequestValidator.validateVideoId(videoId);
        AnalyticsRequestValidator.validateProvidedDates(startDate, endDate);
        log.info("GET /video/{}/retention/analysis | {} → {}", videoId, startDate, endDate);

        VideoRetentionAnalyticsResult retention = analyticsService.getVideoRetentionAnalytics(
                videoId, startDate, endDate);

        return ResponseEntity.ok(ApiResponse.success(retentionAnalysisService.analyze(retention)));
    }
}
