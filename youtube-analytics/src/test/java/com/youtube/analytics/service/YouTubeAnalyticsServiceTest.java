package com.youtube.analytics.service;

import com.youtube.analytics.exception.YouTubeAnalyticsApiException;
import com.youtube.analytics.model.VideoAnalyticsResult;
import com.youtube.analytics.model.VideoMeta;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class YouTubeAnalyticsServiceTest {

    @Mock
    private YouTubeDataService dataService;

    private final AtomicReference<String> requestUri = new AtomicReference<>();

    private void stubMetadata() {
        when(dataService.getVideoMeta(List.of("laQbWAoa3NI"))).thenReturn(Map.of(
                "laQbWAoa3NI", new VideoMeta("laQbWAoa3NI", "Weekend Trip", "2026-07-27T13:00:17Z")));
    }

    @Test
    void parsesSuccessfulVideoAnalyticsResponseForCustomMetrics() {
        stubMetadata();
        YouTubeAnalyticsService service = serviceResponding(HttpStatus.OK, successfulResponse());

        VideoAnalyticsResult result = service.getSingleVideoAnalytics(
                "laQbWAoa3NI", "2026-07-27", "2026-08-29", List.of("views", "likes", "comments"));

        assertThat(result.getVideoId()).isEqualTo("laQbWAoa3NI");
        assertThat(result.getTitle()).isEqualTo("Weekend Trip");
        assertThat(result.getMetrics()).containsEntry("views", 57).containsEntry("likes", 3).containsEntry("comments", 1);
        assertThat(requestUri.get()).contains("metrics=views,likes,comments").contains("filters=video==laQbWAoa3NI");
    }

    @Test
    void usesConfiguredDefaultMetricsWhenMetricsAreOmitted() {
        stubMetadata();
        YouTubeAnalyticsService service = serviceResponding(HttpStatus.OK, successfulResponse());

        service.getSingleVideoAnalytics("laQbWAoa3NI", "2026-07-27", "2026-08-29", null);

        assertThat(requestUri.get()).contains("metrics=views,engagedViews,estimatedMinutesWatched");
    }

    @Test
    void handlesEmptyAnalyticsRowsGracefully() {
        stubMetadata();
        YouTubeAnalyticsService service = serviceResponding(HttpStatus.OK, "{\"columnHeaders\":[{\"name\":\"views\"}]}" );

        VideoAnalyticsResult result = service.getSingleVideoAnalytics("laQbWAoa3NI", "2026-07-27", "2026-08-29", List.of("views"));

        assertThat(result.getMetrics()).isEmpty();
    }

    @Test
    void handlesMissingRequestedMetricInAnalyticsResponse() {
        stubMetadata();
        YouTubeAnalyticsService service = serviceResponding(HttpStatus.OK,
                "{\"columnHeaders\":[{\"name\":\"views\"}],\"rows\":[[57]]}");

        VideoAnalyticsResult result = service.getSingleVideoAnalytics(
                "laQbWAoa3NI", "2026-07-27", "2026-08-29", List.of("views", "likes"));

        assertThat(result.getMetrics()).containsOnly(Map.entry("views", 57));
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 429, 500})
    void convertsYouTubeErrorResponsesToSanitizedException(int status) {
        YouTubeAnalyticsService service = serviceResponding(HttpStatus.valueOf(status), "{\"error\":\"sensitive upstream detail\"}");

        assertThatThrownBy(() -> service.getSingleVideoAnalytics(
                "laQbWAoa3NI", "2026-07-27", "2026-08-29", List.of("views")))
                .isInstanceOf(YouTubeAnalyticsApiException.class)
                .hasMessage("YouTube Analytics API returned " + status);
    }

    private YouTubeAnalyticsService serviceResponding(HttpStatus status, String body) {
        ExchangeFunction exchange = request -> {
            requestUri.set(request.url().toString());
            return Mono.just(ClientResponse.create(status)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .body(body)
                    .build());
        };
        YouTubeAnalyticsService service = new YouTubeAnalyticsService(
                WebClient.builder().exchangeFunction(exchange).build(), dataService);
        ReflectionTestUtils.setField(service, "defaultMetrics",
                "views,engagedViews,estimatedMinutesWatched,averageViewDuration,averageViewPercentage,likes,comments,shares,subscribersGained,subscribersLost");
        return service;
    }

    private String successfulResponse() {
        return """
                {"columnHeaders":[
                  {"name":"views","columnType":"METRIC","dataType":"INTEGER"},
                  {"name":"likes","columnType":"METRIC","dataType":"INTEGER"},
                  {"name":"comments","columnType":"METRIC","dataType":"INTEGER"}
                ],"rows":[[57,3,1]]}
                """;
    }
}
