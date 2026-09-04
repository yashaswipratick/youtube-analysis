package com.youtube.analytics.controller;

import com.youtube.analytics.exception.GlobalExceptionHandler;
import com.youtube.analytics.model.AnalyticsDecisionResult;
import com.youtube.analytics.model.DiscoveryOptimizationResult;
import com.youtube.analytics.model.VideoAnalyticsResult;
import com.youtube.analytics.service.AnalyticsDecisionService;
import com.youtube.analytics.service.DiscoveryOptimizationService;
import com.youtube.analytics.service.OpenAiAnalysisService;
import com.youtube.analytics.service.RecommendationEngineService;
import com.youtube.analytics.service.RetentionAnalysisService;
import com.youtube.analytics.service.YouTubeAnalyticsService;
import com.youtube.analytics.service.YouTubeChannelGeographyAnalyticsService;
import com.youtube.analytics.service.YouTubeGeographyAnalyticsService;
import com.youtube.analytics.service.YouTubeTrafficSourceAnalyticsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class YouTubeAnalyticsControllerTest {
    private MockMvc mockMvc;
    private DiscoveryOptimizationService discoveryOptimizationService;
    private AnalyticsDecisionService analyticsDecisionService;

    @BeforeEach
    void setUp() {
        YouTubeAnalyticsService service = mock(YouTubeAnalyticsService.class);
        YouTubeTrafficSourceAnalyticsService trafficSourceService = mock(YouTubeTrafficSourceAnalyticsService.class);
        YouTubeGeographyAnalyticsService geographyService = mock(YouTubeGeographyAnalyticsService.class);
        YouTubeChannelGeographyAnalyticsService channelGeographyService = mock(YouTubeChannelGeographyAnalyticsService.class);
        OpenAiAnalysisService openAiAnalysisService = mock(OpenAiAnalysisService.class);
        RecommendationEngineService recommendationEngineService = mock(RecommendationEngineService.class);
        RetentionAnalysisService retentionAnalysisService = mock(RetentionAnalysisService.class);
        discoveryOptimizationService = mock(DiscoveryOptimizationService.class);
        analyticsDecisionService = mock(AnalyticsDecisionService.class);

        when(service.getSingleVideoAnalytics(any(), any(), any(), any())).thenReturn(VideoAnalyticsResult.builder()
                .videoId("laQbWAoa3NI")
                .title("Weekend Trip")
                .startDate("2026-07-27")
                .endDate("2026-08-29")
                .metrics(Map.of("views", 57))
                .build());

        mockMvc = MockMvcBuilders.standaloneSetup(new YouTubeAnalyticsController(
                        service,
                        trafficSourceService,
                        geographyService,
                        channelGeographyService,
                        openAiAnalysisService,
                        recommendationEngineService,
                        retentionAnalysisService,
                        discoveryOptimizationService,
                        analyticsDecisionService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void acceptsValidVideoAnalyticsRequest() throws Exception {
        mockMvc.perform(get("/api/youtube/analytics/video/laQbWAoa3NI")
                        .param("startDate", "2026-07-27")
                        .param("endDate", "2026-08-29")
                        .param("metrics", "views"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.metrics.views").value(57));
    }

    @Test
    void returnsAnalyticsDecisionForValidRequest() throws Exception {
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
        when(discoveryOptimizationService.analyze("laQbWAoa3NI", "2026-07-27", "2026-08-29"))
                .thenReturn(discovery);
        when(analyticsDecisionService.decide(discovery)).thenReturn(decision);

        mockMvc.perform(get("/api/youtube/analytics/video/laQbWAoa3NI/decision")
                        .param("startDate", "2026-07-27")
                        .param("endDate", "2026-08-29"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.videoId").value("laQbWAoa3NI"))
                .andExpect(jsonPath("$.data.action").value("CONTINUE_OBSERVING"));
    }

    @Test
    void rejectsInvalidDecisionStartDate() throws Exception {
        mockMvc.perform(get("/api/youtube/analytics/video/laQbWAoa3NI/decision")
                        .param("startDate", "2026-02-30"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void rejectsInvalidDecisionEndDate() throws Exception {
        mockMvc.perform(get("/api/youtube/analytics/video/laQbWAoa3NI/decision")
                        .param("endDate", "not-a-date"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void rejectsDecisionDateRangeWithStartAfterEnd() throws Exception {
        mockMvc.perform(get("/api/youtube/analytics/video/laQbWAoa3NI/decision")
                        .param("startDate", "2026-08-29")
                        .param("endDate", "2026-07-27"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("startDate must be on or before endDate"));
    }

    @Test
    void rejectsBlankDecisionVideoId() throws Exception {
        mockMvc.perform(get("/api/youtube/analytics/video/{videoId}/decision", " "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void rejectsInvalidStartDate() throws Exception {
        mockMvc.perform(get("/api/youtube/analytics/video/laQbWAoa3NI")
                        .param("startDate", "2026-02-30"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void rejectsInvalidEndDate() throws Exception {
        mockMvc.perform(get("/api/youtube/analytics/video/laQbWAoa3NI")
                        .param("endDate", "not-a-date"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void rejectsDateRangeWithStartAfterEnd() throws Exception {
        mockMvc.perform(get("/api/youtube/analytics/video/laQbWAoa3NI")
                        .param("startDate", "2026-08-29")
                        .param("endDate", "2026-07-27"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("startDate must be on or before endDate"));
    }

    @Test
    void rejectsBlankVideoId() throws Exception {
        mockMvc.perform(get("/api/youtube/analytics/video/{videoId}", " ")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }
}
