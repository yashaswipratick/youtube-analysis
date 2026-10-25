package com.youtube.analytics.controller;

import com.youtube.analytics.model.AiAnalysisRequest;
import com.youtube.analytics.model.AiAnalysisResult;
import com.youtube.analytics.model.AnalyticsDecisionResult;
import com.youtube.analytics.model.AnalyticsRequest;
import com.youtube.analytics.model.ApiResponse;
import com.youtube.analytics.model.ChannelAnalyticsResult;
import com.youtube.analytics.model.ChannelGeographyAnalyticsResult;
import com.youtube.analytics.model.DailyVideoAnalyticsResult;
import com.youtube.analytics.model.DiscoveryOptimizationResult;
import com.youtube.analytics.model.GeographyAnalyticsResult;
import com.youtube.analytics.model.RecommendationRequest;
import com.youtube.analytics.model.RecommendationResult;
import com.youtube.analytics.model.RetentionAnalysisResult;
import com.youtube.analytics.model.TrafficSourceAnalyticsResult;
import com.youtube.analytics.model.VideoAnalyticsResult;
import com.youtube.analytics.model.VideoRetentionAnalyticsResult;
import com.youtube.analytics.service.AnalyticsDecisionService;
import com.youtube.analytics.service.AnalyticsDateRangeResolver;
import com.youtube.analytics.service.AnalyticsDateRangeResolver.DateRange;
import com.youtube.analytics.service.AnalyticsRequestValidator;
import com.youtube.analytics.service.DiscoveryOptimizationService;
import com.youtube.analytics.service.OpenAiAnalysisService;
import com.youtube.analytics.service.RecommendationEngineService;
import com.youtube.analytics.service.RetentionAnalysisService;
import com.youtube.analytics.service.YouTubeAnalyticsService;
import com.youtube.analytics.service.YouTubeAnalyticsReportAggregationService;
import com.youtube.analytics.service.YouTubeChannelGeographyAnalyticsService;
import com.youtube.analytics.service.YouTubeGeographyAnalyticsService;
import com.youtube.analytics.service.YouTubeTrafficSourceAnalyticsService;
import com.youtube.analytics.service.YouTubeStudioReportsService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
import java.util.Map;

/** REST controller exposing YouTube Studio Analytics. */
@Slf4j
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/youtube-analytics/v1/analytics")
public class YouTubeAnalyticsController {

    private final YouTubeAnalyticsService analyticsService;
    private final YouTubeAnalyticsReportAggregationService reportAggregationService;
    private final YouTubeTrafficSourceAnalyticsService trafficSourceAnalyticsService;
    private final YouTubeGeographyAnalyticsService geographyAnalyticsService;
    private final YouTubeChannelGeographyAnalyticsService channelGeographyAnalyticsService;
    private final OpenAiAnalysisService openAiAnalysisService;
    private final RecommendationEngineService recommendationEngineService;
    private final RetentionAnalysisService retentionAnalysisService;
    private final DiscoveryOptimizationService discoveryOptimizationService;
    private final AnalyticsDecisionService analyticsDecisionService;
    private final YouTubeStudioReportsService studioReportsService;
    private final AnalyticsDateRangeResolver dateRangeResolver;

    @GetMapping("/ai/key-status")
    public ResponseEntity<Map<String, Object>> getOpenAiKeyStatus() {
        return ResponseEntity.ok(openAiAnalysisService.validateApiKey());
    }

    @PostMapping("/ai/analyze")
    public ResponseEntity<ApiResponse<AiAnalysisResult>> analyzeWithAi(@Valid @RequestBody AiAnalysisRequest request) {
        log.info("POST /ai/analyze | contextPresent={}", request.context() != null && !request.context().isEmpty());
        return ResponseEntity.ok(ApiResponse.success(openAiAnalysisService.analyze(request)));
    }

    @PostMapping("/recommendations")
    public ResponseEntity<ApiResponse<RecommendationResult>> getRecommendations(@Valid @RequestBody RecommendationRequest request) {
        AnalyticsRequestValidator.validateVideoId(request.videoId());
        DateRange range = dateRangeResolver.resolveForVideo(request.videoId(), request.lastNDays());
        log.info("POST /recommendations | videoId={} | {} → {}", request.videoId(), range.startDate(), range.endDate());
        return ResponseEntity.ok(ApiResponse.success(recommendationEngineService.recommend(
                request.videoId(), range.startDateText(), range.endDateText())));
    }

    @GetMapping("/channel")
    public ResponseEntity<ApiResponse<ChannelAnalyticsResult>> getChannelAnalytics(
            @RequestParam(required = false) @Min(1) @Max(3650) Integer lastNDays,
            @RequestParam(required = false) List<String> metrics) {
        DateRange range = dateRangeResolver.resolve(lastNDays);
        log.info("GET /channel | {} → {}", range.startDate(), range.endDate());
        return ResponseEntity.ok(ApiResponse.success(analyticsService.getChannelAnalytics(
                range.startDateText(), range.endDateText(), metrics)));
    }

