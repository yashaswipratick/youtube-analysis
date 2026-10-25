package com.youtube.analytics.service;

import com.youtube.analytics.model.VideoPublicStatistics;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class YouTubeDataServiceTest {

    @Test
    void paginatesUploadsPlaylistPublishDatesAndCachesTheResult() {
        AtomicInteger calls = new AtomicInteger();
        List<String> requestedUrls = new ArrayList<>();
        ExchangeFunction exchange = request -> {
            calls.incrementAndGet();
            String uri = request.url().toString();
            requestedUrls.add(uri);
            String body;
            if (uri.contains("/channels?")) {
                body = """
                        {"items":[{"contentDetails":{"relatedPlaylists":{"uploads":"UUabcdef123456789"}}}]}
                        """;
            } else if (uri.contains("pageToken=next-page")) {
                body = """
                        {"items":[{"contentDetails":{"videoId":"sMMJS_Hp8KI",
                          "videoPublishedAt":"2026-10-05T12:00:00Z"}}]}
                        """;
            } else {
                body = """
                        {"items":[
                          {"contentDetails":{"videoId":"video000001","videoPublishedAt":"2026-10-01T12:00:00Z"}},
                          {"contentDetails":{"videoId":"video000002","videoPublishedAt":"2026-10-03T12:00:00Z"}}
                        ],"nextPageToken":"next-page"}
                        """;
            }
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .body(body)
                    .build());
        };
        YouTubeDataService service = new YouTubeDataService(
                WebClient.builder().exchangeFunction(exchange).build());

        Map<String, String> first = service.getAllUploadedVideoPublishDates();
        Map<String, String> second = service.getAllUploadedVideoPublishDates();

        assertThat(first).containsEntry("video000001", "2026-10-01T12:00:00Z")
                .containsEntry("video000002", "2026-10-03T12:00:00Z")
                .containsEntry("sMMJS_Hp8KI", "2026-10-05T12:00:00Z")
                .hasSize(3);
        assertThat(second).isEqualTo(first);
        assertThat(calls.get()).isEqualTo(3);
        assertThat(requestedUrls).anySatisfy(url -> assertThat(url)
                .contains("youtube/v3/playlistItems").contains("maxResults=50"));
        assertThat(requestedUrls).anySatisfy(url -> assertThat(url).contains("pageToken=next-page"));
    }

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
