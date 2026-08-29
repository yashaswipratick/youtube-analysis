package com.youtube.analytics.service;

import com.youtube.analytics.model.ChannelAnalyticsResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ChannelAnalyticsServiceTest {

    private final AtomicReference<String> requestUri = new AtomicReference<>();

    @Test
    void parsesChannelAnalyticsAndBuildsChannelQuery() {
        YouTubeAnalyticsService service = serviceResponding(
                "{\"columnHeaders\":[{\"name\":\"views\"},{\"name\":\"likes\"},{\"name\":\"subscribersGained\"}],\"rows\":[[57,3,2]]}");

        ChannelAnalyticsResult result = service.getChannelAnalytics(
                "2026-07-27", "2026-08-29", List.of("views", "likes", "subscribersGained"));

        assertThat(result.getStartDate()).isEqualTo("2026-07-27");
        assertThat(result.getEndDate()).isEqualTo("2026-08-29");
        assertThat(result.getMetrics())
                .containsEntry("views", 57)
                .containsEntry("likes", 3)
                .containsEntry("subscribersGained", 2);
        assertThat(requestUri.get())
                .contains("ids=channel==MINE")
                .contains("metrics=views,likes,subscribersGained")
                .doesNotContain("filters=")
                .doesNotContain("dimensions=");
    }

    @Test
    void handlesEmptyChannelAnalyticsRows() {
        YouTubeAnalyticsService service = serviceResponding(
                "{\"columnHeaders\":[{\"name\":\"views\"}],\"rows\":[]}");

        ChannelAnalyticsResult result = service.getChannelAnalytics(
                "2026-07-27", "2026-08-29", List.of("views"));

        assertThat(result.getMetrics()).isEmpty();
    }

    private YouTubeAnalyticsService serviceResponding(String body) {
        ExchangeFunction exchange = request -> {
            requestUri.set(request.url().toString());
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .body(body)
                    .build());
        };
        YouTubeAnalyticsService service = new YouTubeAnalyticsService(
                WebClient.builder().exchangeFunction(exchange).build(), null);
        ReflectionTestUtils.setField(service, "defaultMetrics",
                "views,engagedViews,estimatedMinutesWatched,averageViewDuration,averageViewPercentage,likes,comments,shares,subscribersGained,subscribersLost");
        return service;
    }
}