    @GetMapping("/channel/geography")
    public ResponseEntity<ApiResponse<ChannelGeographyAnalyticsResult>> getChannelGeographyAnalytics(
            @RequestParam(required = false) @Min(1) @Max(3650) Integer lastNDays,
            @RequestParam(required = false) List<String> metrics) {
        DateRange range = dateRangeResolver.resolve(lastNDays);
        return ResponseEntity.ok(ApiResponse.success(channelGeographyAnalyticsService.getChannelGeographyAnalytics(
                range.startDateText(), range.endDateText(), metrics)));
    }

    @GetMapping("/video/{videoId}")
    public ResponseEntity<ApiResponse<VideoAnalyticsResult>> getSingleVideoAnalytics(
            @PathVariable String videoId,
            @RequestParam(required = false) @Min(1) @Max(3650) Integer lastNDays,
            @RequestParam(required = false) List<String> metrics,
            @RequestParam(required = false) List<String> includeSections) {
        DateRange range = dateRangeResolver.resolveForVideo(videoId, lastNDays);
        log.info("GET /video/{} | {} → {}", videoId, range.startDate(), range.endDate());
        VideoAnalyticsResult result = analyticsService.getSingleVideoAnalytics(
                videoId, range.startDateText(), range.endDateText(), metrics);
        return ResponseEntity.ok(ApiResponse.success(reportAggregationService.enrich(result,
                includeSections == null ? null : new java.util.LinkedHashSet<>(includeSections))));
    }

    @GetMapping("/video/{videoId}/reports/replay/{snapshotId}")
    public ResponseEntity<ApiResponse<VideoAnalyticsResult>> replayVideoAnalyticsReports(
            @PathVariable String videoId,
            @PathVariable String snapshotId) {
        return ResponseEntity.ok(ApiResponse.success(reportAggregationService.replay(videoId, snapshotId)));
    }

    @GetMapping("/video/{videoId}/retention")
    public ResponseEntity<ApiResponse<VideoRetentionAnalyticsResult>> getVideoRetentionAnalytics(
            @PathVariable String videoId,
            @RequestParam(required = false) @Min(1) @Max(3650) Integer lastNDays) {
        DateRange range = dateRangeResolver.resolveForVideo(videoId, lastNDays);
        log.info("GET /video/{}/retention | {} → {}", videoId, range.startDate(), range.endDate());
        return ResponseEntity.ok(ApiResponse.success(analyticsService.getVideoRetentionAnalytics(
                videoId, range.startDateText(), range.endDateText())));
    }

    @GetMapping("/video/{videoId}/retention/analysis")
    public ResponseEntity<ApiResponse<RetentionAnalysisResult>> getVideoRetentionAnalysis(
            @PathVariable String videoId,
            @RequestParam(required = false) @Min(1) @Max(3650) Integer lastNDays) {
        DateRange range = dateRangeResolver.resolveForVideo(videoId, lastNDays);
        log.info("GET /video/{}/retention/analysis | {} → {}", videoId, range.startDate(), range.endDate());
        VideoRetentionAnalyticsResult retention = analyticsService.getVideoRetentionAnalytics(
                videoId, range.startDateText(), range.endDateText());
        return ResponseEntity.ok(ApiResponse.success(retentionAnalysisService.analyze(retention)));
    }

    @GetMapping("/video/{videoId}/discovery")
    public ResponseEntity<ApiResponse<DiscoveryOptimizationResult>> getDiscoveryOptimization(
            @PathVariable String videoId,
            @RequestParam(required = false) @Min(1) @Max(3650) Integer lastNDays) {
        DateRange range = dateRangeResolver.resolveForVideo(videoId, lastNDays);
        log.info("GET /video/{}/discovery | {} → {}", videoId, range.startDate(), range.endDate());
        return ResponseEntity.ok(ApiResponse.success(discoveryOptimizationService.analyze(
                videoId, range.startDateText(), range.endDateText())));
    }

    @GetMapping("/video/{videoId}/decision")
    public ResponseEntity<ApiResponse<AnalyticsDecisionResult>> getAnalyticsDecision(
            @PathVariable String videoId,
            @RequestParam(required = false) @Min(1) @Max(3650) Integer lastNDays) {
        DateRange range = dateRangeResolver.resolveForVideo(videoId, lastNDays);
        log.info("GET /video/{}/decision | {} → {}", videoId, range.startDate(), range.endDate());
        DiscoveryOptimizationResult discovery = discoveryOptimizationService.analyze(
                videoId, range.startDateText(), range.endDateText());
        return ResponseEntity.ok(ApiResponse.success(analyticsDecisionService.decide(discovery)));
    }

