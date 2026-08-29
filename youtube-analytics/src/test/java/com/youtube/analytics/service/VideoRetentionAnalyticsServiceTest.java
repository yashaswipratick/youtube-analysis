package com.youtube.analytics.service;

import com.youtube.analytics.model.VideoRetentionAnalyticsResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class VideoRetentionAnalyticsServiceTest {

    @Test
    void parsesRetentionCurveAndSummaryMetrics() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> firstUri = new AtomicReference<>();

        ExchangeFunction exchange = request -> {
            int call = calls.getAndIncrement();
            if (call == 0) {
                firstUri.set(request.url().toString());
                return Mono.just(jsonResponse("{\"columnHeaders\":["
                        + "{\"name\":\"elapsedVideoTimeRatio\"},"
                        + "{\"name\":\"audienceWatchRatio\"},"
                        + "{\"name\":\"relativeRetentionPerformance\"}],"
                        + "\"rows\":[[0.01,0.98,0.60],[0.50,0.32,0.45],[1.0,0.10,0.70]]}"));
            }
            return Mono.just(jsonResponse("{\"columnHeaders\":["
                    + "{\"name\":\"averageViewDuration\"},"
                    + "{\"name\":\"averageViewPercentage\"}],"
                    + "\"rows\":[[122,25.0]]}"));
        };

        YouTubeAnalyticsService service = new YouTubeAnalyticsService(
                WebClient.builder().exchangeFunction(exchange).build(), null);

        VideoRetentionAnalyticsResult result = service.getVideoRetentionAnalytics(
                "laQbWAoa3NI", "2026-07-27", "2026-08-29");

        assertThat(calls.get()).isEqualTo(2);
        assertThat(firstUri.get())
                .contains("dimensions=elapsedVideoTimeRatio")
                .contains("metrics=audienceWatchRatio,relativeRetentionPerformance")
                .contains("filters=video==laQbWAoa3NI");
        assertThat(result.getAverageViewDurationSeconds()).isEqualTo(122.0);
        assertThat(result.getAverageViewPercentage()).isEqualTo(25.0);
        assertThat(result.getRetention()).hasSize(3);
        assertThat(result.getRetention().get(1).getElapsedVideoTimeRatio()).isEqualTo(0.50);
        assertThat(result.getRetention().get(1).getAudienceWatchRatio()).isEqualTo(0.32);
        assertThat(result.getRetention().get(1).getRelativeRetentionPerformance()).isEqualTo(0.45);
    }

    private ClientResponse jsonResponse(String body) {
        return ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build();
    }
}
