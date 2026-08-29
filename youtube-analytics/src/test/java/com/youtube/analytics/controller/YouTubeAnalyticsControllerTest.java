package com.youtube.analytics.controller;

import com.youtube.analytics.exception.GlobalExceptionHandler;
import com.youtube.analytics.model.VideoAnalyticsResult;
import com.youtube.analytics.service.YouTubeAnalyticsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class YouTubeAnalyticsControllerTest {
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        YouTubeAnalyticsService service = mock(YouTubeAnalyticsService.class);
        when(service.getSingleVideoAnalytics(any(), any(), any(), any())).thenReturn(VideoAnalyticsResult.builder()
                .videoId("laQbWAoa3NI")
                .title("Weekend Trip")
                .startDate("2026-07-27")
                .endDate("2026-08-29")
                .metrics(Map.of("views", 57))
                .build());
        mockMvc = MockMvcBuilders.standaloneSetup(new YouTubeAnalyticsController(service))
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