    @GetMapping("/video/{videoId}/daily")
    public ResponseEntity<ApiResponse<DailyVideoAnalyticsResult>> getDailyVideoAnalytics(
            @PathVariable String videoId,
            @RequestParam(required = false) @Min(1) @Max(3650) Integer lastNDays,
            @RequestParam(required = false) List<String> metrics) {
        DateRange range = dateRangeResolver.resolveForVideo(videoId, lastNDays);
        log.info("GET /video/{}/daily | {} → {}", videoId, range.startDate(), range.endDate());
        return ResponseEntity.ok(ApiResponse.success(analyticsService.getDailyVideoAnalytics(
                videoId, range.startDateText(), range.endDateText(), metrics)));
    }

    @GetMapping("/video/{videoId}/traffic-sources")
    public ResponseEntity<ApiResponse<TrafficSourceAnalyticsResult>> getVideoTrafficSources(
            @PathVariable String videoId,
            @RequestParam(required = false) @Min(1) @Max(3650) Integer lastNDays,
            @RequestParam(required = false) List<String> metrics) {
        DateRange range = dateRangeResolver.resolveForVideo(videoId, lastNDays);
        log.info("GET /video/{}/traffic-sources | {} → {}", videoId, range.startDate(), range.endDate());
        return ResponseEntity.ok(ApiResponse.success(trafficSourceAnalyticsService.getVideoTrafficSources(
                videoId, range.startDateText(), range.endDateText(), metrics)));
    }

    @GetMapping("/video/{videoId}/geography")
    public ResponseEntity<ApiResponse<GeographyAnalyticsResult>> getVideoGeography(
            @PathVariable String videoId,
            @RequestParam(required = false) @Min(1) @Max(3650) Integer lastNDays,
            @RequestParam(required = false) List<String> metrics) {
        DateRange range = dateRangeResolver.resolveForVideo(videoId, lastNDays);
        log.info("GET /video/{}/geography | {} → {}", videoId, range.startDate(), range.endDate());
        return ResponseEntity.ok(ApiResponse.success(geographyAnalyticsService.getVideoGeography(
                videoId, range.startDateText(), range.endDateText(), metrics)));
    }

    @PostMapping("/videos")
    public ResponseEntity<ApiResponse<List<VideoAnalyticsResult>>> getMultipleVideoAnalytics(@Valid @RequestBody AnalyticsRequest request) {
        request.getVideoIds().forEach(AnalyticsRequestValidator::validateVideoId);
        DateRange range = dateRangeResolver.resolve(request.getLastNDays());
        log.info("POST /videos | count={} | {} → {}", request.getVideoIds().size(), range.startDate(), range.endDate());
        return ResponseEntity.ok(ApiResponse.success(analyticsService.getMultipleVideoAnalytics(
                request.getVideoIds(), range.startDateText(), range.endDateText(), request.getMetrics())));
    }

    @GetMapping("/videos/all")
    public ResponseEntity<ApiResponse<List<VideoAnalyticsResult>>> getAllVideosAnalytics(
            @RequestParam(required = false) @Min(1) @Max(3650) Integer lastNDays,
            @RequestParam(required = false) List<String> metrics) {
        DateRange range = dateRangeResolver.resolve(lastNDays);
        log.info("GET /videos/all | {} → {}", range.startDate(), range.endDate());
        return ResponseEntity.ok(ApiResponse.success(analyticsService.getAllVideosAnalytics(
                range.startDateText(), range.endDateText(), metrics)));
    }

    @GetMapping("/reports/overview")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getStudioOverview(
            @RequestParam(required = false) @Min(1) @Max(3650) Integer lastNDays) {
        DateRange range = dateRangeResolver.resolve(lastNDays);
        return ResponseEntity.ok(ApiResponse.success(studioReportsService.overview(
                range.startDateText(), range.endDateText())));
    }

    @GetMapping("/reports/videos/top")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getTopVideoReport(
            @RequestParam(required = false) @Min(1) @Max(3650) Integer lastNDays,
            @RequestParam(defaultValue = "15") int limit,
            @RequestParam(defaultValue = "views") String sortBy) {
        DateRange range = dateRangeResolver.resolve(lastNDays);
        return ResponseEntity.ok(ApiResponse.success(studioReportsService.topVideos(
                range.startDateText(), range.endDateText(), limit, sortBy)));
    }

    @GetMapping("/reports/videos/compare")
    public ResponseEntity<ApiResponse<Map<String, Object>>> compareStudioVideos(
            @RequestParam List<String> videoIds,
            @RequestParam(required = false) @Min(1) @Max(3650) Integer lastNDays,
            @RequestParam(required = false) List<String> metrics) {
        DateRange range = dateRangeResolver.resolve(lastNDays);
        return ResponseEntity.ok(ApiResponse.success(studioReportsService.compareVideos(
                range.startDateText(), range.endDateText(), videoIds, metrics)));
    }

