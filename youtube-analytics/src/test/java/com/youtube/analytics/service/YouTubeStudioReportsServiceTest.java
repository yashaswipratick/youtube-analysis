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
        @SuppressWarnings("unchecked")
        Map<String, Object> revenue = (Map<String, Object>) result.get("revenue");
        assertThat(revenue).containsEntry("available", false);
        @SuppressWarnings("unchecked")
        Map<String, String> revenueStatus = (Map<String, String>) revenue.get("sectionStatus");
        assertThat(revenueStatus).containsEntry("estimatedRevenue", "UNAVAILABLE")
                .containsEntry("adPerformance", "UNAVAILABLE")
                .containsEntry("playbackBasedPerformance", "UNAVAILABLE");
        assertThat(requestCount.get()).isEqualTo(4);
    }

    @Test
    void audienceQueriesUniqueViewersWithDayDimensionAndDoesNotSumDailyUniques() {
        List<String> requestUris = new ArrayList<>();
        AtomicInteger requestCount = new AtomicInteger();
        ExchangeFunction exchange = request -> {
            requestUris.add(request.url().toString());
            int call = requestCount.incrementAndGet();
            String body = switch (call) {
                case 1 -> """
                        {"columnHeaders":[{"name":"subscribedStatus"},{"name":"views"},{"name":"estimatedMinutesWatched"}],
                         "rows":[["SUBSCRIBED",20,60]]}
                        """;
                case 2 -> """
                        {"columnHeaders":[{"name":"ageGroup"},{"name":"gender"},{"name":"viewerPercentage"}],
                         "rows":[["age25-34","female",40.0]]}
                        """;
                default -> """
                        {"columnHeaders":[{"name":"day"},{"name":"uniques"}],
                         "rows":[["2026-10-01",30],["2026-10-02",25]]}
                        """;
            };
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .body(body)
                    .build());
        };
        YouTubeStudioReportsService service = new YouTubeStudioReportsService(
                WebClient.builder().exchangeFunction(exchange).build(),
                mock(YouTubeDataService.class),
                mock(YouTubeReachReportingService.class));

        Map<String, Object> result = service.audience("2026-10-01", "2026-10-02", null);

        assertThat(requestCount.get()).isEqualTo(3);
        assertThat(requestUris.get(2)).contains("dimensions=day").contains("metrics=uniques");
        assertThat(result.get("uniqueViewers")).isNull();
        assertThat(result.get("uniqueViewersStatus")).isEqualTo("AVAILABLE");
        assertThat(result.get("uniqueViewersNote").toString()).contains("not additive");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> byDay = (List<Map<String, Object>>) result.get("uniqueViewersByDay");
        assertThat(byDay).hasSize(2);
        assertThat(byDay.get(0)).containsEntry("day", "2026-10-01").containsEntry("uniques", 30);
    }

    @Test
    void uploadFrequencyGroupsPublishedVideosByMondayWeekAndCalendarMonth() {
        YouTubeDataService dataService = mock(YouTubeDataService.class);
        org.mockito.Mockito.when(dataService.getAllUploadedVideoPublishDates()).thenReturn(Map.of(
                "video000001", "2026-10-01T12:00:00Z",
                "video000002", "2026-10-05T12:00:00Z",
                "video000003", "2026-09-30T12:00:00Z",
                "video000004", "2026-11-01T12:00:00Z"));
        YouTubeStudioReportsService service = new YouTubeStudioReportsService(
                WebClient.builder().build(), dataService, mock(YouTubeReachReportingService.class));

        Map<String, Object> result = service.uploadFrequency("2026-10-01", "2026-10-10");

        assertThat(result).containsEntry("totalPublishedVideosInRange", 2)
                .containsEntry("averageUploadsPerWeek", 1.0)
                .containsEntry("averageUploadsPerMonth", 2.0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> weekly = (List<Map<String, Object>>) result.get("weekly");
        assertThat(weekly).hasSize(2);
        assertThat(weekly.get(0)).containsEntry("periodStart", "2026-09-28").containsEntry("uploads", 1L);
        assertThat(weekly.get(1)).containsEntry("periodStart", "2026-10-05").containsEntry("uploads", 1L);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> monthly = (List<Map<String, Object>>) result.get("monthly");
        assertThat(monthly).hasSize(1);
        assertThat(monthly.get(0)).containsEntry("periodStart", "2026-10-01").containsEntry("uploads", 2L);
    }

    @Test
    void playlistAnalyticsUsesPlaylistFilterForOverviewDailyAndTrafficReports() {
        List<String> requestUris = new ArrayList<>();
        ExchangeFunction exchange = request -> {
            requestUris.add(request.url().toString());
            String query = request.url().getQuery() == null ? "" : request.url().getQuery();
            String body;
            if (query.contains("dimensions=day")) {
                body = """
                        {"columnHeaders":[{"name":"day"},{"name":"views"},{"name":"playlistViews"}],
                         "rows":[["2026-10-01",20,7]]}
                        """;
            } else if (query.contains("insightTrafficSourceType")) {
                body = """
                        {"columnHeaders":[{"name":"insightTrafficSourceType"},{"name":"views"}],
                         "rows":[["YT_SEARCH",5]]}
                        """;
            } else {
                body = """
                        {"columnHeaders":[{"name":"views"},{"name":"playlistViews"},{"name":"playlistStarts"}],
                         "rows":[[20,7,3]]}
                        """;
            }
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .body(body)
                    .build());
        };
        YouTubeStudioReportsService service = new YouTubeStudioReportsService(
                WebClient.builder().exchangeFunction(exchange).build(),
                mock(YouTubeDataService.class),
                mock(YouTubeReachReportingService.class));

        Map<String, Object> result = service.playlistAnalytics(
                "2026-10-01", "2026-10-15", "PLabcdefghijklmno01234567");

        assertThat(requestUris).hasSize(3);
        assertThat(requestUris).allSatisfy(uri -> {
            assertThat(uri).contains("playlist");
            assertThat(uri).contains("PLabcdefghijklmno01234567");
        });
        assertThat(result).containsEntry("overviewStatus", "AVAILABLE")
                .containsEntry("dailyStatus", "AVAILABLE")
                .containsEntry("trafficSourcesStatus", "AVAILABLE");
        @SuppressWarnings("unchecked")
        Map<String, Object> overview = (Map<String, Object>) result.get("overview");
        assertThat(overview).containsEntry("views", 20).containsEntry("playlistViews", 7);
    }

    @Test
    void rejectsMalformedPlaylistIdsBeforeCallingGoogle() {
        YouTubeStudioReportsService service = new YouTubeStudioReportsService(
                WebClient.builder().build(),
                mock(YouTubeDataService.class),
                mock(YouTubeReachReportingService.class));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> service.playlistAnalytics("2026-10-01", "2026-10-15", "https://not-an-id"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("playlistId");
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
