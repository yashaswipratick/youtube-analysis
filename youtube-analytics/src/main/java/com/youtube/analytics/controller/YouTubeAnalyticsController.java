package com.youtube.analytics.controller;

import com.youtube.analytics.model.AiAnalysisRequest;
import com.youtube.analytics.model.AiAnalysisResult;
import com.youtube.analytics.model.AnalyticsRequest;
import com.youtube.analytics.model.ApiResponse;
import com.youtube.analytics.model.ChannelAnalyticsResult;
import com.youtube.analytics.model.ChannelGeographyAnalyticsResult;
import com.youtube.analytics.model.DailyVideoAnalyticsResult;
import com.youtube.analytics.model.DiscoveryOptimizationResult;
import com.youtube.analytics.model.GeographyAnalyticsResult;
import com.youtube.analytics.model.RecommendationRequest;
import com.youtube.analytics.model.RecommendationResult;
import com.youtube.analytics.model.TrafficSourceAnalyticsResult;
import com.youtube.analytics.model.VideoAnalyticsResult;
import com.youtube.analytics.model.VideoRetentionAnalyticsResult;
import com.youtube.analytics.service.AnalyticsRequestValidator;
import com.youtube.analytics.service.DiscoveryOptimizationService;
import com.youtube.analytics.service.OpenAiAnalysisService;
import com.youtube.analytics.service.RecommendationEngineService;
import com.youtube.analytics.service.YouTubeAnalyticsService;
import com.youtube.analytics.service.YouTubeChannelGeographyAnalyticsService;
import com.youtube.analytics.service.YouTubeGeographyAnalyticsService;
import com.youtube.analytics.service.YouTubeTrafficSourceAnalyticsService;
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

/** REST controller exposing YouTube Studio Analytics. */
@Slf4j
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/youtube/analytics")
public class YouTubeAnalyticsController {

    private final YouTubeAnalyticsService analyticsService;
    private final YouTubeTrafficSourceAnalyticsService trafficSourceAnalyticsService;
    private final YouTubeGeographyAnalyticsService geographyAnalyticsService;
    private final YouTubeChannelGeographyAnalyticsService channelGeographyAnalyticsService;
    private final OpenAiAnalysisService openAiAnalysisService;
    private final RecommendationEngineService recommendationEngineService;
    private final DiscoveryOptimizationService discoveryOptimizationService;

    @PostMapping("/ai/analyze")
    public ResponseEntity<ApiResponse<AiAnalysisResult>> analyzeWithAi(@Valid @RequestBody AiAnalysisRequest request) {
        log.info("POST /ai/analyze | contextPresent={}", request.context() != null && !request.context().isEmpty());
        return ResponseEntity.ok(ApiResponse.success(openAiAnalysisService.analyze(request)));
    }

    @PostMapping("/recommendations")
    public ResponseEntity<ApiResponse<RecommendationResult>> getRecommendations(@Valid @RequestBody RecommendationRequest request) {
        AnalyticsRequestValidator.validateVideoId(request.videoId());
        AnalyticsRequestValidator.validateProvidedDates(request.startDate(), request.endDate());
        log.info("POST /recommendations | videoId={} | {} → {}", request.videoId(), request.startDate(), request.endDate());
        return ResponseEntity.ok(ApiResponse.success(recommendationEngineService.recommend(request.videoId(), request.startDate(), request.endDate())));
    }

