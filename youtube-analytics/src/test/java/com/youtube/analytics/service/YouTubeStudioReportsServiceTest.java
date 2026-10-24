package com.youtube.analytics.service;

import com.youtube.analytics.model.YouTubeAnalyticsApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class YouTubeStudioReportsServiceTest {

    @Test
    void overviewReturnsCoreMetricsAndMarksRevenueUnavailableWithoutMonetaryAccess() {
        AtomicInteger requestCount = new AtomicInteger();
        ExchangeFunction exchange = request -> {
            int call = requestCount.incrementAndGet();
            if (call == 1) {
                return Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .body("""
                                {
                                  "columnHeaders":[
                                    {"name":"views"},
                                    {"name":"estimatedMinutesWatched"},
                                    {"name":"subscribersGained"},
                                    {"name":"subscribersLost"},
                                    {"name":"averageViewDuration"},
                                    {"name":"averageViewPercentage"},
                                    {"name":"likes"},
                                    {"name":"comments"},
                                    {"name":"shares"}
                                  ],
                                  "rows":[[57,120,4,1,40,35.5,4,1,2]]
                                }
                                """)
                        .build());
            }
            return Mono.just(ClientResponse.create(HttpStatus.FORBIDDEN)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .body("{\"error\":{\"message\":\"insufficient permissions\"}}")
                    .build());
        };

        YouTubeStudioReportsService service = new YouTubeStudioReportsService(
                WebClient.builder().exchangeFunction(exchange).build(),
                mock(YouTubeDataService.class),
                mock(YouTubeReachReportingService.class));

        Map<String, Object> result = service.overview("2026-10-01", "2026-10-15");

        @SuppressWarnings("unchecked")
        Map<String, Object> metrics = (Map<String, Object>) result.get("metrics");
        assertThat(result).containsEntry("startDate", "2026-10-01")
                .containsEntry("endDate", "2026-10-15")
                .containsEntry("revenueAvailable", false);
        assertThat(metrics).containsEntry("views", 57)
                .containsEntry("estimatedMinutesWatched", 120)
                .containsEntry("subscribersGained", 4)
                .containsEntry("subscribersLost", 1)
                .containsEntry("netSubscribers", 3L)
                .containsEntry("averageViewDuration", 40)
                .containsEntry("averageViewPercentage", 35.5);
        assertThat(result.get("revenue")).isNull();
        assertThat(requestCount.get()).isEqualTo(2);
    }

    @Test
    void capabilitiesExplainStudioOnlyMetricsNotAvailableThroughOfficialApi() {
        YouTubeStudioReportsService service = new YouTubeStudioReportsService(
                WebClient.builder().build(),
                mock(YouTubeDataService.class),
                mock(YouTubeReachReportingService.class));

        Map<String, Object> capabilities = service.capabilities();

        @SuppressWarnings("unchecked")
        Map<String, String> unsupported =
                (Map<String, String>) capabilities.get("unsupportedOrNotOfficiallyExposed");
        assertThat(unsupported).containsKeys(
                "returningViewers",
                "whenViewersAreOnYouTube",
                "otherChannelsAudienceWatches",
                "otherVideosAudienceWatches",
                "uncoveredSearchDemand");
    }

    @Test
    void calculatesRpmFromEstimatedRevenueAndVideoViews() {
        AtomicInteger requests = new AtomicInteger();
        ExchangeFunction exchange = request -> {
            if (requests.incrementAndGet() == 1) {
                return Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .body("""
                                {"columnHeaders":[{"name":"video"},{"name":"estimatedRevenue"}],
                                 "rows":[["video123",2.0]]}
                                """)
                        .build());
            }
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .body("""
                            {"columnHeaders":[{"name":"video"},{"name":"views"}],
                             "rows":[["video123",500]]}
                            """)
                    .build());
        };
        YouTubeStudioReportsService service = new YouTubeStudioReportsService(
                WebClient.builder().exchangeFunction(exchange).build(),
                mock(YouTubeDataService.class),
                mock(YouTubeReachReportingService.class));

        Map<String, Object> result = service.revenue("2026-10-01", "2026-10-15", null, 15);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("metrics");
        assertThat(result.get("available")).isEqualTo(true);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("estimatedRevenue", 2.0)
                .containsEntry("rpm", 4.0);
    }
}
