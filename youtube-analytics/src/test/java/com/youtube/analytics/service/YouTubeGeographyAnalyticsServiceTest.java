package com.youtube.analytics.service;

import com.youtube.analytics.model.GeographyAnalyticsResult;
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
class YouTubeGeographyAnalyticsServiceTest {

    @Mock
    private YouTubeDataService dataService;

    @Test
    void parsesCountryRowsAndMetrics() {
        when(dataService.getVideoMeta(List.of("laQbWAoa3NI"))).thenReturn(Map.of(
                "laQbWAoa3NI", new VideoMeta("laQbWAoa3NI", "Weekend Trip", "2026-07-27T13:00:17Z")));

        AtomicReference<String> requestUri = new AtomicReference<>();
        ExchangeFunction exchange = request -> {
            requestUri.set(request.url().toString());
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .body("""
                            {"columnHeaders":[
                              {"name":"country","columnType":"DIMENSION","dataType":"STRING"},
                              {"name":"views","columnType":"METRIC","dataType":"INTEGER"},
                              {"name":"likes","columnType":"METRIC","dataType":"INTEGER"}
                            ],"rows":[
                              ["IN",50,4],
                              ["US",5,1]
                            ]}
                            """)
                    .build());
        };

        YouTubeGeographyAnalyticsService service = new YouTubeGeographyAnalyticsService(
                WebClient.builder().exchangeFunction(exchange).build(), dataService);
        ReflectionTestUtils.setField(service, "defaultMetrics", "views");

        GeographyAnalyticsResult result = service.getVideoGeography(
                "laQbWAoa3NI", "2026-07-27", "2026-08-29", List.of("views", "likes"));

        assertThat(result.getVideoId()).isEqualTo("laQbWAoa3NI");
        assertThat(result.getCountries()).hasSize(2);
        assertThat(result.getCountries().get(0).getCountry()).isEqualTo("IN");
        assertThat(result.getCountries().get(0).getMetrics())
                .containsEntry("views", 50)
                .containsEntry("likes", 4);
        assertThat(result.getCountries().get(1).getCountry()).isEqualTo("US");
        assertThat(requestUri.get())
                .contains("dimensions=country")
                .contains("filters=video==laQbWAoa3NI")
                .contains("metrics=views,likes");
    }

    @Test
    void handlesEmptyRows() {
        when(dataService.getVideoMeta(List.of("laQbWAoa3NI"))).thenReturn(Map.of(
                "laQbWAoa3NI", new VideoMeta("laQbWAoa3NI", "Weekend Trip", "2026-07-27T13:00:17Z")));

        ExchangeFunction exchange = request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body("{\"columnHeaders\":[{\"name\":\"country\"},{\"name\":\"views\"}],\"rows\":[]}")
                .build());

        YouTubeGeographyAnalyticsService service = new YouTubeGeographyAnalyticsService(
                WebClient.builder().exchangeFunction(exchange).build(), dataService);
        ReflectionTestUtils.setField(service, "defaultMetrics", "views");

        GeographyAnalyticsResult result = service.getVideoGeography(
                "laQbWAoa3NI", "2026-07-27", "2026-08-29", List.of("views"));

        assertThat(result.getCountries()).isEmpty();
    }
}
