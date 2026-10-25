package com.youtube.analytics.service;

import com.youtube.analytics.model.VideoMeta;
import com.youtube.analytics.model.VideoPublicStatistics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
    private static final Duration UPLOAD_DATES_CACHE_TTL = Duration.ofMinutes(30);

    private final WebClient youTubeWebClient;
    private volatile UploadDatesSnapshot uploadDatesSnapshot;

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
     * Returns publish timestamps from the authenticated channel's uploads playlist.
     * Playlist items are read in pages of 50. The result is cached in-memory for 30 minutes
     * because the upload list changes far less often than analytics queries are repeated.
     */
    public Map<String, String> getAllUploadedVideoPublishDates() {
        UploadDatesSnapshot cached = uploadDatesSnapshot;
        Instant now = Instant.now();
        if (cached != null && now.isBefore(cached.expiresAt())) return cached.publishDates();

        synchronized (this) {
            cached = uploadDatesSnapshot;
            now = Instant.now();
            if (cached != null && now.isBefore(cached.expiresAt())) return cached.publishDates();

            Map<String, String> dates = new LinkedHashMap<>();
            String playlistId = getUploadsPlaylistId();
            String pageToken = null;
            do {
                UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(YT_DATA_BASE + "/playlistItems")
                        .queryParam("part", "contentDetails")
                        .queryParam("maxResults", 50)
                        .queryParam("playlistId", playlistId);
                if (pageToken != null && !pageToken.isBlank()) builder.queryParam("pageToken", pageToken);
                Map<?, ?> response = youTubeWebClient.get().uri(builder.build().encode().toUri())
                        .retrieve().bodyToMono(Map.class).block();
                if (response == null) break;

                Object itemsObject = response.get("items");
                if (itemsObject instanceof List<?> items) {
                    for (Object itemObject : items) {
                        if (!(itemObject instanceof Map<?, ?> item)) continue;
                        Object detailsObject = item.get("contentDetails");
                        if (!(detailsObject instanceof Map<?, ?> details)) continue;
                        Object idObject = details.get("videoId");
                        Object publishedObject = details.get("videoPublishedAt");
                        if (idObject != null && publishedObject != null
                                && !String.valueOf(publishedObject).isBlank()) {
                            dates.put(String.valueOf(idObject), String.valueOf(publishedObject));
                        }
                    }
                }
                Object token = response.get("nextPageToken");
                pageToken = token == null ? null : String.valueOf(token);
            } while (pageToken != null && !pageToken.isBlank());

            Map<String, String> immutableDates = Map.copyOf(dates);
            uploadDatesSnapshot = new UploadDatesSnapshot(Instant.now().plus(UPLOAD_DATES_CACHE_TTL), immutableDates);
            log.info("Cached publish dates for {} uploads for {} minutes",
                    immutableDates.size(), UPLOAD_DATES_CACHE_TTL.toMinutes());
            return immutableDates;
        }
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

    /**
     * Fetches current, non-date-filtered public counters through videos.list.
     * Google recommends this Data API endpoint for current view/like/comment
     * counts because YouTube Analytics API reports can lag by 48–72 hours.
     */
    public Map<String, VideoPublicStatistics> getVideoStatistics(List<String> videoIds) {
        Map<String, VideoPublicStatistics> result = new HashMap<>();
        if (videoIds == null || videoIds.isEmpty()) return result;

        List<List<String>> batches = partition(videoIds, 50);
        for (List<String> batch : batches) {
            String ids = String.join(",", batch);
            String url = YT_DATA_BASE + "/videos?part=statistics&id=" + ids;
            Map<?, ?> response = youTubeWebClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (response == null) continue;
            List<?> items = (List<?>) response.get("items");
            if (items == null) continue;
            for (Object item : items) {
                if (!(item instanceof Map<?, ?> videoMap)) continue;
                String videoId = String.valueOf(videoMap.get("id"));
                Object statisticsObject = videoMap.get("statistics");
                if (!(statisticsObject instanceof Map<?, ?> statistics)) continue;
                result.put(videoId, new VideoPublicStatistics(
                        videoId,
                        parseOptionalLong(statistics.get("viewCount")),
                        parseOptionalLong(statistics.get("likeCount")),
                        parseOptionalLong(statistics.get("commentCount"))));
            }
        }
        return result;
    }

    private record UploadDatesSnapshot(Instant expiresAt, Map<String, String> publishDates) {
    }

    private Long parseOptionalLong(Object value) {
        if (value == null) return null;
        try {
            return value instanceof Number number ? number.longValue() : Long.parseLong(value.toString());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            partitions.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return partitions;
    }
}