    @GetMapping("/reports/audience")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getStudioAudienceReports(
            @RequestParam(required = false) @Min(1) @Max(3650) Integer lastNDays,
            @RequestParam(required = false) String videoId) {
        DateRange range = videoId == null || videoId.isBlank()
                ? dateRangeResolver.resolve(lastNDays)
                : dateRangeResolver.resolveForVideo(videoId, lastNDays);
        return ResponseEntity.ok(ApiResponse.success(studioReportsService.audience(
                range.startDateText(), range.endDateText(), videoId)));
    }

    @GetMapping("/reports/uploads-frequency")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getUploadFrequencyReport(
            @RequestParam(required = false) @Min(1) @Max(3650) Integer lastNDays) {
        DateRange range = dateRangeResolver.resolve(lastNDays);
        return ResponseEntity.ok(ApiResponse.success(studioReportsService.uploadFrequency(
                range.startDateText(), range.endDateText())));
    }

    @GetMapping("/reports/playlists/{playlistId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getPlaylistAnalyticsReport(
            @PathVariable String playlistId,
            @RequestParam(required = false) @Min(1) @Max(3650) Integer lastNDays) {
        DateRange range = dateRangeResolver.resolve(lastNDays);
        return ResponseEntity.ok(ApiResponse.success(studioReportsService.playlistAnalytics(
                range.startDateText(), range.endDateText(), playlistId)));
    }

    @GetMapping("/reports/formats")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getFormatBreakdown(
            @RequestParam(required = false) @Min(1) @Max(3650) Integer lastNDays) {
        DateRange range = dateRangeResolver.resolve(lastNDays);
        return ResponseEntity.ok(ApiResponse.success(studioReportsService.formatBreakdown(
                range.startDateText(), range.endDateText())));
    }

    @GetMapping("/reports/traffic-sources")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getChannelTrafficSources(
            @RequestParam(required = false) @Min(1) @Max(3650) Integer lastNDays,
            @RequestParam(required = false) String videoId) {
        DateRange range = videoId == null || videoId.isBlank()
                ? dateRangeResolver.resolve(lastNDays)
                : dateRangeResolver.resolveForVideo(videoId, lastNDays);
        return ResponseEntity.ok(ApiResponse.success(studioReportsService.trafficSources(
                range.startDateText(), range.endDateText(), videoId)));
    }

    @GetMapping("/reports/search-terms")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getSearchTerms(
            @RequestParam(required = false) @Min(1) @Max(3650) Integer lastNDays,
            @RequestParam(defaultValue = "50") int limit) {
        DateRange range = dateRangeResolver.resolve(lastNDays);
        return ResponseEntity.ok(ApiResponse.success(studioReportsService.searchTerms(
                range.startDateText(), range.endDateText(), limit)));
    }

    @GetMapping("/reports/revenue")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getRevenueReport(
            @RequestParam(required = false) @Min(1) @Max(3650) Integer lastNDays,
            @RequestParam(required = false) String videoId,
            @RequestParam(defaultValue = "15") int limit) {
        DateRange range = videoId == null || videoId.isBlank()
                ? dateRangeResolver.resolve(lastNDays)
                : dateRangeResolver.resolveForVideo(videoId, lastNDays);
        return ResponseEntity.ok(ApiResponse.success(studioReportsService.revenue(
                range.startDateText(), range.endDateText(), videoId, limit)));
    }

    @GetMapping("/reports/videos/benchmarks")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getRecentVideoBenchmarks(
            @RequestParam(defaultValue = "15") int limit) {
        return ResponseEntity.ok(ApiResponse.success(studioReportsService.recentVideoBenchmarks(limit)));
    }

    @GetMapping("/reports/subtitles")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getSubtitleLanguageReport(
            @RequestParam(required = false) @Min(1) @Max(3650) Integer lastNDays) {
        DateRange range = dateRangeResolver.resolve(lastNDays);
        return ResponseEntity.ok(ApiResponse.success(studioReportsService.subtitleLanguages(
                range.startDateText(), range.endDateText())));
    }

    @GetMapping("/reports/capabilities")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getStudioReportCapabilities() {
        return ResponseEntity.ok(ApiResponse.success(studioReportsService.capabilities()));
    }

    @GetMapping("/video/{videoId}/reach")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getVideoReachMetrics(
            @PathVariable String videoId,
            @RequestParam(required = false) @Min(1) @Max(3650) Integer lastNDays) {
        DateRange range = dateRangeResolver.resolveForVideo(videoId, lastNDays);
        return ResponseEntity.ok(ApiResponse.success(studioReportsService.videoReach(
                videoId, range.startDateText(), range.endDateText())));
    }
}
