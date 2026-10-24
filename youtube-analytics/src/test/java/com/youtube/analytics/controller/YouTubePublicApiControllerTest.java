package com.youtube.analytics.controller;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class YouTubePublicApiControllerTest {

    @Test
    void exposesEveryDocumentedOperationUnderOneCanonicalPrefix() {
        RequestMapping baseMapping = YouTubePublicApiController.class.getAnnotation(RequestMapping.class);
        assertThat(baseMapping).isNotNull();
        assertThat(baseMapping.value()).containsExactly("/api/youtube-analytics");
        List<String> getRoutes = Arrays.stream(YouTubePublicApiController.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(GetMapping.class))
                .filter(mapping -> mapping != null)
                .flatMap(mapping -> Arrays.stream(mapping.value()))
                .toList();
        List<String> postRoutes = Arrays.stream(YouTubePublicApiController.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(PostMapping.class))
                .filter(mapping -> mapping != null)
                .flatMap(mapping -> Arrays.stream(mapping.value()))
                .toList();

        assertThat(getRoutes.size() + postRoutes.size()).isEqualTo(34);
        assertThat(getRoutes).contains(
                "/v1/youtube/video/{video_id}",
                "/v1/youtube/video/{video_id}/enhanced",
                "/v1/youtube/channel/{channel_id}/about",
                "/v1/youtube/search/suggestions",
                "/v1/youtube/playlist/{playlist_id}/metadata",
                "/v1/youtube/utility/resolve",
                "/v1/youtube/trending/shorts");
        assertThat(postRoutes).contains(
                "/v1/youtube/video/{video_id}/comments/page",
                "/v1/youtube/channel/{channel_id}/community/page",
                "/v1/youtube/search/page",
                "/v1/youtube/search/hashtag/page",
                "/v1/youtube/playlist/{playlist_id}/page",
                "/v1/youtube/videos:batch",
                "/v1/youtube/channels:batch");
    }
}
