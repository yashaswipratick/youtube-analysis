package com.youtube.analytics.service;

import com.youtube.analytics.model.VideoMeta;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Calls the YouTube Data API v3 to:
 *  - find the channel uploads playlist
 *  - paginate through all uploaded video IDs
 *  - fetch video titles and publish dates
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class YouTubeDataService {

    private static final String YT_DATA_BASE = "https://www.googleapis.com/youtube/v3";

    private final WebClient youTubeWebClient;

    /**
     * Returns the uploads playlist ID for the authenticated user.
     */
    public String getUploadsPlaylistId() {
        log.debug("Fetching channel uploads playlist ID");

        Map<?, ?> response = youTubeWebClient.get()
                .uri(YT_DATA_BASE + "/channels?part=contentDetails&mine=true")
                .retrieve()
                .bodyToMono(Map.class)
                .block();

        List<?> items = (List<?>) response.get("items");
        if (items == null || items.isEmpty()) {
            throw new IllegalStateException("No YouTube channel found for the authenticated user.");
        }

        Map<?, ?> channel = (Map<?, ?>) items.get(0);
        Map<?, ?> contentDetails = (Map<?, ?>) channel.get("contentDetails");
        Map<?, ?> relatedPlaylists = (Map<?, ?>) contentDetails.get("relatedPlaylists");
        String playlistId = (String) relatedPlaylists.get("uploads");

        log.info("Uploads playlist ID: {}", playlistId);
        return playlistId;
    }

    /**
     * Paginates through the uploads playlist and returns all video IDs.
     */
    public List<String> getAllVideoIds() {
        String playlistId = getUploadsPlaylistId();
        List<String> videoIds = new ArrayList<>();
        String nextPageToken = null;

        log.info("Paginating uploads playlist...");

        do {
            String url = YT_DATA_BASE + "/playlistItems?part=contentDetails&maxResults=50&playlistId=" + playlistId
                    + (nextPageToken != null ? "&pageToken=" + nextPageToken : "");

            Map<?, ?> response = youTubeWebClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            List<?> items = (List<?>) response.get("items");
            if (items != null) {
                for (Object item : items) {
                    Map<?, ?> contentDetails = (Map<?, ?>) ((Map<?, ?>) item).get("contentDetails");
                    String videoId = (String) contentDetails.get("videoId");
                    videoIds.add(videoId);
                }
            }

            nextPageToken = (String) response.get("nextPageToken");
        } while (nextPageToken != null);

        log.info("Collected {} video IDs", videoIds.size());
        return videoIds;
    }

    /**
     * Fetches title and publishedAt for a list of video IDs.
     * Batches up to 50 IDs per request (YouTube Data API limit).
     *
     * @return map of videoId → VideoMeta
     */
    public Map<String, VideoMeta> getVideoMeta(List<String> videoIds) {
        Map<String, VideoMeta> result = new HashMap<>();
        List<List<String>> batches = partition(videoIds, 50);

        for (List<String> batch : batches) {
            String ids = String.join(",", batch);
            String url = YT_DATA_BASE + "/videos?part=snippet&id=" + ids;

            Map<?, ?> response = youTubeWebClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            List<?> items = (List<?>) response.get("items");
            if (items != null) {
                for (Object item : items) {
                    Map<?, ?> videoMap = (Map<?, ?>) item;
                    String videoId = (String) videoMap.get("id");
                    Map<?, ?> snippet = (Map<?, ?>) videoMap.get("snippet");
                    String title = snippet != null ? (String) snippet.get("title") : videoId;
                    String publishedAt = snippet != null ? (String) snippet.get("publishedAt") : null;
                    result.put(videoId, new VideoMeta(videoId, title, publishedAt));
                }
            }
        }

        log.debug("Fetched metadata for {} videos", result.size());
        return result;
    }

    private <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            partitions.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return partitions;
    }
}
