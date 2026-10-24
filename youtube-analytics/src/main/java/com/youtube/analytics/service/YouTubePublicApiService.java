package com.youtube.analytics.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Local compatibility implementation for the TubeAlfred-style public YouTube API contract.
 * All video/channel/playlist/search/comment data calls use the Google YouTube Data API v3
 * with the signed-in user's OAuth access token. No TubeAlfred service or paid API key is used.
 */
@Slf4j
@Service
public class YouTubePublicApiService {
    private static final List<String> PREFIXES = List.of("/api/youtube-analytics");
    private static final String GOOGLE_BASE = "https://www.googleapis.com/youtube/v3";
    private static final int MAX_RESULTS = 50;
    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");
    private static final Pattern ISO_DURATION = Pattern.compile("PT(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?");
    private static final Pattern CAPTION_TIMECODE = Pattern.compile("(?:(\\d{2}):)?(\\d{2}):(\\d{2})[,.](\\d{3})\\s+-->\\s+(?:(\\d{2}):)?(\\d{2}):(\\d{2})[,.](\\d{3})");

    private final WebClient youTubeWebClient;
    private final WebClient publicWebClient;
    private final ObjectMapper objectMapper;

    public YouTubePublicApiService(
            @Qualifier("youTubeWebClient") WebClient youTubeWebClient,
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper) {
        this.youTubeWebClient = youTubeWebClient;
        this.publicWebClient = webClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    /**
     * Returns the same publication timestamp exposed as data.publish_date by
     * GET /api/youtube-analytics/v1/youtube/video/{videoId}. Analytics services
     * call this service method directly instead of making an HTTP loopback call
     * to the controller.
     */
    public String getVideoPublishDate(String videoId) {
        Map<String, String> dates = getVideoPublishDates(List.of(videoId));
        return dates.get(videoId);
    }

    /** Batch equivalent used by analytics reports to avoid one Data API call per video. */
    public Map<String, String> getVideoPublishDates(List<String> videoIds) {
        if (videoIds == null || videoIds.isEmpty()) return Map.of();
        videoIds.forEach(id -> {
            if (id == null || !VIDEO_ID.matcher(id).matches()) {
                throw new IllegalArgumentException("Every videoId must be a valid 11-character YouTube video ID");
            }
        });

        Map<String, String> publishDates = new LinkedHashMap<>();
        for (int start = 0; start < videoIds.size(); start += MAX_RESULTS) {
            List<String> batch = videoIds.subList(start, Math.min(start + MAX_RESULTS, videoIds.size()));
            JsonNode response = google("/videos", params("part", "snippet", "id", String.join(",", batch)));
            JsonNode items = response.path("items");
            if (!items.isArray()) continue;
            for (JsonNode item : items) {
                String id = item.path("id").asText("");
                String publishedAt = item.path("snippet").path("publishedAt").asText("");
                if (!id.isBlank() && !publishedAt.isBlank()) publishDates.put(id, publishedAt);
            }
        }
        return publishDates;
    }

    /** Handles one compatibility endpoint based on the existing route, HTTP method and request variables. */
    public ResponseEntity<JsonNode> handle(HttpServletRequest request, Object requestBody) {
        try {
            String localPath = request.getRequestURI();
            String matchedPrefix = PREFIXES.stream()
                    .filter(prefix -> localPath.equals(prefix) || localPath.startsWith(prefix + "/"))
                    .findFirst()
                    .orElse(null);
            if (matchedPrefix == null) {
                throw new UnsupportedOperationException("Unknown local YouTube API route.");
            }
            String path = UriUtils.decode(localPath.substring(matchedPrefix.length()), StandardCharsets.UTF_8);
            JsonNode body = requestBody == null ? objectMapper.createObjectNode() : objectMapper.valueToTree(requestBody);
            JsonNode data = dispatch(request.getMethod(), path, request, body);
            return ResponseEntity.ok(success(addContinuationAlias(data)));
        } catch (UnsupportedOperationException ex) {
            return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(error(
                    "UNSUPPORTED_BY_YOUTUBE_DATA_API", ex.getMessage(), objectMapper.nullNode()));
        } catch (WebClientResponseException ex) {
            JsonNode googleError = parseJson(ex.getResponseBodyAsString());
            String message = googleMessage(googleError, ex.getStatusText());
            String code = googleReason(googleError);
            log.warn("YouTube Data API rejected request: status={} code={} message={}",
                    ex.getStatusCode().value(), code, message);
            return ResponseEntity.status(ex.getStatusCode())
                    .body(error(code, message, googleError));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(error("INVALID_REQUEST", ex.getMessage(), objectMapper.nullNode()));
        } catch (Exception ex) {
            log.error("Local YouTube API request failed", ex);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(error(
                    "YOUTUBE_API_REQUEST_FAILED",
                    "The YouTube Data API request could not be completed. Check OAuth login, API enablement, and request parameters.",
                    objectMapper.nullNode()));
        }
    }

    private JsonNode dispatch(String method, String path, HttpServletRequest request, JsonNode body) {
        String verb = method.toUpperCase(Locale.ROOT);
        String[] s = path.replaceFirst("^/v1/youtube/?", "").split("/");
        if (path.startsWith("/v1/youtube/video/") && s.length >= 2) {
            String videoId = videoId(s[1]);
            if (s.length == 2 && "GET".equals(verb)) return videoDetails(videoId, query(request, "fields"), false);
            if (s.length == 3 && "enhanced".equals(s[2]) && "GET".equals(verb)) return videoDetails(videoId, query(request, "fields"), true);
            if (s.length == 4 && "transcript".equals(s[2]) && "fast".equals(s[3]) && "GET".equals(verb)) {
                return transcript(videoId, query(request, "language"), query(request, "kind"));
            }
            if (s.length >= 3 && "comments".equals(s[2])) {
                if (s.length == 3 && "GET".equals(verb)) return videoComments(videoId, query(request, "count"), query(request, "sort"), null);
                if (s.length == 4 && "page".equals(s[3]) && "POST".equals(verb)) return videoComments(videoId, body.path("count").isNumber() ? body.path("count").asText() : null, null, body.path("continuation_token").asText());
                if (s.length == 5 && "replies".equals(s[4]) && "GET".equals(verb)) return commentReplies(s[3], query(request, "count"), query(request, "sort"), null);
                if (s.length == 6 && "replies".equals(s[4]) && "page".equals(s[5]) && "POST".equals(verb)) return commentReplies(s[3], body.path("count").isNumber() ? body.path("count").asText() : null, null, body.path("continuation_token").asText());
            }
            if (s.length == 3 && "related".equals(s[2]) && "GET".equals(verb)) return relatedVideos(videoId, query(request, "continuation_token"));
            if (s.length == 4 && "related".equals(s[2]) && "page".equals(s[3]) && "POST".equals(verb)) return relatedVideos(videoId, body.path("continuation_token").asText());
        }

        if (path.startsWith("/v1/youtube/channel/") && s.length >= 2) {
            String channel = s[1];
            if (s.length == 2 && "GET".equals(verb)) return channelDetails(channel, query(request, "fields"), false);
            if (s.length == 3 && "about".equals(s[2]) && "GET".equals(verb)) return channelDetails(channel, query(request, "fields"), true);
            if (s.length >= 3) {
                String kind = s[2];
                String token = "POST".equals(verb) ? body.path("continuation_token").asText(null) : query(request, "continuation_token");
                if (s.length == 3 && "GET".equals(verb) && "videos".equals(kind)) return channelVideos(channel, token);
                if (s.length == 4 && "page".equals(s[3]) && "POST".equals(verb) && "videos".equals(kind)) return channelVideos(channel, token);
                if (s.length == 3 && "GET".equals(verb) && "streams".equals(kind)) return channelStreams(channel, token);
                if (s.length == 4 && "page".equals(s[3]) && "POST".equals(verb) && "streams".equals(kind)) return channelStreams(channel, token);
                if (s.length == 3 && "GET".equals(verb) && "shorts".equals(kind)) return channelShorts(channel, token);
                if (s.length == 4 && "page".equals(s[3]) && "POST".equals(verb) && "shorts".equals(kind)) return channelShorts(channel, token);
                if (s.length == 3 && "GET".equals(verb) && "playlists".equals(kind)) return channelPlaylists(channel, token);
                if (s.length == 4 && "page".equals(s[3]) && "POST".equals(verb) && "playlists".equals(kind)) return channelPlaylists(channel, token);
                if (s.length == 3 && "GET".equals(verb) && "community".equals(kind)) return communityUnavailable();
                if (s.length == 4 && "page".equals(s[3]) && "POST".equals(verb) && "community".equals(kind)) return communityUnavailable();
            }
        }

        if (path.equals("/v1/youtube/search") && "GET".equals(verb)) return search(request, null);
        if (path.equals("/v1/youtube/search/page") && "POST".equals(verb)) return search(request, body);
        if (path.equals("/v1/youtube/search/suggestions") && "GET".equals(verb)) return suggestions(query(request, "q"), query(request, "prev"));
        if (path.equals("/v1/youtube/search/hashtag") && "GET".equals(verb)) return hashtagSearch(query(request, "hashtag"), query(request, "continuation_token"));
        if (path.equals("/v1/youtube/search/hashtag/page") && "POST".equals(verb)) return hashtagSearch(body.path("hashtag").asText(), body.path("continuation_token").asText());
        if (path.equals("/v1/youtube/trending") && "GET".equals(verb)) return trendingVideos(request, false);
        if (path.equals("/v1/youtube/trending/shorts") && "GET".equals(verb)) return trendingVideos(request, true);

        if (path.startsWith("/v1/youtube/playlist/") && s.length >= 2) {
            String playlistId = s[1];
            if (s.length == 3 && "metadata".equals(s[2]) && "GET".equals(verb)) return playlistMetadata(playlistId);
            String token = "POST".equals(verb) ? body.path("continuation_token").asText(null) : query(request, "continuation_token");
            if (s.length == 2 && "GET".equals(verb)) return playlistContents(playlistId, token);
            if (s.length == 3 && "page".equals(s[2]) && "POST".equals(verb)) return playlistContents(playlistId, token);
        }
        if (path.equals("/v1/youtube/utility/resolve") && "GET".equals(verb)) return resolveUrl(query(request, "url"));
        if (path.equals("/v1/youtube/videos:batch") && "POST".equals(verb)) return batchVideos(body, query(request, "fields"));
        if (path.equals("/v1/youtube/channels:batch") && "POST".equals(verb)) return batchChannels(body, query(request, "fields"));

        throw new UnsupportedOperationException("No local handler is registered for " + verb + " " + path);
    }

    /**
     * Captions API is OAuth-protected and is only able to list/download caption tracks
     * for videos the signed-in user is permitted to edit. It is not a public transcript API.
     */
    private JsonNode transcript(String videoId, String language, String kind) {
        JsonNode available = google("/captions", params("part", "snippet", "videoId", videoId));
        JsonNode tracks = available.path("items");
        JsonNode selected = null;
        for (JsonNode track : tracks) {
            String trackLanguage = track.path("snippet").path("language").asText(
                    track.path("snippet").path("languageCode").asText(""));
            String trackKind = track.path("snippet").path("trackKind").asText("standard");
            boolean languageMatches = language == null || language.isBlank()
                    || language.equalsIgnoreCase(trackLanguage)
                    || language.equalsIgnoreCase(track.path("snippet").path("languageCode").asText(""));
            boolean kindMatches = kind == null || kind.isBlank() || "any".equalsIgnoreCase(kind)
                    || ("auto".equalsIgnoreCase(kind) && "ASR".equalsIgnoreCase(trackKind))
                    || ("manual".equalsIgnoreCase(kind) && !"ASR".equalsIgnoreCase(trackKind));
            if (!languageMatches || !kindMatches) continue;
            if (selected == null) selected = track;
            if (language != null && !language.isBlank()) break;
            if ("en".equalsIgnoreCase(trackLanguage) || "en".equalsIgnoreCase(
                    track.path("snippet").path("languageCode").asText(""))) {
                selected = track;
                break;
            }
        }
        if (selected == null) {
            throw new IllegalArgumentException("No matching caption track is accessible for this video. Google only lists caption tracks your OAuth account is authorized to edit; public captions on other channels may not be downloadable through the official API.");
        }
        String captionId = selected.path("id").asText("");
        if (captionId.isBlank()) throw new IllegalArgumentException("Google returned a caption track without an ID");
        URI downloadUri = UriComponentsBuilder.fromUriString(GOOGLE_BASE + "/captions/" + captionId)
                .queryParam("tfmt", "srt")
                .build().encode().toUri();
        String srt = youTubeWebClient.get().uri(downloadUri).retrieve().bodyToMono(String.class).block();
        if (srt == null) srt = "";
        ObjectNode result = objectMapper.createObjectNode();
        result.put("videoId", videoId);
        result.put("captionTrackId", captionId);
        result.put("languageCode", selected.path("snippet").path("language").asText(
                selected.path("snippet").path("languageCode").asText("")));
        result.put("kind", "ASR".equalsIgnoreCase(selected.path("snippet").path("trackKind").asText(""))
                ? "auto" : "manual");
        result.put("transcript", srt);
        result.set("segments", parseCaptionSegments(srt));
        result.put("availability", "OAuth-accessible caption track");
        return result;
    }

    private ArrayNode parseCaptionSegments(String srt) {
        ArrayNode segments = objectMapper.createArrayNode();
        for (String block : srt.split("\\R\\s*\\R")) {
            String[] lines = block.split("\\R");
            int timingLine = -1;
            Matcher timeMatcher = null;
            for (int i = 0; i < lines.length; i++) {
                Matcher candidate = CAPTION_TIMECODE.matcher(lines[i].trim());
                if (candidate.matches()) {
                    timingLine = i;
                    timeMatcher = candidate;
                    break;
                }
            }
            if (timingLine < 0 || timeMatcher == null) continue;
            double start = captionTime(timeMatcher.group(1), timeMatcher.group(2), timeMatcher.group(3), timeMatcher.group(4));
            double end = captionTime(timeMatcher.group(5), timeMatcher.group(6), timeMatcher.group(7), timeMatcher.group(8));
            String text = java.util.Arrays.stream(lines).skip(timingLine + 1L)
                    .map(String::trim).filter(line -> !line.isBlank())
                    .collect(Collectors.joining(" ")).replaceAll("<[^>]+>", "").trim();
            if (text.isBlank()) continue;
            ObjectNode segment = objectMapper.createObjectNode();
            segment.put("start", start);
            segment.put("end", end);
            segment.put("text", text);
            segments.add(segment);
        }
        return segments;
    }

    private double captionTime(String hours, String minutes, String seconds, String millis) {
        return (hours == null ? 0 : Long.parseLong(hours)) * 3600d
                + Long.parseLong(minutes) * 60d
                + Long.parseLong(seconds)
                + Long.parseLong(millis) / 1000d;
    }

    private JsonNode videoDetails(String id, String fields, boolean enhanced) {
        Map<String, String> p = params("part", enhanced
                ? "snippet,contentDetails,statistics,status,player,topicDetails,liveStreamingDetails,recordingDetails,localizations"
                : "snippet,contentDetails,statistics,status,liveStreamingDetails", "id", id);
        JsonNode raw = google("/videos", p);
        ObjectNode result = raw != null && raw.isObject()
                ? ((ObjectNode) raw).deepCopy()
                : objectMapper.createObjectNode();
        JsonNode items = raw == null ? objectMapper.createArrayNode() : raw.path("items");
        if (!items.isArray() || items.isEmpty()) {
            if (fields != null && !fields.isBlank()) return selectTopLevelFields(result, fields);
            return result;
        }

        JsonNode video = items.get(0);
        JsonNode snippet = video.path("snippet");
        JsonNode content = video.path("contentDetails");
        JsonNode statistics = video.path("statistics");
        String videoId = video.path("id").asText(id);
        String channelId = snippet.path("channelId").asText("");
        long durationSeconds = durationSeconds(content.path("duration").asText(""));

        result.put("id", videoId);
        result.put("thumbnail", thumbnailUrl(videoId, snippet.path("thumbnails")));
        result.put("type", "video");
        putText(result, "title", snippet.path("title"));
        putText(result, "description", snippet.path("description"));
        putCount(result, "comment_count_text", "comment_count_int", statistics.path("commentCount"), "comments");
        putCount(result, "like_count_text", "like_count_int", statistics.path("likeCount"), "likes");
        putCount(result, "view_count_text", "view_count_int", statistics.path("viewCount"), "views");
        String publishedAt = snippet.path("publishedAt").asText("");
        if (!publishedAt.isBlank()) {
            result.put("publish_date", publishedAt);
            result.put("publish_date_text", relativePublishText(publishedAt));
        } else {
            result.putNull("publish_date");
            result.put("publish_date_text", "");
        }

        ObjectNode channel = objectMapper.createObjectNode();
        channel.put("id", channelId);
        channel.put("url", channelId.isBlank() ? "" : "https://www.youtube.com/channel/" + channelId);
        channel.put("handle", "");
        channel.put("title", snippet.path("channelTitle").asText(""));
        if (!channelId.isBlank()) {
            try {
                JsonNode channelResponse = google("/channels", params(
                        "part", "snippet", "id", channelId));
                JsonNode channelItems = channelResponse.path("items");
                if (channelItems.isArray() && !channelItems.isEmpty()) {
                    JsonNode channelSnippet = channelItems.get(0).path("snippet");
                    putText(channel, "title", channelSnippet.path("title"));
                    String customUrl = channelSnippet.path("customUrl").asText("");
                    if (!customUrl.isBlank()) {
                        channel.put("handle", customUrl.startsWith("@") ? customUrl : "@" + customUrl);
                    }
                }
            } catch (RuntimeException ex) {
                // Public video details should still be returned if supplemental channel lookup fails.
                log.debug("Could not enrich channel metadata for video {}: {}", videoId, ex.getMessage());
            }
        }
        result.set("channel", channel);

        if (durationSeconds >= 0) {
            result.put("duration_ms", durationSeconds * 1000L);
            result.put("duration_formatted", formatDuration(durationSeconds));
        } else {
            result.putNull("duration_ms");
            result.put("duration_formatted", "");
        }
        ArrayNode keywords = objectMapper.createArrayNode();
        if (snippet.path("tags").isArray()) snippet.path("tags").forEach(keywords::add);
        result.set("keywords", keywords);

        ArrayNode transcript = transcriptIfAvailable(videoId);
        result.set("transcript", transcript);
        String transcriptText = java.util.stream.StreamSupport.stream(transcript.spliterator(), false)
                .map(segment -> segment.path("text").asText(""))
                .filter(text -> !text.isBlank())
                .collect(Collectors.joining(" "));
        result.put("transcript_only_text", transcriptText);

        // Google removed the relatedToVideoId parameter. Keep the documented field present;
        // do not fabricate suggestions or consume additional search quota automatically.
        result.set("watch_next_videos", objectMapper.createArrayNode());
        if (fields != null && !fields.isBlank()) return selectTopLevelFields(result, fields);
        return result;
    }

    private ArrayNode transcriptIfAvailable(String videoId) {
        ArrayNode segments = objectMapper.createArrayNode();
        try {
            JsonNode response = google("/captions", params("part", "snippet", "videoId", videoId));
            JsonNode tracks = response.path("items");
            if (!tracks.isArray() || tracks.isEmpty()) return segments;
            JsonNode selected = null;
            for (JsonNode track : tracks) {
                String language = track.path("snippet").path("language").asText(
                        track.path("snippet").path("languageCode").asText(""));
                if (selected == null) selected = track;
                if ("en".equalsIgnoreCase(language) || language.toLowerCase(Locale.ROOT).startsWith("en-")) {
                    selected = track;
                    break;
                }
            }
            String captionId = selected == null ? "" : selected.path("id").asText("");
            if (captionId.isBlank()) return segments;
            URI downloadUri = UriComponentsBuilder.fromUriString(GOOGLE_BASE + "/captions/" + captionId)
                    .queryParam("tfmt", "srt").build().encode().toUri();
            String srt = youTubeWebClient.get().uri(downloadUri).retrieve().bodyToMono(String.class).block();
            if (srt == null || srt.isBlank()) return segments;
            JsonNode parsed = parseCaptionSegments(srt);
            if (!parsed.isArray()) return segments;
            for (JsonNode original : parsed) {
                ObjectNode normalized = objectMapper.createObjectNode();
                normalized.put("text", original.path("text").asText(""));
                long startMs = Math.round(original.path("start").asDouble(0) * 1000);
                long endMs = Math.round(original.path("end").asDouble(0) * 1000);
                normalized.put("start_ms", String.valueOf(startMs));
                normalized.put("end_ms", String.valueOf(endMs));
                normalized.put("start_time_text", formatDuration(startMs / 1000));
                segments.add(normalized);
            }
        } catch (RuntimeException ex) {
            // YouTube captions.list/download only supports tracks the OAuth account may edit.
            // Transcript access must not make otherwise-valid video metadata fail.
            log.debug("Transcript is not available through the official captions API for {}: {}", videoId, ex.getMessage());
        }
        return segments;
    }

    private void putText(ObjectNode target, String field, JsonNode source) {
        target.put(field, source.isMissingNode() || source.isNull() ? "" : source.asText(""));
    }

    private void putCount(ObjectNode target, String textField, String numberField, JsonNode source, String unit) {
        if (source == null || source.isMissingNode() || source.isNull() || source.asText().isBlank()) {
            target.putNull(numberField);
            target.put(textField, "");
            return;
        }
        long count;
        try {
            count = Long.parseLong(source.asText());
        } catch (NumberFormatException ex) {
            target.putNull(numberField);
            target.put(textField, "");
            return;
        }
        target.put(numberField, count);
        target.put(textField, compactCount(count) + " " + unit);
    }

    private String compactCount(long count) {
        if (count >= 1_000_000_000L) return compactDecimal(count / 1_000_000_000d) + "B";
        if (count >= 1_000_000L) return compactDecimal(count / 1_000_000d) + "M";
        if (count >= 1_000L) return compactDecimal(count / 1_000d) + "K";
        return String.format(Locale.ROOT, "%,d", count);
    }

    private String compactDecimal(double value) {
        String formatted = String.format(Locale.ROOT, "%.1f", value);
        return formatted.endsWith(".0") ? formatted.substring(0, formatted.length() - 2) : formatted;
    }

    private String thumbnailUrl(String videoId, JsonNode thumbnails) {
        for (String size : List.of("maxres", "standard", "high", "medium", "default")) {
            String url = thumbnails.path(size).path("url").asText("");
            if (!url.isBlank()) return url;
        }
        return "https://i.ytimg.com/vi/" + videoId + "/maxresdefault.jpg";
    }

    private String formatDuration(long totalSeconds) {
        if (totalSeconds < 0) return "";
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) return String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds);
        return String.format(Locale.ROOT, "%d:%02d", minutes, seconds);
    }

    private String relativePublishText(String publishedAt) {
        try {
            Instant published = Instant.parse(publishedAt);
            Duration age = Duration.between(published, Instant.now());
            if (age.isNegative()) return "upcoming";
            long days = age.toDays();
            if (days < 1) return "today";
            if (days < 7) return days + (days == 1 ? " day ago" : " days ago");
            if (days < 30) {
                long weeks = days / 7;
                return weeks + (weeks == 1 ? " week ago" : " weeks ago");
            }
            if (days < 365) {
                long months = days / 30;
                return months + (months == 1 ? " month ago" : " months ago");
            }
            long years = days / 365;
            return years + (years == 1 ? " year ago" : " years ago");
        } catch (RuntimeException ex) {
            return publishedAt;
        }
    }

    private ObjectNode selectTopLevelFields(ObjectNode source, String fields) {
        ObjectNode selected = objectMapper.createObjectNode();
        for (String rawField : fields.split(",")) {
            String field = rawField.trim();
            if (field.isBlank()) continue;
            if (source.has(field)) selected.set(field, source.path(field).deepCopy());
        }
        return selected;
    }

    private JsonNode channelDetails(String channel, String fields, boolean about) {
        Map<String, String> p = channelLookup(channel);
        p.put("part", about ? "snippet,brandingSettings,statistics" : "snippet,contentDetails,statistics,brandingSettings,status,topicDetails");
        optional(p, "fields", fields);
        return google("/channels", p);
    }

    private JsonNode videoComments(String videoId, String count, String sort, String token) {
        Map<String, String> p = params("part", "snippet,replies", "videoId", videoId,
                "maxResults", boundedCount(count, 20), "textFormat", "plainText");
        optional(p, "order", "newest".equalsIgnoreCase(sort) ? "time" : "relevance");
        optional(p, "pageToken", token);
        return google("/commentThreads", p);
    }

    private JsonNode commentReplies(String commentId, String count, String sort, String token) {
        Map<String, String> p = params("part", "snippet", "parentId", commentId,
                "maxResults", boundedCount(count, 20), "textFormat", "plainText");
        optional(p, "pageToken", token);
        JsonNode result = google("/comments", p);
        if (sort != null) ((ObjectNode) result).put("sortApplied", "newest".equalsIgnoreCase(sort) ? "unsupported-for-replies" : "default");
        return result;
    }

    private JsonNode relatedVideos(String videoId, String token) {
        JsonNode video = google("/videos", params("part", "snippet", "id", videoId));
        JsonNode items = video.path("items");
        if (!items.isArray() || items.isEmpty()) return objectMapper.createObjectNode().set("items", objectMapper.createArrayNode());
        JsonNode snippet = items.get(0).path("snippet");
        String title = snippet.path("title").asText("");
        String description = snippet.path("description").asText("");
        String tags = "";
        if (snippet.path("tags").isArray()) {
            List<String> tagList = new ArrayList<>();
            snippet.path("tags").forEach(t -> tagList.add(t.asText()));
            tags = String.join(" ", tagList);
        }
        String query = (title + " " + tags + " " + description).replaceAll("[^\\p{L}\\p{N}\\s]", " ");
        query = List.of(query.split("\\s+")).stream().filter(w -> w.length() > 3).limit(8).collect(Collectors.joining(" "));
        Map<String, String> p = params("part", "snippet", "type", "video", "q", query, "maxResults", "25", "order", "relevance");
        optional(p, "pageToken", token);
        JsonNode related = google("/search", p);
        ((ObjectNode) related).put("approximation", "YouTube Data API v3 no longer provides direct relatedToVideoId results. This result is a best-effort search based on the video's title, tags and description.");
        return related;
    }

    private JsonNode channelVideos(String channel, String token) {
        String uploads = uploadsPlaylistId(channel);
        return playlistVideoItems(uploads, token, null);
    }

    private JsonNode channelStreams(String channel, String token) {
        JsonNode page = channelVideos(channel, token);
        ArrayNode filtered = objectMapper.createArrayNode();
        JsonNode items = page.path("items");
        if (items.isArray()) {
            for (JsonNode item : items) {
                if (item.hasNonNull("liveStreamingDetails")
                        || !"none".equalsIgnoreCase(item.path("snippet").path("liveBroadcastContent").asText("none"))) {
                    filtered.add(item);
                }
            }
        }
        ((ObjectNode) page).set("items", filtered);
        ((ObjectNode) page).put("note", "Streams are detected from liveStreamingDetails on the channel's latest uploads; YouTube Data API v3 has no dedicated channel-streams list.");
        return page;
    }

    private JsonNode channelShorts(String channel, String token) {
        JsonNode page = channelVideos(channel, token);
        ArrayNode filtered = objectMapper.createArrayNode();
        JsonNode items = page.path("items");
        if (items.isArray()) {
            for (JsonNode item : items) {
                long seconds = durationSeconds(item.path("contentDetails").path("duration").asText(""));
                if (seconds > 0 && seconds <= 180) filtered.add(item);
            }
        }
        ((ObjectNode) page).set("items", filtered);
        ((ObjectNode) page).put("note", "Best-effort Shorts approximation: filters channel uploads to videos of 3 minutes or less. The official API does not expose a definitive Shorts-tab listing or orientation field.");
        return page;
    }

    private JsonNode channelPlaylists(String channel, String token) {
        String channelId = resolveChannelId(channel);
        Map<String, String> p = params("part", "snippet,contentDetails,status", "channelId", channelId, "maxResults", String.valueOf(MAX_RESULTS));
        optional(p, "pageToken", token);
        return google("/playlists", p);
    }

    private JsonNode communityUnavailable() {
        throw new UnsupportedOperationException("YouTube Data API v3 does not expose public channel Community posts. The legacy channel-bulletin API was deprecated; this local implementation does not scrape private or undocumented endpoints.");
    }

    private JsonNode search(HttpServletRequest request, JsonNode body) {
        String q = body == null ? query(request, "query") : body.path("query").asText();
        String token = body == null ? query(request, "continuation_token") : body.path("continuation_token").asText(null);
        String channel = body == null ? query(request, "channel_id") : body.path("channel_id").asText(null);
        String uploadDate = body == null ? query(request, "upload_date") : body.path("upload_date").asText(null);
        String duration = body == null ? query(request, "duration") : body.path("duration").asText(null);
        String sort = body == null ? query(request, "sort") : body.path("sort").asText(null);
        String type = body == null ? query(request, "type") : body.path("type").asText(null);
        String features = body == null ? query(request, "features") : body.path("features").asText(null);
        boolean live = body == null ? Boolean.parseBoolean(query(request, "live")) : body.path("live").asBoolean(false);
        boolean shorts = body == null ? Boolean.parseBoolean(query(request, "shorts")) : body.path("shorts").asBoolean(false);

        Map<String, String> p = params("part", "snippet", "q", q, "maxResults", String.valueOf(MAX_RESULTS));
        optional(p, "pageToken", token);
        if (channel != null && !channel.isBlank()) p.put("channelId", resolveChannelId(channel));
        mapUploadDate(p, uploadDate);
        mapDuration(p, duration);
        if ("popularity".equalsIgnoreCase(sort)) p.put("order", "viewCount");
        else if (sort != null) p.put("order", "relevance");
        if (type != null && !type.isBlank()) {
            switch (type.toLowerCase(Locale.ROOT)) {
                case "all" -> p.put("type", "video,channel,playlist");
                case "shorts" -> { p.put("type", "video"); p.put("videoDuration", "short"); p.put("q", (q == null ? "" : q) + " shorts"); }
                case "movie" -> { p.put("type", "video"); p.put("videoType", "movie"); }
                case "video", "channel", "playlist" -> p.put("type", type.toLowerCase(Locale.ROOT));
                default -> throw new IllegalArgumentException("type must be all, video, shorts, channel, playlist, or movie");
            }
        }
        if (live) p.put("eventType", "live");
        if (shorts) { p.put("type", "video"); p.put("videoDuration", "short"); }
        List<String> unsupportedFeatures = mapFeatures(p, features);
        JsonNode result = google("/search", p);
        if ("under_three_mins".equalsIgnoreCase(duration) || shorts || "shorts".equalsIgnoreCase(type)) {
            result = enrichSearchVideosAndFilter(result, 180);
        }
        if ("three_to_twenty_mins".equalsIgnoreCase(duration) && result instanceof ObjectNode) {
            ((ObjectNode) result).put("approximation", "The official YouTube API medium duration filter is 4-20 minutes; videos from 3 to 4 minutes may be omitted.");
        }
        if (!unsupportedFeatures.isEmpty() && result instanceof ObjectNode) {
            ((ObjectNode) result).put("unsupportedFeatureFilters", String.join(",", unsupportedFeatures));
            ((ObjectNode) result).put("featureFilterNote", "The official YouTube Data API has no direct equivalent for the listed filters; results were not filtered by those features.");
        }
        return result;
    }

    private JsonNode hashtagSearch(String hashtag, String token) {
        if (hashtag == null || hashtag.isBlank()) throw new IllegalArgumentException("hashtag is required");
        String q = hashtag.startsWith("#") ? hashtag.substring(1) : hashtag;
        Map<String, String> p = params("part", "snippet", "q", "#" + q, "type", "video", "maxResults", String.valueOf(MAX_RESULTS));
        optional(p, "pageToken", token);
        return google("/search", p);
    }

    private JsonNode suggestions(String q, String previous) {
        if (q == null || q.trim().length() < 2) throw new IllegalArgumentException("q must contain at least 2 characters");
        try {
            String url = "https://suggestqueries.google.com/complete/search?client=firefox&ds=yt&q="
                    + URLEncoder.encode(q, StandardCharsets.UTF_8);
            JsonNode response = publicWebClient.get().uri(URI.create(url)).retrieve()
                    .bodyToMono(JsonNode.class).block();
            ArrayNode suggestions = objectMapper.createArrayNode();
            if (response != null && response.size() > 1 && response.get(1).isArray()) {
                response.get(1).forEach(suggestions::add);
            }
            ObjectNode data = objectMapper.createObjectNode();
            data.put("query", q);
            if (previous != null) data.put("previousQuery", previous);
            data.set("suggestions", suggestions);
            data.put("source", "YouTube public autocomplete; no paid API key used");
            return data;
        } catch (WebClientResponseException ex) {
            throw ex;
        }
    }

    private JsonNode trendingVideos(HttpServletRequest request, boolean shortsOnly) {
        String region = query(request, "regionCode");
        if (region == null || region.isBlank()) region = "IN";
        JsonNode trending = google("/videos", params("part", "snippet,contentDetails,statistics,status,liveStreamingDetails",
                "chart", "mostPopular", "regionCode", region, "maxResults", String.valueOf(MAX_RESULTS)));
        if (!shortsOnly) return trending;
        ArrayNode filtered = objectMapper.createArrayNode();
        JsonNode items = trending.path("items");
        if (items.isArray()) {
            for (JsonNode item : items) {
                long duration = durationSeconds(item.path("contentDetails").path("duration").asText(""));
                if (duration > 0 && duration <= 180) filtered.add(item);
            }
        }
        ((ObjectNode) trending).set("items", filtered);
        ((ObjectNode) trending).put("note", "Trending Shorts approximation: most-popular regional videos filtered to 3 minutes or less; the official API has no separate Shorts trending chart.");
        return trending;
    }

    private JsonNode playlistMetadata(String playlistId) {
        return google("/playlists", params("part", "snippet,contentDetails,status", "id", normalizePlaylistId(playlistId)));
    }

    private JsonNode playlistContents(String playlistId, String token) {
        return playlistVideoItems(normalizePlaylistId(playlistId), token, null);
    }

    private JsonNode batchVideos(JsonNode body, String fields) {
        JsonNode idsNode = body.path("ids");
        if (!idsNode.isArray() || idsNode.isEmpty() || idsNode.size() > MAX_RESULTS) {
            throw new IllegalArgumentException("ids must contain 1 to 50 video IDs or URLs");
        }
        List<String> ids = new ArrayList<>();
        idsNode.forEach(node -> ids.add(videoId(node.asText())));
        Map<String, String> p = params("part", "snippet,contentDetails,statistics,status,liveStreamingDetails",
                "id", String.join(",", ids), "maxResults", String.valueOf(MAX_RESULTS));
        optional(p, "fields", fields);
        return google("/videos", p);
    }

    private JsonNode batchChannels(JsonNode body, String fields) {
        JsonNode idsNode = body.path("ids");
        if (!idsNode.isArray() || idsNode.isEmpty() || idsNode.size() > MAX_RESULTS) {
            throw new IllegalArgumentException("ids must contain 1 to 50 channel IDs, handles or usernames");
        }
        List<String> ids = new ArrayList<>();
        List<String> handles = new ArrayList<>();
        for (JsonNode node : idsNode) {
            String value = node.asText();
            Map<String, String> lookup = channelLookup(value);
            if (lookup.containsKey("id")) ids.add(lookup.get("id"));
            else handles.add(value);
        }
        ArrayNode items = objectMapper.createArrayNode();
        if (!ids.isEmpty()) {
            Map<String, String> p = params("part", "snippet,contentDetails,statistics,brandingSettings,status,topicDetails",
                    "id", String.join(",", ids));
            optional(p, "fields", fields);
            JsonNode data = google("/channels", p);
            if (data.path("items").isArray()) data.path("items").forEach(items::add);
        }
        for (String handle : handles) {
            Map<String, String> p = channelLookup(handle);
            p.put("part", "snippet,contentDetails,statistics,brandingSettings,status,topicDetails");
            optional(p, "fields", fields);
            JsonNode data = google("/channels", p);
            if (data.path("items").isArray()) data.path("items").forEach(items::add);
        }
        ObjectNode result = objectMapper.createObjectNode();
        result.put("kind", "youtube#channelListResponse");
        result.set("items", items);
        result.put("requestedCount", idsNode.size());
        return result;
    }

    private JsonNode resolveUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) throw new IllegalArgumentException("url is required");
        String value = rawUrl.trim();
        URI uri;
        try { uri = URI.create(value.contains("://") ? value : "https://" + value); }
        catch (IllegalArgumentException ex) { throw new IllegalArgumentException("url must be a valid YouTube URL"); }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        String path = uri.getPath() == null ? "" : uri.getPath();
        Map<String, String> query = queryMap(uri.getRawQuery());
        ObjectNode result = objectMapper.createObjectNode();
        result.put("inputUrl", value);
        if (host.endsWith("youtu.be")) return resolved(result, "video", path.replaceFirst("^/", ""), null, null);
        if (!host.endsWith("youtube.com") && !host.endsWith("youtube-nocookie.com")) {
            throw new IllegalArgumentException("Only YouTube URLs are supported");
        }
        if ("/watch".equals(path) && query.containsKey("v")) return resolved(result, "video", query.get("v"), null, query.get("list"));
        if (path.startsWith("/shorts/")) return resolved(result, "short", path.substring("/shorts/".length()).split("/")[0], null, query.get("list"));
        if (path.startsWith("/live/")) return resolved(result, "live", path.substring("/live/".length()).split("/")[0], null, query.get("list"));
        if (path.startsWith("/embed/")) return resolved(result, "video", path.substring("/embed/".length()).split("/")[0], null, query.get("list"));
        if (path.startsWith("/playlist") && query.containsKey("list")) return resolved(result, "playlist", null, null, query.get("list"));
        if (path.startsWith("/channel/")) return resolved(result, "channel", null, path.substring("/channel/".length()).split("/")[0], null);
        if (path.startsWith("/user/")) return resolved(result, "channel", null, path.substring("/user/".length()).split("/")[0], null);
        if (path.startsWith("/@")) {
            result.put("type", "channel");
            result.put("handle", path.substring(2).split("/")[0]);
            result.put("canonicalUrl", "https://www.youtube.com/@" + result.path("handle").asText());
            return result;
        }
        throw new IllegalArgumentException("URL type is not recognized. Supported: video/watch, youtu.be, Shorts, live, embed, playlist, channel ID, user, or handle.");
    }

    private ObjectNode resolved(ObjectNode target, String type, String videoId, String channelId, String playlistId) {
        target.put("type", type);
        if (videoId != null && !videoId.isBlank()) target.put("videoId", videoId);
        if (channelId != null && !channelId.isBlank()) target.put("channelId", channelId);
        if (playlistId != null && !playlistId.isBlank()) target.put("playlistId", playlistId);
        if (videoId != null && !videoId.isBlank()) target.put("canonicalUrl", "https://www.youtube.com/watch?v=" + videoId);
        else if (channelId != null && !channelId.isBlank()) target.put("canonicalUrl", "https://www.youtube.com/channel/" + channelId);
        else if (playlistId != null && !playlistId.isBlank()) target.put("canonicalUrl", "https://www.youtube.com/playlist?list=" + playlistId);
        return target;
    }

    private JsonNode playlistVideoItems(String playlistId, String token, String fields) {
        Map<String, String> p = params("part", "snippet,contentDetails,status", "playlistId", playlistId,
                "maxResults", String.valueOf(MAX_RESULTS));
        optional(p, "pageToken", token);
        JsonNode page = google("/playlistItems", p);
        List<String> ids = new ArrayList<>();
        Map<String, JsonNode> playlistEntryByVideoId = new LinkedHashMap<>();
        JsonNode sourceItems = page.path("items");
        if (sourceItems.isArray()) {
            for (JsonNode item : sourceItems) {
                String id = item.path("contentDetails").path("videoId").asText("");
                if (!id.isBlank()) {
                    ids.add(id);
                    playlistEntryByVideoId.put(id, item.deepCopy());
                }
            }
        }
        JsonNode videos = ids.isEmpty()
                ? objectMapper.createObjectNode().set("items", objectMapper.createArrayNode())
                : google("/videos", params("part", "snippet,contentDetails,statistics,status,liveStreamingDetails",
                        "id", String.join(",", ids)));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("kind", "youtube#videoListResponse");
        if (page.has("nextPageToken")) {
            response.set("nextPageToken", page.path("nextPageToken").deepCopy());
            response.set("continuation_token", page.path("nextPageToken").deepCopy());
        }
        if (page.has("pageInfo")) response.set("pageInfo", page.path("pageInfo").deepCopy());
        ArrayNode enrichedItems = objectMapper.createArrayNode();
        if (videos.path("items").isArray()) {
            for (JsonNode video : videos.path("items")) {
                ObjectNode enriched = video.deepCopy();
                String videoId = video.path("id").asText("");
                JsonNode playlistEntry = playlistEntryByVideoId.get(videoId);
                if (playlistEntry != null) enriched.set("playlistItem", playlistEntry.deepCopy());
                enrichedItems.add(enriched);
            }
        }
        response.set("items", enrichedItems);
        response.put("playlistId", playlistId);
        optionalJson(response, "fields", fields);
        return response;
    }

    private String uploadsPlaylistId(String channel) {
        JsonNode channels = google("/channels", merge(channelLookup(channel), params("part", "contentDetails")));
        JsonNode items = channels.path("items");
        if (!items.isArray() || items.isEmpty()) throw new IllegalArgumentException("No channel found for " + channel);
        String uploads = items.get(0).path("contentDetails").path("relatedPlaylists").path("uploads").asText("");
        if (uploads.isBlank()) throw new IllegalArgumentException("Uploads playlist is not available for channel " + channel);
        return uploads;
    }

    private String resolveChannelId(String channel) {
        Map<String, String> p = channelLookup(channel);
        if (p.containsKey("id")) return p.get("id");
        JsonNode data = google("/channels", merge(p, params("part", "id")));
        JsonNode items = data.path("items");
        if (!items.isArray() || items.isEmpty()) throw new IllegalArgumentException("No channel found for " + channel);
        return items.get(0).path("id").asText(channel);
    }

    private Map<String, String> channelLookup(String value) {
        String clean = value == null ? "" : value.trim();
        if (clean.isBlank()) throw new IllegalArgumentException("channel_id is required");
        if (clean.startsWith("https://") || clean.startsWith("http://")) {
            JsonNode resolved = resolveUrl(clean);
            if (resolved.hasNonNull("channelId")) clean = resolved.path("channelId").asText();
            else if (resolved.hasNonNull("handle")) clean = "@" + resolved.path("handle").asText();
            else throw new IllegalArgumentException("The provided URL does not identify a YouTube channel");
        }
        if (clean.startsWith("/channel/")) clean = clean.substring("/channel/".length());
        if (clean.startsWith("@")) return params("forHandle", clean.substring(1));
        if (clean.startsWith("UC") && clean.length() >= 20) return params("id", clean);
        return params("forUsername", clean);
    }

    private String normalizePlaylistId(String value) {
        String clean = value == null ? "" : value.trim();
        if (clean.contains("://")) {
            JsonNode result = resolveUrl(clean);
            clean = result.path("playlistId").asText("");
        }
        if (clean.isBlank()) throw new IllegalArgumentException("playlist_id must be a playlist ID or playlist URL");
        return clean;
    }

    private String videoId(String input) {
        if (input == null || input.isBlank()) throw new IllegalArgumentException("video_id is required");
        String clean = input.trim();
        if (VIDEO_ID.matcher(clean).matches()) return clean;
        JsonNode resolved = resolveUrl(clean);
        String id = resolved.path("videoId").asText("");
        if (!VIDEO_ID.matcher(id).matches()) throw new IllegalArgumentException("video_id must identify an 11-character YouTube video ID");
        return id;
    }

    private JsonNode enrichSearchVideosAndFilter(JsonNode search, long maxDurationSeconds) {
        List<String> ids = new ArrayList<>();
        JsonNode items = search.path("items");
        if (items.isArray()) for (JsonNode item : items) {
            String id = item.path("id").path("videoId").asText("");
            if (!id.isBlank()) ids.add(id);
        }
        if (ids.isEmpty()) return search;
        JsonNode videos = google("/videos", params("part", "snippet,contentDetails,statistics", "id", String.join(",", ids)));
        Map<String, JsonNode> byId = new LinkedHashMap<>();
        if (videos.path("items").isArray()) for (JsonNode item : videos.path("items")) byId.put(item.path("id").asText(), item);
        ArrayNode filtered = objectMapper.createArrayNode();
        for (String id : ids) {
            JsonNode item = byId.get(id);
            if (item == null) continue;
            long duration = durationSeconds(item.path("contentDetails").path("duration").asText(""));
            if (duration > 0 && duration <= maxDurationSeconds) filtered.add(item);
        }
        ObjectNode result = search.deepCopy();
        result.set("items", filtered);
        result.put("note", "Duration filtering uses official video contentDetails after search; official videoDuration=short includes videos shorter than four minutes.");
        return result;
    }

    private JsonNode google(String endpoint, Map<String, String> params) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(GOOGLE_BASE + endpoint);
        params.forEach((key, value) -> { if (value != null && !value.isBlank()) builder.queryParam(key, value); });
        URI uri = builder.build().encode().toUri();
        log.debug("Calling official YouTube Data API v3: {}", endpoint);
        JsonNode response = youTubeWebClient.get().uri(uri).retrieve().bodyToMono(JsonNode.class).block();
        return response == null ? objectMapper.createObjectNode() : response;
    }

    private JsonNode addContinuationAlias(JsonNode input) {
        if (!(input instanceof ObjectNode)) return input;
        ObjectNode copy = input.deepCopy();
        if (copy.hasNonNull("nextPageToken") && !copy.hasNonNull("continuation_token")) {
            copy.set("continuation_token", copy.path("nextPageToken").deepCopy());
        }
        return copy;
    }

    private ObjectNode success(JsonNode data) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("status", "success");
        response.set("data", data == null ? objectMapper.createObjectNode() : data);
        return response;
    }

    private ObjectNode error(String code, String message, JsonNode details) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("status", "error");
        response.put("code", code == null || code.isBlank() ? "YOUTUBE_API_ERROR" : code);
        response.put("message", message == null || message.isBlank() ? "YouTube API request failed" : message);
        response.set("data", objectMapper.nullNode());
        response.set("details", details == null ? objectMapper.nullNode() : details);
        return response;
    }

    private JsonNode parseJson(String text) {
        try { return objectMapper.readTree(text == null || text.isBlank() ? "{}" : text); }
        catch (Exception ignored) { return objectMapper.createObjectNode().put("raw", text == null ? "" : text); }
    }

    private String googleMessage(JsonNode error, String fallback) {
        String message = error.path("error").path("message").asText("");
        if (!message.isBlank()) return message;
        return fallback == null || fallback.isBlank() ? "YouTube Data API request failed" : fallback;
    }

    private String googleReason(JsonNode error) {
        JsonNode errors = error.path("error").path("errors");
        if (errors.isArray() && !errors.isEmpty()) return errors.get(0).path("reason").asText("YOUTUBE_API_ERROR");
        return error.path("error").path("status").asText("YOUTUBE_API_ERROR");
    }

    private Map<String, String> params(String... pairs) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) result.put(pairs[i], pairs[i + 1]);
        return result;
    }

    private Map<String, String> merge(Map<String, String> first, Map<String, String> second) {
        Map<String, String> combined = new LinkedHashMap<>(first);
        combined.putAll(second);
        return combined;
    }

    private void optional(Map<String, String> target, String key, String value) {
        if (value != null && !value.isBlank()) target.put(key, value);
    }

    private String query(HttpServletRequest request, String name) {
        String value = request.getParameter(name);
        return value == null || value.isBlank() ? null : value;
    }

    private void mapUploadDate(Map<String, String> p, String uploadDate) {
        if (uploadDate == null || uploadDate.isBlank() || "all".equalsIgnoreCase(uploadDate)) return;
        Instant now = Instant.now();
        Instant after = switch (uploadDate.toLowerCase(Locale.ROOT)) {
            case "today" -> now.minus(1, ChronoUnit.DAYS);
            case "week" -> now.minus(7, ChronoUnit.DAYS);
            case "month" -> now.minus(30, ChronoUnit.DAYS);
            case "year" -> now.minus(365, ChronoUnit.DAYS);
            default -> throw new IllegalArgumentException("upload_date must be all, today, week, month, or year");
        };
        p.put("publishedAfter", after.toString());
    }

    private void mapDuration(Map<String, String> p, String duration) {
        if (duration == null || duration.isBlank() || "all".equalsIgnoreCase(duration)) return;
        switch (duration.toLowerCase(Locale.ROOT)) {
            case "under_three_mins" -> p.put("videoDuration", "short");
            case "three_to_twenty_mins" -> p.put("videoDuration", "medium");
            case "over_twenty_mins" -> p.put("videoDuration", "long");
            default -> throw new IllegalArgumentException("duration must be all, under_three_mins, three_to_twenty_mins, or over_twenty_mins");
        }
    }

    private List<String> mapFeatures(Map<String, String> p, String features) {
        List<String> unsupported = new ArrayList<>();
        if (features == null || features.isBlank()) return unsupported;
        for (String raw : features.split(",")) {
            switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "hd", "4k" -> p.put("videoDefinition", "high");
                case "subtitles" -> p.put("videoCaption", "closedCaption");
                case "creative_commons" -> p.put("videoLicense", "creativeCommon");
                case "3d" -> p.put("videoDimension", "3d");
                case "live" -> p.put("eventType", "live");
                case "360", "location", "hdr", "vr180", "purchased" -> unsupported.add(raw.trim());
                default -> throw new IllegalArgumentException("Unsupported feature filter: " + raw.trim());
            }
        }
        return unsupported;
    }

    private String boundedCount(String count, int defaultValue) {
        if (count == null || count.isBlank()) return String.valueOf(defaultValue);
        try {
            int value = Integer.parseInt(count);
            if (value < 1 || value > 100) throw new NumberFormatException();
            return String.valueOf(value);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("count must be between 1 and 100");
        }
    }

    private long durationSeconds(String iso) {
        Matcher matcher = ISO_DURATION.matcher(iso);
        if (!matcher.matches()) return -1;
        long hours = matcher.group(1) == null ? 0 : Long.parseLong(matcher.group(1));
        long minutes = matcher.group(2) == null ? 0 : Long.parseLong(matcher.group(2));
        long seconds = matcher.group(3) == null ? 0 : Long.parseLong(matcher.group(3));
        return hours * 3600 + minutes * 60 + seconds;
    }

    private Map<String, String> queryMap(String raw) {
        Map<String, String> result = new LinkedHashMap<>();
        if (raw == null) return result;
        for (String entry : raw.split("&")) {
            String[] pair = entry.split("=", 2);
            result.put(java.net.URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                    pair.length > 1 ? java.net.URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "");
        }
        return result;
    }

    private void optionalJson(ObjectNode target, String key, String value) {
        if (value != null && !value.isBlank()) target.put(key, value);
    }
}