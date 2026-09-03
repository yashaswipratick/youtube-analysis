package com.youtube.analytics.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youtube.analytics.model.YouTubeReachReportResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class YouTubeReachReportingServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void aggregatesMatchingVideoAcrossReportsWithWeightedCtr() {
        List<RequestRecord> requests = new ArrayList<>();
        ExchangeFunction exchangeFunction = request -> {
            requests.add(new RequestRecord(request.method(), request.url().toString()));
            if (request.method() == HttpMethod.GET && request.url().getPath().equals("/v1/jobs")) {
                return json("{\"jobs\":[{\"id\":\"job-1\",\"reportTypeId\":\"channel_reach_basic_a1\"}]}");
            }
            if (request.method() == HttpMethod.GET && request.url().getPath().equals("/v1/jobs/job-1/reports")) {
                return json("{\"reports\":["
                        + "{\"startTime\":\"2026-08-01T00:00:00Z\",\"endTime\":\"2026-08-01T23:59:59Z\",\"downloadUrl\":\"https://reports.test/report-1.csv\"},"
                        + "{\"startTime\":\"2026-08-02T00:00:00Z\",\"endTime\":\"2026-08-02T23:59:59Z\",\"downloadUrl\":\"https://reports.test/report-2.csv\"}"
                        + "]}");
            }
            if (request.url().toString().equals("https://reports.test/report-1.csv")) {
                return csv("date,video_id,video_thumbnail_impressions,video_thumbnail_impressions_ctr\n"
                        + "2026-08-01,abc123,100,2.0\n"
                        + "2026-08-01,other,900,9.0\n");
            }
            if (request.url().toString().equals("https://reports.test/report-2.csv")) {
                return csv("video_thumbnail_impressions_ctr,date,video_id,video_thumbnail_impressions\n"
                        + "6.0,2026-08-02,abc123,300\n");
            }
            return Mono.just(ClientResponse.create(HttpStatus.NOT_FOUND).build());
        };

        YouTubeReachReportResult result = service(exchangeFunction).getVideoReach(
                "abc123", "2026-08-01", "2026-08-10");

        assertThat(result.available()).isTrue();
        assertThat(result.impressions()).isEqualTo(400L);
        assertThat(result.impressionsClickThroughRate()).isEqualTo(5.0);
        assertThat(requests.stream().filter(r -> r.url().contains("report-")).count()).isEqualTo(2);
    }

    @Test
    void createsReachJobWhenNoneExistsAndReturnsUnavailableUntilReportExists() {
        List<RequestRecord> requests = new ArrayList<>();
        ExchangeFunction exchangeFunction = request -> {
            requests.add(new RequestRecord(request.method(), request.url().toString()));
            if (request.method() == HttpMethod.GET && request.url().getPath().equals("/v1/jobs")) {
                return json("{\"jobs\":[]}");
            }
            if (request.method() == HttpMethod.POST && request.url().getPath().equals("/v1/jobs")) {
                return json("{\"id\":\"job-created\",\"reportTypeId\":\"channel_reach_basic_a1\"}");
            }
            if (request.method() == HttpMethod.GET && request.url().getPath().equals("/v1/jobs/job-created/reports")) {
                return json("{\"reports\":[]}");
            }
            return Mono.just(ClientResponse.create(HttpStatus.NOT_FOUND).build());
        };

        YouTubeReachReportResult result = service(exchangeFunction).getVideoReach(
                "abc123", "2026-08-01", "2026-08-10");

        assertThat(result.available()).isFalse();
        assertThat(result.impressions()).isNull();
        assertThat(result.impressionsClickThroughRate()).isNull();
        assertThat(requests).extracting(RequestRecord::method).containsExactly(
                HttpMethod.GET, HttpMethod.POST, HttpMethod.GET);
    }

    @Test
    void ignoresReportsOutsideRequestedRangeAndMissingColumns() {
        ExchangeFunction exchangeFunction = request -> {
            if (request.method() == HttpMethod.GET && request.url().getPath().equals("/v1/jobs")) {
                return json("{\"jobs\":[{\"id\":\"job-1\",\"reportTypeId\":\"channel_reach_basic_a1\"}]}");
            }
            if (request.method() == HttpMethod.GET && request.url().getPath().equals("/v1/jobs/job-1/reports")) {
                return json("{\"reports\":["
                        + "{\"startTime\":\"2026-07-01T00:00:00Z\",\"endTime\":\"2026-07-01T23:59:59Z\",\"downloadUrl\":\"https://reports.test/outside.csv\"},"
                        + "{\"startTime\":\"2026-08-05T00:00:00Z\",\"endTime\":\"2026-08-05T23:59:59Z\",\"downloadUrl\":\"https://reports.test/missing.csv\"}"
                        + "]}");
            }
            return csv("date,video_id,video_thumbnail_impressions\n2026-08-05,abc123,100\n");
        };

        YouTubeReachReportResult result = service(exchangeFunction).getVideoReach(
                "abc123", "2026-08-01", "2026-08-04");

        assertThat(result.available()).isFalse();
        assertThat(result.impressions()).isNull();
        assertThat(result.impressionsClickThroughRate()).isNull();
    }

    private YouTubeReachReportingService service(ExchangeFunction exchangeFunction) {
        WebClient client = WebClient.builder().exchangeFunction(exchangeFunction).build();
        return new YouTubeReachReportingService(client, objectMapper);
    }

    private Mono<ClientResponse> json(String body) {
        return Mono.just(ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build());
    }

    private Mono<ClientResponse> csv(String body) {
        return Mono.just(ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, "text/csv")
                .body(body)
                .build());
    }

    private record RequestRecord(HttpMethod method, String url) {
    }
}
