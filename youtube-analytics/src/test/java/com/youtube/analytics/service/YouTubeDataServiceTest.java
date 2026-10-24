package com.youtube.analytics.service;

import com.youtube.analytics.model.VideoPublicStatistics;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class YouTubeDataServiceTest {

    @Test
    void retrievesCurrentPublicVideoStatisticsFromVideosList() {
        AtomicReference<String> requestedUrl = new AtomicReference<>();
        ExchangeFunction exchange = request -> {
            requestedUrl.set(request.url().toString());
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .body("""
                            {"items":[{"id":"sMMJS_Hp8KI","statistics":{
                              "viewCount":"131","likeCount":"4","commentCount":"0"
                            }}]}
                            """)
                    .build());
        };
        YouTubeDataService service = new YouTubeDataService(
                WebClient.builder().exchangeFunction(exchange).build());

        Map<String, VideoPublicStatistics> result =
                service.getVideoStatistics(List.of("sMMJS_Hp8KI"));

        assertThat(requestedUrl.get())
                .contains("youtube/v3/videos?part=statistics&id=sMMJS_Hp8KI");
        assertThat(result.get("sMMJS_Hp8KI"))
                .isEqualTo(new VideoPublicStatistics("sMMJS_Hp8KI", 131L, 4L, 0L));
    }
}
