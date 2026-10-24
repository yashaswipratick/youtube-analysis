package com.youtube.analytics.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class YouTubePublicApiServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void resolvesYouTubeShortUrlLocallyWithoutAnUpstreamCall() {
        AtomicReference<String> calledUrl = new AtomicReference<>();
        ExchangeFunction exchange = request -> {
            calledUrl.set(request.url().toString());
            return Mono.just(ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR).build());
        };
        YouTubePublicApiService service = new YouTubePublicApiService(
                WebClient.builder().exchangeFunction(exchange).build(),
                WebClient.builder().exchangeFunction(exchange), mapper);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/youtube-analytics/v1/youtube/utility/resolve");
        when(request.getMethod()).thenReturn("GET");
        when(request.getParameter("url")).thenReturn("https://youtu.be/dQw4w9WgXcQ");

        ResponseEntity<JsonNode> response = service.handle(request, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().path("status").asText()).isEqualTo("success");
        assertThat(response.getBody().path("data").path("videoId").asText()).isEqualTo("dQw4w9WgXcQ");
        assertThat(response.getBody().path("data").path("type").asText()).isEqualTo("video");
        assertThat(calledUrl.get()).isNull();
    }

    @Test
    void videoDetailsIncludeTubeAlfredNormalizedFieldsAndRetainRawGoogleFields() {
        AtomicReference<String> videoRequestUrl = new AtomicReference<>();
        ExchangeFunction exchange = request -> {
            String path = request.url().getPath();
            if (path.endsWith("/videos")) {
                videoRequestUrl.set(request.url().toString());
                return Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .body("""
                                {
                                  "kind":"youtube#videoListResponse",
                                  "etag":"video-etag",
                                  "items":[{
                                    "kind":"youtube#video",
                                    "etag":"item-etag",
                                    "id":"dQw4w9WgXcQ",
                                    "snippet":{
                                      "publishedAt":"2026-10-10T13:00:16Z",
                                      "channelId":"UC1234567890123456789012",
                                      "channelTitle":"Yash Xplore",
                                      "title":"Demo",
                                      "description":"Test description",
                                      "tags":["travel","weekend"],
                                      "thumbnails":{"high":{"url":"https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg"}}
                                    },
                                    "contentDetails":{"duration":"PT7M3S"},
                                    "statistics":{"viewCount":"128","likeCount":"4","commentCount":"2"},
                                    "futureField":17
                                  }],
                                  "pageInfo":{"totalResults":1,"resultsPerPage":1}
                                }
                                """)
                        .build());
            }
            if (path.endsWith("/channels")) {
                return Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .body("""
                                {"items":[{"id":"UC1234567890123456789012",
                                  "snippet":{"title":"Yash Xplore","customUrl":"@theyashxplore"}}]}
                                """)
                        .build());
            }
            if (path.endsWith("/captions")) {
                return Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .body("{\"items\":[]}")
                        .build());
            }
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .body("{}")
                    .build());
        };
        YouTubePublicApiService service = new YouTubePublicApiService(
                WebClient.builder().exchangeFunction(exchange).build(),
                WebClient.builder().exchangeFunction(exchange), mapper);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/youtube-analytics/v1/youtube/video/dQw4w9WgXcQ");
        when(request.getMethod()).thenReturn("GET");

        ResponseEntity<JsonNode> response = service.handle(request, null);
        JsonNode data = response.getBody().path("data");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(videoRequestUrl.get()).startsWith("https://www.googleapis.com/youtube/v3/videos?");
        assertThat(response.getBody().path("status").asText()).isEqualTo("success");
        assertThat(data.path("id").asText()).isEqualTo("dQw4w9WgXcQ");
        assertThat(data.path("title").asText()).isEqualTo("Demo");
        assertThat(data.path("description").asText()).isEqualTo("Test description");
        assertThat(data.path("thumbnail").asText()).isEqualTo("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg");
        assertThat(data.path("view_count_int").asLong()).isEqualTo(128L);
        assertThat(data.path("like_count_int").asLong()).isEqualTo(4L);
        assertThat(data.path("comment_count_int").asLong()).isEqualTo(2L);
        assertThat(data.path("duration_ms").asLong()).isEqualTo(423000L);
        assertThat(data.path("duration_formatted").asText()).isEqualTo("7:03");
        assertThat(data.path("keywords").toString()).isEqualTo("[\"travel\",\"weekend\"]");
        assertThat(data.path("channel").path("id").asText()).isEqualTo("UC1234567890123456789012");
        assertThat(data.path("channel").path("url").asText()).isEqualTo("https://www.youtube.com/channel/UC1234567890123456789012");
        assertThat(data.path("channel").path("handle").asText()).isEqualTo("@theyashxplore");
        assertThat(data.path("channel").path("title").asText()).isEqualTo("Yash Xplore");
        assertThat(data.path("transcript").isArray()).isTrue();
        assertThat(data.path("transcript_only_text").asText()).isEmpty();
        assertThat(data.path("watch_next_videos").isArray()).isTrue();
        assertThat(data.at("/items/0/futureField").asInt()).isEqualTo(17);
        assertThat(data.at("/items/0/snippet/title").asText()).isEqualTo("Demo");
        assertThat(data.path("pageInfo").path("totalResults").asInt()).isEqualTo(1);
    }

    @Test
    void returnsExplicitUnsupportedResponseForCommunityPosts() {
        YouTubePublicApiService service = new YouTubePublicApiService(
                WebClient.builder().build(), WebClient.builder(), mapper);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/youtube-analytics/v1/youtube/channel/UC123456789012345678/community");
        when(request.getMethod()).thenReturn("GET");

        ResponseEntity<JsonNode> response = service.handle(request, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_IMPLEMENTED);
        assertThat(response.getBody().path("code").asText()).isEqualTo("UNSUPPORTED_BY_YOUTUBE_DATA_API");
        assertThat(response.getBody().path("message").asText()).contains("Community posts");
    }
}
