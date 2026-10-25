package com.youtube.analytics.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youtube.analytics.config.YouTubeAnalyticsProperties;
import com.youtube.analytics.config.YouTubeReportingProperties;
import com.youtube.analytics.model.VideoAnalyticsResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class YouTubeAnalyticsReportAggregationServiceTest {

    private static final String VIDEO_ID = "laQbWAoa3NI";
    private static final String ANALYTICS_RESPONSE =
            "{\"columnHeaders\":[{\"name\":\"views\",\"columnType\":\"METRIC\",\"dataType\":\"INTEGER\"}],\"rows\":[[12]]}";

    @TempDir
    Path archiveDirectory;

    @Test
    void mergesReportSectionsAndReplaysRawResponsesWithoutCallingApiAgain() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        YouTubeAnalyticsReportAggregationService service = service(calls, false);
        LocalDate safeEnd = LocalDate.now(ZoneId.of("America/Los_Angeles")).minusDays(3);
        VideoAnalyticsResult input = VideoAnalyticsResult.builder()
                .videoId(VIDEO_ID)
                .title("Weekend Trip")
                .publishedAt("2025-01-01T00:00:00Z")
                .startDate(safeEnd.minusDays(27).toString())
                .endDate(safeEnd.toString())
                .metrics(Map.of("views", 12, "likes", 2, "comments", 1, "subscribersGained", 1))
                .build();

        VideoAnalyticsResult enriched = service.enrich(input);

        assertThat(enriched.getReportSections()).containsKeys(
                "core", "cards", "premium", "retention", "demographics", "traffic",
                "trend", "dimensions", "liveStream", "liveOrOnDemand", "youtubeProduct",
                "channelBenchmark", "revenue", "reach", "endScreens", "optionalMetricValidation", "archive");
        Map<?, ?> benchmark = (Map<?, ?>) enriched.getReportSections().get("channelBenchmark");
        Map<?, ?> benchmarkSummary = (Map<?, ?>) benchmark.get("summary");
        assertThat(benchmarkSummary.get("medianViews")).isEqualTo(12.0);
        assertThat(enriched.getArchiveSnapshotId()).isNotBlank();
        Path snapshot = archiveDirectory.resolve(VIDEO_ID).resolve(enriched.getArchiveSnapshotId());
        assertThat(snapshot.resolve("snapshot.json")).exists();
        try (var files = Files.list(snapshot)) {
            assertThat(files.anyMatch(path -> path.getFileName().toString().endsWith(".json"))).isTrue();
        }

        int beforeReplay = calls.get();
        VideoAnalyticsResult replayed = service.replay(VIDEO_ID, enriched.getArchiveSnapshotId());

        assertThat(calls.get()).isEqualTo(beforeReplay);
        assertThat(replayed.getArchiveSnapshotId()).isEqualTo(enriched.getArchiveSnapshotId());
        assertThat(replayed.getRawReportResponses()).isNotEmpty();
        assertThat(replayed.getReportSections()).containsKeys("core", "traffic", "revenue");
    }

    @Test
    void paginatesReportingJobsAndReportsAndWeightsReportingRatesByImpressions() {
        AtomicInteger calls = new AtomicInteger();
        String csv = """
                video_id,date,video_thumbnail_impressions,video_thumbnail_impressions_ctr,end_screen_element_impressions,end_screen_element_clicks,end_screen_element_click_rate
                laQbWAoa3NI,2026-10-01,100,0.1,50,5,0.2
                laQbWAoa3NI,2026-10-02,300,0.2,150,30,0.4
                """;
        ExchangeFunction exchange = request -> {
            calls.incrementAndGet();
            String uri = request.url().toString();
            String body;
            String contentType = MediaType.APPLICATION_JSON_VALUE;

            if (uri.contains("youtubeanalytics.googleapis.com")) {
                body = ANALYTICS_RESPONSE;
            } else if (uri.contains("/download/reach.csv") || uri.contains("/download/end-screens.csv")) {
                body = csv;
                contentType = "text/csv";
            } else if (uri.contains("/jobs/reach-job/reports") || uri.contains("/jobs/end-screen-job/reports")) {
                if (uri.contains("pageToken=reports-next")) {
                    body = "{\"reports\":[]}";
                } else if (uri.contains("/reach-job/reports")) {
                    body = """
                            {"reports":[{"id":"reach-report-1","startTime":"2026-10-01T00:00:00Z",
                             "endTime":"2026-10-02T23:59:59Z","downloadUrl":"https://youtubereporting.googleapis.com/download/reach.csv"}],
                             "nextPageToken":"reports-next"}
                            """;
                } else {
                    body = """
                            {"reports":[{"id":"end-screen-report-1","startTime":"2026-10-01T00:00:00Z",
                             "endTime":"2026-10-02T23:59:59Z","downloadUrl":"https://youtubereporting.googleapis.com/download/end-screens.csv"}]}
                            """;
                }
            } else if (uri.contains("/jobs") && uri.contains("pageToken=jobs-next")) {
                body = "{\"jobs\":[]}";
            } else if (uri.endsWith("/jobs")) {
                body = """
                        {"jobs":[
                          {"id":"reach-job","reportTypeId":"channel_reach_basic_a1"},
                          {"id":"end-screen-job","reportTypeId":"channel_end_screens_a2"}
                        ],"nextPageToken":"jobs-next"}
                        """;
            } else {
                body = "{}";
            }

            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", contentType)
                    .body(body)
                    .build());
        };
        YouTubeAnalyticsProperties properties = new YouTubeAnalyticsProperties();
        properties.setArchiveDirectory(archiveDirectory.toString());
        YouTubeAnalyticsReportAggregationService service = new YouTubeAnalyticsReportAggregationService(
                WebClient.builder().exchangeFunction(exchange).build(),
                new ObjectMapper(),
                properties,
                new YouTubeReportingProperties());

        LocalDate safeEnd = LocalDate.now(ZoneId.of("America/Los_Angeles")).minusDays(3);
        VideoAnalyticsResult input = VideoAnalyticsResult.builder()
                .videoId(VIDEO_ID)
                .title("Weekend Trip")
                .publishedAt("2025-01-01T00:00:00Z")
                .startDate(safeEnd.minusDays(27).toString())
                .endDate(safeEnd.toString())
                .metrics(Map.of("views", 12))
                .build();

        VideoAnalyticsResult enriched = service.enrich(input);

        @SuppressWarnings("unchecked")
        Map<String, Object> reach = (Map<String, Object>) enriched.getReportSections().get("reach");
        @SuppressWarnings("unchecked")
        Map<String, Object> reachTotals = (Map<String, Object>) reach.get("totals");
        assertThat(reach.get("availableReportFiles")).isEqualTo(1);
        assertThat(reachTotals).containsEntry("video_thumbnail_impressions", 400.0)
                .containsEntry("video_thumbnail_impressions_ctr", 0.175);

        @SuppressWarnings("unchecked")
        Map<String, Object> endScreens = (Map<String, Object>) enriched.getReportSections().get("endScreens");
        @SuppressWarnings("unchecked")
        Map<String, Object> endScreenTotals = (Map<String, Object>) endScreens.get("totals");
        assertThat(endScreenTotals).containsEntry("end_screen_element_impressions", 200.0)
                .containsEntry("end_screen_element_clicks", 35.0)
                .containsEntry("end_screen_element_click_rate", 0.35);
        assertThat(calls.get()).isGreaterThan(10);
    }

    @Test
    void rejectedOptionalDimensionReportDoesNotDiscardOtherSections() {
        AtomicInteger calls = new AtomicInteger();
        YouTubeAnalyticsReportAggregationService service = service(calls, true);
        LocalDate safeEnd = LocalDate.now(ZoneId.of("America/Los_Angeles")).minusDays(3);
        VideoAnalyticsResult input = VideoAnalyticsResult.builder()
                .videoId(VIDEO_ID)
                .publishedAt("2025-01-01T00:00:00Z")
                .startDate(safeEnd.minusDays(27).toString())
                .endDate(safeEnd.toString())
                .metrics(Map.of("views", 12))
                .build();

        VideoAnalyticsResult enriched = service.enrich(input);

        Map<?, ?> core = (Map<?, ?>) enriched.getReportSections().get("core");
        Map<?, ?> lifetime = (Map<?, ?>) core.get("lifetime");
        Map<?, ?> demographics = (Map<?, ?>) enriched.getReportSections().get("demographics");
        assertThat(lifetime.get("status")).isEqualTo("AVAILABLE");
        assertThat(demographics.get("status")).isEqualTo("INVALID_COMBINATION");
    }

    private YouTubeAnalyticsReportAggregationService service(AtomicInteger calls, boolean rejectDimensions) {
        ExchangeFunction exchange = request -> {
            calls.incrementAndGet();
            String uri = request.url().toString();
            if (uri.contains("youtubeanalytics.googleapis.com")) {
                if (rejectDimensions && uri.contains("dimensions=")) {
                    return Mono.just(ClientResponse.create(HttpStatus.BAD_REQUEST)
                            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                            .body("{\"error\":{\"message\":\"Invalid metric/dimension combination\"}}")
                            .build());
                }
                return Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .body(ANALYTICS_RESPONSE)
                        .build());
            }
            if (uri.endsWith("/jobs") && request.method().name().equals("POST")) {
                return Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .body("{\"id\":\"reporting-job-1\"}")
                        .build());
            }
            if (uri.endsWith("/jobs")) {
                return Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .body("{\"jobs\":[]}")
                        .build());
            }
            if (uri.contains("/jobs/reporting-job-1/reports")) {
                return Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .body("{\"reports\":[]}")
                        .build());
            }
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .body("")
                    .build());
        };
        YouTubeAnalyticsProperties properties = new YouTubeAnalyticsProperties();
        properties.setArchiveDirectory(archiveDirectory.toString());
        return new YouTubeAnalyticsReportAggregationService(
                WebClient.builder().exchangeFunction(exchange).build(),
                new ObjectMapper(),
                properties,
                new YouTubeReportingProperties());
    }
}