    @GetMapping("/channel")
    public ResponseEntity<ApiResponse<ChannelAnalyticsResult>> getChannelAnalytics(
            @RequestParam(required = false) @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "startDate must be yyyy-MM-dd") String startDate,
            @RequestParam(required = false) @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "endDate must be yyyy-MM-dd") String endDate,
            @RequestParam(required = false) List<String> metrics) {
        AnalyticsRequestValidator.validateProvidedDates(startDate, endDate);
        log.info("GET /channel | {} → {}", startDate, endDate);
        return ResponseEntity.ok(ApiResponse.success(analyticsService.getChannelAnalytics(startDate, endDate, metrics)));
    }

    @GetMapping("/channel/geography")
    public ResponseEntity<ApiResponse<ChannelGeographyAnalyticsResult>> getChannelGeographyAnalytics(
            @RequestParam(required = false) @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "startDate must be yyyy-MM-dd") String startDate,
            @RequestParam(required = false) @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "endDate must be yyyy-MM-dd") String endDate,
            @RequestParam(required = false) List<String> metrics) {
        AnalyticsRequestValidator.validateProvidedDates(startDate, endDate);
        log.info("GET /channel/geography | {} → {}", startDate, endDate);
        return ResponseEntity.ok(ApiResponse.success(channelGeographyAnalyticsService.getChannelGeographyAnalytics(startDate, endDate, metrics)));
    }

    @GetMapping("/video/{videoId}")
    public ResponseEntity<ApiResponse<VideoAnalyticsResult>> getSingleVideoAnalytics(
            @PathVariable String videoId,
            @RequestParam(required = false) @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "startDate must be yyyy-MM-dd") String startDate,
            @RequestParam(required = false) @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "endDate must be yyyy-MM-dd") String endDate,
            @RequestParam(required = false) List<String> metrics) {
        AnalyticsRequestValidator.validateVideoId(videoId);
        AnalyticsRequestValidator.validateProvidedDates(startDate, endDate);
        log.info("GET /video/{} | {} → {}", videoId, startDate, endDate);
        return ResponseEntity.ok(ApiResponse.success(analyticsService.getSingleVideoAnalytics(videoId, startDate, endDate, metrics)));
    }

    @GetMapping("/video/{videoId}/retention")
    public ResponseEntity<ApiResponse<VideoRetentionAnalyticsResult>> getVideoRetentionAnalytics(
            @PathVariable String videoId,
            @RequestParam(required = false) @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "startDate must be yyyy-MM-dd") String startDate,
            @RequestParam(required = false) @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "endDate must be yyyy-MM-dd") String endDate) {
        AnalyticsRequestValidator.validateVideoId(videoId);
        AnalyticsRequestValidator.validateProvidedDates(startDate, endDate);
        log.info("GET /video/{}/retention | {} → {}", videoId, startDate, endDate);
        return ResponseEntity.ok(ApiResponse.success(analyticsService.getVideoRetentionAnalytics(videoId, startDate, endDate)));
    }

    @GetMapping("/video/{videoId}/retention/analysis")
    public ResponseEntity<ApiResponse<com.youtube.analytics.model.RetentionAnalysisResult>> getVideoRetentionAnalysis(
            @PathVariable String videoId,
            @RequestParam(required = false) @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "startDate must be yyyy-MM-dd") String startDate,
            @RequestParam(required = false) @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "endDate must be yyyy-MM-dd") String endDate) {
        AnalyticsRequestValidator.validateVideoId(videoId);
        AnalyticsRequestValidator.validateProvidedDates(startDate, endDate);
        log.info("GET /video/{}/retention/analysis | {} → {}", videoId, startDate, endDate);
        return ResponseEntity.ok(ApiResponse.success(analyticsService.getVideoRetentionAnalysis(videoId, startDate, endDate)));
    }

    @GetMapping("/video/{videoId}/discovery")
    public ResponseEntity<ApiResponse<DiscoveryOptimizationResult>> getDiscoveryOptimization(
            @PathVariable String videoId,
            @RequestParam(required = false) @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "startDate must be yyyy-MM-dd") String startDate,
            @RequestParam(required = false) @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "endDate must be yyyy-MM-dd") String endDate) {
        AnalyticsRequestValidator.validateVideoId(videoId);
        AnalyticsRequestValidator.validateProvidedDates(startDate, endDate);
        log.info("GET /video/{}/discovery | {} → {}", videoId, startDate, endDate);
        return ResponseEntity.ok(ApiResponse.success(discoveryOptimizationService.analyze(videoId, startDate, endDate)));
    }

    @GetMapping("/video/{videoId}/daily")
    public ResponseEntity<ApiResponse<DailyVideoAnalyticsResult>> getDailyVideoAnalytics(
            @PathVariable String videoId,
            @RequestParam(required = false) @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "startDate must be yyyy-MM-dd") String startDate,
            @RequestParam(required = false) @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "endDate must be yyyy-MM-dd") String endDate,
            @RequestParam(required = false) List<String> metrics) {
        AnalyticsRequestValidator.validateVideoId(videoId);
        AnalyticsRequestValidator.validateProvidedDates(startDate, endDate);
        log.info("GET /video/{}/daily | {} → {}", videoId, startDate, endDate);
        return ResponseEntity.ok(ApiResponse.success(analyticsService.getDailyVideoAnalytics(videoId, startDate, endDate, metrics)));
    }

    @GetMapping("/video/{videoId}/traffic-sources")
    public ResponseEntity<ApiResponse<TrafficSourceAnalyticsResult>> getVideoTrafficSources(
            @PathVariable String videoId,
            @RequestParam(required = false) @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "startDate must be yyyy-MM-dd") String startDate,
            @RequestParam(required = false) @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "endDate must be yyyy-MM-dd") String endDate,
            @RequestParam(required = false) List<String> metrics) {
        AnalyticsRequestValidator.validateVideoId(videoId);
        AnalyticsRequestValidator.validateProvidedDates(startDate, endDate);
        log.info("GET /video/{}/traffic-sources | {} → {}", videoId, startDate, endDate);
        return ResponseEntity.ok(ApiResponse.success(trafficSourceAnalyticsService.getVideoTrafficSources(videoId, startDate, endDate, metrics)));
    }

    @GetMapping("/video/{videoId}/geography")
    public ResponseEntity<ApiResponse<GeographyAnalyticsResult>> getVideoGeography(
            @PathVariable String videoId,
            @RequestParam(required = false) @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "startDate must be yyyy-MM-dd") String startDate,
            @RequestParam(required = false) @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "endDate must be yyyy-MM-dd") String endDate,
            @RequestParam(required = false) List<String> metrics) {
        AnalyticsRequestValidator.validateVideoId(videoId);
        AnalyticsRequestValidator.validateProvidedDates(startDate, endDate);
        log.info("GET /video/{}/geography | {} → {}", videoId, startDate, endDate);
        return ResponseEntity.ok(ApiResponse.success(geographyAnalyticsService.getVideoGeography(videoId, startDate, endDate, metrics)));
    }

    @PostMapping("/videos")
    public ResponseEntity<ApiResponse<List<VideoAnalyticsResult>>> getMultipleVideoAnalytics(@Valid @RequestBody AnalyticsRequest request) {
        request.getVideoIds().forEach(AnalyticsRequestValidator::validateVideoId);
        AnalyticsRequestValidator.validateProvidedDates(request.getStartDate(), request.getEndDate());
        log.info("POST /videos | count={} | {} → {}", request.getVideoIds().size(), request.getStartDate(), request.getEndDate());
        return ResponseEntity.ok(ApiResponse.success(analyticsService.getMultipleVideoAnalytics(request.getVideoIds(), request.getStartDate(), request.getEndDate(), request.getMetrics())));
    }

    @GetMapping("/videos/all")
    public ResponseEntity<ApiResponse<List<VideoAnalyticsResult>>> getAllVideosAnalytics(
            @RequestParam(required = false) @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "startDate must be yyyy-MM-dd") String startDate,
            @RequestParam(required = false) @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "endDate must be yyyy-MM-dd") String endDate,
            @RequestParam(required = false) List<String> metrics) {
        AnalyticsRequestValidator.validateProvidedDates(startDate, endDate);
        log.info("GET /videos/all | {} → {}", startDate, endDate);
        return ResponseEntity.ok(ApiResponse.success(analyticsService.getAllVideosAnalytics(startDate, endDate, metrics)));
    }
}
