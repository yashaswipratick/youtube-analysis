package com.youtube.analytics.controller;

import com.youtube.analytics.exception.GlobalExceptionHandler;
import com.youtube.analytics.model.AnalyticsDecisionResult;
import com.youtube.analytics.model.DiscoveryOptimizationResult;
import com.youtube.analytics.model.VideoAnalyticsResult;
import com.youtube.analytics.service.AnalyticsDateRangeResolver;
import com.youtube.analytics.service.AnalyticsDecisionService;
import com.youtube.analytics.service.DiscoveryOptimizationService;
import com.youtube.analytics.service.OpenAiAnalysisService;
import com.youtube.analytics.service.RecommendationEngineService;
import com.youtube.analytics.service.RetentionAnalysisService;
import com.youtube.analytics.service.YouTubeAnalyticsService;
import com.youtube.analytics.service.YouTubeChannelGeographyAnalyticsService;
import com.youtube.analytics.service.YouTubeGeographyAnalyticsService;
import com.youtube.analytics.service.YouTubeTrafficSourceAnalyticsService;
import com.youtube.analytics.service.YouTubeStudioReportsService;
import com.youtube.analytics.service.YouTubePublicApiService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class YouTubeAnalyticsControllerTest {
    private MockMvc mockMvc;
    private YouTubeAnalyticsService analyticsService;
    private DiscoveryOptimizationService discoveryOptimizationService;
    private AnalyticsDecisionService analyticsDecisionService;
    private YouTubeStudioReportsService studioReportsService;
    private YouTubePublicApiService youtubePublicApiService;

    @BeforeEach
    void setUp() {
        analyticsService = mock(YouTubeAnalyticsService.class);
        YouTubeTrafficSourceAnalyticsService trafficSourceService = mock(YouTubeTrafficSourceAnalyticsService.class);
        YouTubeGeographyAnalyticsService geographyService = mock(YouTubeGeographyAnalyticsService.class);
        YouTubeChannelGeographyAnalyticsService channelGeographyService = mock(YouTubeChannelGeographyAnalyticsService.class);
        OpenAiAnalysisService openAiAnalysisService = mock(OpenAiAnalysisService.class);
        RecommendationEngineService recommendationEngineService = mock(RecommendationEngineService.class);
        RetentionAnalysisService retentionAnalysisService = mock(RetentionAnalysisService.class);
        discoveryOptimizationService = mock(DiscoveryOptimizationService.class);
        analyticsDecisionService = mock(AnalyticsDecisionService.class);
        studioReportsService = mock(YouTubeStudioReportsService.class);
        youtubePublicApiService = mock(YouTubePublicApiService.class);
        AnalyticsDateRangeResolver dateRangeResolver = new AnalyticsDateRangeResolver(youtubePublicApiService);

        when(analyticsService.getSingleVideoAnalytics(any(), any(), any(), any())).thenReturn(VideoAnalyticsResult.builder()
                .videoId("laQbWAoa3NI")
                .title("Weekend Trip")
                .startDate("2026-07-27")
                .endDate("2026-08-29")
                .metrics(Map.of("views", 57))
                .build());

        mockMvc = MockMvcBuilders.standaloneSetup(new YouTubeAnalyticsController(
                        analyticsService,
                        trafficSourceService,
                        geographyService,
                        channelGeographyService,
                        openAiAnalysisService,
                        recommendationEngineService,
                        retentionAnalysisService,
                        discoveryOptimizationService,
                        analyticsDecisionService,
                        studioReportsService,
                        dateRangeResolver))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void acceptsLastNDaysForVideoAnalyticsAndClampsToPublishDate() throws Exception {
        LocalDate today = LocalDate.now(AnalyticsDateRangeResolver.REPORTING_ZONE);
        LocalDate publishDay = today.minusDays(2);
        String publishedAt = publishDay.atTime(17, 0)
                .atZone(AnalyticsDateRangeResolver.REPORTING_ZONE).toInstant().toString();
        when(youtubePublicApiService.getVideoPublishDate("laQbWAoa3NI")).thenReturn(publishedAt);

        mockMvc.perform(get("/api/youtube-analytics/v1/analytics/video/laQbWAoa3NI")
                        .param("lastNDays", "30")
                        .param("metrics", "views"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.metrics.views").value(57));

        verify(analyticsService).getSingleVideoAnalytics(
                eq("laQbWAoa3NI"), eq(publishDay.toString()), eq(today.toString()), any());
    }

    @Test
    void returnsAnalyticsDecisionUsingLastNDays() throws Exception {
        DiscoveryOptimizationResult discovery = DiscoveryOptimizationResult.builder()
                .videoId("laQbWAoa3NI")
                .primaryDiagnosis(DiscoveryOptimizationResult.DiscoveryDiagnosis.HEALTHY_DISCOVERY)
                .build();
        AnalyticsDecisionResult decision = new AnalyticsDecisionResult(
                "laQbWAoa3NI",
                AnalyticsDecisionResult.DecisionAction.CONTINUE_OBSERVING,
                "Continue observing.",
                java.util.List.of("Discovery and retention signals are healthy."),
                java.util.List.of());
        when(discoveryOptimizationService.analyze(any(), any(), any())).thenReturn(discovery);
        when(analyticsDecisionService.decide(discovery)).thenReturn(decision);

        mockMvc.perform(get("/api/youtube-analytics/v1/analytics/video/laQbWAoa3NI/decision")
                        .param("lastNDays", "14"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.videoId").value("laQbWAoa3NI"))
                .andExpect(jsonPath("$.data.action").value("CONTINUE_OBSERVING"));
    }

    @Test
    void rejectsZeroLastNDays() throws Exception {
        mockMvc.perform(get("/api/youtube-analytics/v1/analytics/video/laQbWAoa3NI")
                        .param("lastNDays", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void rejectsLastNDaysAboveMaximum() throws Exception {
        mockMvc.perform(get("/api/youtube-analytics/v1/analytics/video/laQbWAoa3NI/decision")
                        .param("lastNDays", "3651"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void rejectsNonNumericLastNDays() throws Exception {
        mockMvc.perform(get("/api/youtube-analytics/v1/analytics/video/laQbWAoa3NI/decision")
                        .param("lastNDays", "last-week"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void exposesStudioReportCapabilities() throws Exception {
        when(studioReportsService.capabilities()).thenReturn(Map.of(
                "supported", Map.of("overview", "YouTube Analytics API v2"),
                "unsupportedOrNotOfficiallyExposed", Map.of(
                        "returningViewers", "Not available through official public API")));

        mockMvc.perform(get("/api/youtube-analytics/v1/analytics/reports/capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.supported.overview").value("YouTube Analytics API v2"))
                .andExpect(jsonPath("$.data.unsupportedOrNotOfficiallyExposed.returningViewers").exists());
    }

    @Test
    void exposesStudioOverviewReportUsingLastNDays() throws Exception {
        when(studioReportsService.overview(any(), any())).thenReturn(Map.of(
                "report", "channel overview",
                "startDate", "2026-10-01",
                "endDate", "2026-10-15",
                "metrics", Map.of("views", 57, "netSubscribers", 3),
                "revenueAvailable", false));

        mockMvc.perform(get("/api/youtube-analytics/v1/analytics/reports/overview")
                        .param("lastNDays", "15"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.metrics.views").value(57))
                .andExpect(jsonPath("$.data.metrics.netSubscribers").value(3))
                .andExpect(jsonPath("$.data.revenueAvailable").value(false));
    }

    @Test
    void acceptsDefaultLastNDaysWhenOmitted() throws Exception {
        when(studioReportsService.overview(any(), any())).thenReturn(Map.of(
                "report", "channel overview",
                "metrics", Map.of("views", 57),
                "revenueAvailable", false));

        mockMvc.perform(get("/api/youtube-analytics/v1/analytics/reports/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.metrics.views").value(57));
    }

    @Test
    void rejectsBlankVideoId() throws Exception {
        mockMvc.perform(get("/api/youtube-analytics/v1/analytics/video/{videoId}", " ")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }
}
