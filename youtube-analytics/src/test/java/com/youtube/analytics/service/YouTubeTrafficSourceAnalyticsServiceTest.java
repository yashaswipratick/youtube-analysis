package com.youtube.analytics.service;

import com.youtube.analytics.model.TrafficSourceAnalyticsResult;
import com.youtube.analytics.model.VideoMeta;
import org.junit.jupiter.api.Test;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class YouTubeTrafficSourceAnalyticsServiceTest {

    @Mock
    private YouTubeDataService dataService;

    private final AtomicReference<String> requestUri = new AtomicReference<>();

    @Test
    void parsesTrafficSourceRows() {
        when(dataService.getVideoMeta(List.of("laQbWAoa3NI"))).thenReturn(Map.of(
                "laQbWAoa3NI", new VideoMeta("laQbWAoa3NI", "Weekend Trip", "2026-07-27T13:00:17Z")));

        YouTubeTrafficSourceAnalyticsService service = serviceResponding();

        TrafficSourceAnalyticsResult result = service.getVideoTrafficSources(
                "laQbWAoa3NI", "2026-07-27", "2026-08-29", List.of("views", "estimatedMinutesWatched"));

        assertThat(result.getVideoId()).isEqualTo("laQbWAoa3NI");
        assertThat(result.getSources()).hasSize(3);
        assertThat(result.getSources().get(0).getTrafficSource()).isEqualTo("YT_SEARCH");
        assertThat(result.getSources().get(0).getMetrics())
                .containsEntry("views", 20)
                .containsEntry("estimatedMinutesWatched", 100);
        assertThat(requestUri.get())
                .contains("dimensions=insightTrafficSourceType")
                .contains("filters=video==laQbWAoa3NI")
                .contains("metrics=views,estimatedMinutesWatched");
    }

    @Test
    void returnsEmptySourcesWhenAnalyticsRowsAreEmpty() {
        when(dataService.getVideoMeta(List.of("laQbWAoa3NI"))).thenReturn(Map.of(
                "laQbWAoa3NI", new VideoMeta("laQbWAoa3NI", "Weekend Trip", "2026-07-27T13:00:17Z")));

        YouTubeTrafficSourceAnalyticsService service = serviceResponding("{\"columnHeaders":[{\"name\":\"insightTrafficSourceType\"},{\"name\":\"views\"}],\"rows\":[]}");

        TrafficSourceAnalyticsResult result = service.getVideoTrafficSources(
                "laQbWAoa3NI", "2026-07-27", "2026-08-29", List.of("views"));

        assertThat(result.getSources()).isEmpty();
    }

    private YouTubeTrafficSourceAnalyticsService serviceResponding() {
        return serviceResponding("""
                {"columnHeaders":[
                  {"name":"insightTrafficSourceType","columnType":"DIMENSION","dataType":"STRING"},
                  {"name":"views","columnType":"METRIC","dataType":"INTEGER"},
                  {"name":"estimatedMinutesWatched","columnType":"METRIC","dataType":"INTEGER"}
                ],"rows":[
                  ["YT_SEARCH",20,100],
                  ["SHORTS",15,80],
                  ["EXT_URL",5,20]
                ]}
                """);
    }

    private YouTubeTrafficSourceAnalyticsService serviceResponding(String body) {
        ExchangeFunction exchange = request -> {
            requestUri.set(request.url().toString());
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .body(body)
                    .build());
        };
        YouTubeTrafficSourceAnalyticsService service = new YouTubeTrafficSourceAnalyticsService(
                WebClient.builder().exchangeFunction(exchange).build(), dataService);
        ReflectionTestUtils.setField(service, "defaultMetrics", "views");
        return service;
    }
}
