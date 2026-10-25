package com.youtube.analytics.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youtube.analytics.model.TranscriptComparisonRequest;
import com.youtube.analytics.model.VideoAnalyticsResult;
import com.youtube.analytics.service.businesslogic.AnalyticsWorkspaceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Collects public video metadata, available transcripts and owner-safe performance signals,
 * then asks the configured AI model for a structured content comparison.
 * Transcript text is processed in memory and temporary subtitle files are deleted after use.
 */
@Slf4j
@Service
public class TranscriptComparisonService {
    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");
    private static final Pattern VTT_TIMECODE = Pattern.compile(
            "^(?:\\d{1,2}:)?\\d{2}:\\d{2}\\.\\d{3}\\s+-->\\s+(?:\\d{1,2}:)?\\d{2}:\\d{2}\\.\\d{3}.*$");
    private static final int MAX_TRANSCRIPT_CHARS = 60_000;
    private static final Set<String> PERFORMANCE_METRICS = Set.of(
            "views", "estimatedMinutesWatched", "averageViewDuration", "averageViewPercentage",
            "likes", "comments", "shares", "subscribersGained", "subscribersLost");

    private final YouTubePublicApiService publicApiService;
    private final AnalyticsWorkspaceService workspaceService;
    private final OpenAiAnalysisService openAiAnalysisService;
    private final ObjectMapper objectMapper;

    @Value("${transcript.yt-dlp-command:yt-dlp}")
    private String ytDlpCommand;

    public TranscriptComparisonService(YouTubePublicApiService publicApiService,
                                       AnalyticsWorkspaceService workspaceService,
                                       OpenAiAnalysisService openAiAnalysisService,
                                       ObjectMapper objectMapper) {
        this.publicApiService = publicApiService;
        this.workspaceService = workspaceService;
        this.openAiAnalysisService = openAiAnalysisService;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> compare(TranscriptComparisonRequest request) {
        String myVideoId = normalizeVideoId(request.myVideoId());
        String competitorVideoId = normalizeVideoId(request.competitorVideoId());
        if (myVideoId.equals(competitorVideoId)) {
            throw new IllegalArgumentException("Choose two different videos to compare.");
        }
        int days = request.lastNDays() == null ? 28 : request.lastNDays();
        if (days < 1 || days > 365) throw new IllegalArgumentException("lastNDays must be between 1 and 365");

        JsonNode myDetails = publicApiService.getVideoDetails(myVideoId);
        JsonNode competitorDetails = publicApiService.getVideoDetails(competitorVideoId);
        String authorizedChannelId = publicApiService.getAuthorizedChannelId();
        String myChannelId = myDetails.path("channel").path("id").asText("");
        String competitorChannelId = competitorDetails.path("channel").path("id").asText("");
        if (!authorizedChannelId.equals(myChannelId)) {
            throw new IllegalArgumentException("The selected video is not owned by the currently connected YouTube channel.");
        }
        if (authorizedChannelId.equals(competitorChannelId)) {
            throw new IllegalArgumentException("Choose a video from a different channel for the competitor comparison.");
        }
        Map<String, Object> myVideo = videoMetadata(myDetails, myVideoId);
        Map<String, Object> competitorVideo = videoMetadata(competitorDetails, competitorVideoId);

        TranscriptData myTranscript = transcript(request.myTranscript(), myDetails, myVideoId, false);
        TranscriptData competitorTranscript = transcript(request.competitorTranscript(), competitorDetails,
                competitorVideoId, true);

        Map<String, Object> myPerformance = performanceSummary(myVideoId, days);
        Map<String, Object> competitorPerformance = performanceSummary(competitorVideoId, days);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("myVideo", myVideo);
        result.put("competitorVideo", competitorVideo);
        result.put("myTranscript", transcriptMap(myTranscript));
        result.put("competitorTranscript", transcriptMap(competitorTranscript));
        result.put("performance", Map.of(
                "myVideo", myPerformance,
                "competitorVideo", competitorPerformance,
                "reportingWindowDays", days,
                "interpretationNote", "Your video's private metrics are period-filtered when available. Competitor views, likes and comments are public lifetime counters. They are not a like-for-like reporting-window comparison."
        ));
        result.put("transcriptsReady", !myTranscript.text().isBlank() && !competitorTranscript.text().isBlank());
        result.put("analysis", null);
        result.put("analysisError", "");
        result.put("analysisMessage", "");

        if (myTranscript.text().isBlank() || competitorTranscript.text().isBlank()) {
            result.put("analysisMessage", "A transcript could not be retrieved for one or both videos. Paste the missing transcript below and run the comparison again.");
        } else if (request.analyze()) {
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("myVideo", myVideo);
            context.put("competitorVideo", competitorVideo);
            context.put("performance", result.get("performance"));
            context.put("myTranscriptSource", myTranscript.source());
            context.put("competitorTranscriptSource", competitorTranscript.source());
            context.put("myTranscript", myTranscript.text());
            context.put("competitorTranscript", competitorTranscript.text());
            try {
                result.put("analysis", openAiAnalysisService.compareTranscripts(context));
                result.put("analysisMessage", "Compared the supplied transcript text and the available video metadata. Findings about performance are hypotheses, not proven causes.");
            } catch (RuntimeException ex) {
                log.warn("Transcript text was retrieved but AI comparison failed: {}", ex.getMessage());
                result.put("analysisError", safeMessage(ex));
                result.put("analysisMessage", "Both transcripts are available. Fix the AI configuration or retry the analysis; the transcript text remains available below.");
            }
        } else {
            result.put("analysisMessage", "Transcripts are available. Review or edit them below, then run the analysis.");
        }

        result.put("notes", List.of(
                "YouTube's official captions download requires permission to edit a video. Public competitor captions are therefore attempted through the optional local yt-dlp executable; availability depends on public captions and YouTube's current access restrictions.",
                "Transcript gaps mean only that the spoken transcript did not contain matching text. Important context may be visual or conveyed through on-screen text, editing, music or thumbnails.",
                "Competitor retention, impressions, click-through rate, watch time, revenue and audience analytics are private and are not retrieved or inferred."
        ));
        return result;
    }

    public static String normalizeVideoId(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("A YouTube video URL or 11-character video ID is required.");
        String clean = value.trim();
        if (VIDEO_ID.matcher(clean).matches()) return clean;
        try {
            String url = clean.contains("://") ? clean : "https://" + clean;
            URI uri = URI.create(url);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (!(host.equals("youtu.be") || host.endsWith(".youtube.com") || host.equals("youtube.com")
                    || host.equals("www.youtube-nocookie.com") || host.equals("youtube-nocookie.com"))) {
                throw new IllegalArgumentException("URL must point to YouTube.");
            }
            if ("youtu.be".equals(host)) {
                String path = uri.getPath() == null ? "" : uri.getPath().replaceFirst("^/", "");
                if (VIDEO_ID.matcher(path).matches()) return path;
            }
            String query = uri.getRawQuery();
            if (query != null) for (String parameter : query.split("&")) {
                int equals = parameter.indexOf('=');
                if (equals <= 0) continue;
                String key = URLDecoder.decode(parameter.substring(0, equals), StandardCharsets.UTF_8);
                String id = URLDecoder.decode(parameter.substring(equals + 1), StandardCharsets.UTF_8);
                if ("v".equals(key) && VIDEO_ID.matcher(id).matches()) return id;
            }
            String path = uri.getPath() == null ? "" : uri.getPath();
            var matcher = Pattern.compile("/(?:shorts|embed|live|v)/([A-Za-z0-9_-]{11})(?:/|$)").matcher(path + (path.endsWith("/") ? "" : "/"));
            if (matcher.find()) return matcher.group(1);
        } catch (IllegalArgumentException ex) {
            if (ex.getMessage() != null && (ex.getMessage().contains("YouTube") || ex.getMessage().contains("11-character"))) throw ex;
        }
        throw new IllegalArgumentException("Could not extract an 11-character video ID from the YouTube URL.");
    }

    private TranscriptData transcript(String suppliedText, JsonNode details, String videoId, boolean allowPublicFallback) {
        if (suppliedText != null && !suppliedText.isBlank()) {
            return new TranscriptData(limit(suppliedText), "USER_PROVIDED", "Transcript pasted or edited in the comparison form.");
        }
        String official = limit(details.path("transcript_only_text").asText(""));
        if (official.isBlank() && details.path("transcript").isArray()) {
            StringBuilder text = new StringBuilder();
            details.path("transcript").forEach(segment -> {
                String part = segment.path("text").asText("");
                if (!part.isBlank()) {
                    if (!text.isEmpty()) text.append(' ');
                    text.append(part);
                }
            });
            official = limit(text.toString());
        }
        if (!official.isBlank()) {
            return new TranscriptData(official, "YOUTUBE_CAPTIONS_API", "Fetched using the official captions API under the signed-in account's permissions.");
        }
        if (allowPublicFallback) {
            String extracted = fetchWithYtDlp(videoId);
            if (!extracted.isBlank()) {
                return new TranscriptData(limit(extracted), "YT_DLP_PUBLIC_CAPTIONS", "Fetched by the optional local yt-dlp executable from available public subtitles.");
            }
        }
        String reason = allowPublicFallback
                ? "No accessible transcript was found. Public captions may be disabled, or yt-dlp may be missing or blocked."
                : "No transcript was accessible through the signed-in account. Paste the transcript to continue.";
        return new TranscriptData("", "UNAVAILABLE", reason);
    }

    private String fetchWithYtDlp(String videoId) {
        if (ytDlpCommand == null || ytDlpCommand.isBlank()) return "";
        Path tempDirectory = null;
        try {
            tempDirectory = Files.createTempDirectory("youtube-transcript-");
            Path logFile = tempDirectory.resolve("yt-dlp.log");
            List<String> command = new ArrayList<>(List.of(
                    ytDlpCommand, "--ignore-config", "--no-warnings", "--no-call-home", "--no-playlist",
                    "--skip-download", "--write-subs", "--write-auto-subs",
                    "--sub-langs", "en.*,hi.*,en,hi", "--sub-format", "vtt/srt/best",
                    "--output", tempDirectory.resolve("%(id)s.%(ext)s").toString(),
                    "https://www.youtube.com/watch?v=" + videoId
            ));
            Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(logFile.toFile()).start();
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                log.debug("Timed out while fetching public subtitles for {}", videoId);
                return "";
            }
            try (Stream<Path> files = Files.list(tempDirectory)) {
                List<Path> subtitleFiles = files.filter(Files::isRegularFile)
                        .filter(path -> path.toString().toLowerCase(Locale.ROOT).endsWith(".vtt")
                                || path.toString().toLowerCase(Locale.ROOT).endsWith(".srt"))
                        .sorted(Comparator.comparingInt(this::languagePreference).thenComparing(Path::toString))
                        .toList();
                for (Path file : subtitleFiles) {
                    String cleaned = cleanSubtitleFile(Files.readString(file, StandardCharsets.UTF_8));
                    if (!cleaned.isBlank()) return limit(cleaned);
                }
            }
            if (Files.exists(logFile)) log.debug("yt-dlp found no usable subtitle file for {} (exit {}).",
                    videoId, process.exitValue());
        } catch (IOException ex) {
            log.debug("Optional yt-dlp transcript fallback unavailable: {}", ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.debug("Interrupted while fetching public subtitles for {}", videoId);
        } catch (RuntimeException ex) {
            log.debug("Could not parse public subtitles for {}: {}", videoId, ex.getMessage());
        } finally {
            deleteTree(tempDirectory);
        }
        return "";
    }

    private int languagePreference(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.matches(".*\\.(en|en-us|en-orig)\\.(vtt|srt)$")) return 0;
        if (name.matches(".*\\.(hi|hi-orig)\\.(vtt|srt)$")) return 1;
        return 2;
    }

    private String cleanSubtitleFile(String input) {
        StringBuilder result = new StringBuilder();
        String previous = "";
        boolean skipBlock = false;
        for (String rawLine : input.replace("\uFEFF", "").split("\\R")) {
            String line = rawLine.trim();
            if (line.isBlank()) { skipBlock = false; continue; }
            if (line.equalsIgnoreCase("WEBVTT")) continue;
            if (line.startsWith("NOTE") || line.equals("STYLE") || line.equals("REGION")) { skipBlock = true; continue; }
            if (skipBlock) continue;
            if (line.matches("\\d+") || VTT_TIMECODE.matcher(line).matches() || line.matches("^(?:Kind|Language|X-TIMESTAMP-MAP):.*$")) continue;
            line = line.replaceAll("<[^>]*>", " ").replace("&nbsp;", " ").replace("&amp;", "&")
                    .replace("&lt;", "<").replace("&gt;", ">").replaceAll("\\s+", " ").trim();
            if (line.isBlank() || line.equals(previous)) continue;
            if (!result.isEmpty()) result.append(' ');
            result.append(line);
            previous = line;
        }
        return result.toString().trim();
    }

    private Map<String, Object> videoMetadata(JsonNode data, String fallbackId) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("videoId", data.path("id").asText(fallbackId));
        map.put("title", data.path("title").asText(""));
        map.put("channelId", data.path("channel").path("id").asText(""));
        map.put("channelTitle", data.path("channel").path("title").asText(""));
        map.put("publishedAt", data.path("publish_date").asText(""));
        map.put("duration", data.path("duration_formatted").asText(""));
        map.put("durationMs", data.path("duration_ms").isNumber() ? data.path("duration_ms").asLong() : null);
        map.put("description", data.path("description").asText(""));
        map.put("thumbnailUrl", data.path("thumbnail").asText(""));
        map.put("viewCount", data.path("view_count_int").isNumber() ? data.path("view_count_int").asLong() : null);
        map.put("likeCount", data.path("like_count_int").isNumber() ? data.path("like_count_int").asLong() : null);
        map.put("commentCount", data.path("comment_count_int").isNumber() ? data.path("comment_count_int").asLong() : null);
        return map;
    }

    private Map<String, Object> performanceSummary(String videoId, int days) {
        try {
            Map<String, Object> data = workspaceService.video(videoId, days);
            Map<String, Object> result = new LinkedHashMap<>();
            boolean publicOnly = Boolean.TRUE.equals(data.get("publicOnly"));
            result.put("accessLevel", publicOnly ? "PUBLIC_ONLY" : "FULL_ANALYTICS");
            result.put("publicOnly", publicOnly);
            if (publicOnly) {
                Object rawVideo = data.get("video");
                Map<String, Object> video = asMap(rawVideo);
                result.put("publicLifetimeCounters", subset(video, Set.of("viewCount", "likeCount", "commentCount", "publishedAt", "duration", "durationSeconds")));
                result.put("periodMetrics", Map.of());
                result.put("note", "Public lifetime counters only. Competitor watch time, retention, impressions CTR and revenue are not available.");
            } else {
                Object rawAnalytics = data.get("analytics");
                Map<String, Object> metrics = rawAnalytics instanceof VideoAnalyticsResult a
                        ? a.getMetrics() : asMap(asMap(rawAnalytics).get("metrics"));
                result.put("periodMetrics", subset(metrics, PERFORMANCE_METRICS));
                result.put("reach", subset(asMap(data.get("reach")), Set.of("impressions", "impressionsClickThroughRate", "videoThumbnailImpressions", "videoThumbnailImpressionsClickRate")));
                result.put("note", "Metrics are for the selected reporting window; unavailable fields remain absent rather than being treated as zero.");
            }
            return result;
        } catch (RuntimeException ex) {
            log.debug("Video performance summary unavailable for {}: {}", videoId, ex.getMessage());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("accessLevel", "UNAVAILABLE");
            result.put("periodMetrics", Map.of());
            result.put("note", "Performance metrics could not be retrieved for this video. Transcript comparison may still be available.");
            return result;
        }
    }

    private Map<String, Object> subset(Map<String, Object> input, Set<String> fields) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (input != null) input.forEach((key, value) -> {
            if (fields.contains(key) && value != null) result.put(key, value);
        });
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> converted = new LinkedHashMap<>();
            map.forEach((key, item) -> converted.put(String.valueOf(key), item));
            return converted;
        }
        if (value != null && value.getClass().getPackageName().startsWith("com.youtube.analytics.model")) {
            try {
                return objectMapper.convertValue(value, Map.class);
            } catch (IllegalArgumentException ignored) { }
        }
        return Map.of();
    }

    private Map<String, Object> transcriptMap(TranscriptData transcript) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("text", transcript.text());
        map.put("available", !transcript.text().isBlank());
        map.put("source", transcript.source());
        map.put("message", transcript.message());
        return map;
    }

    private String limit(String text) {
        if (text == null) return "";
        String normalized = text.trim();
        return normalized.length() <= MAX_TRANSCRIPT_CHARS ? normalized : normalized.substring(0, MAX_TRANSCRIPT_CHARS);
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) return "The configured AI analysis request failed.";
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    private void deleteTree(Path directory) {
        if (directory == null || !Files.exists(directory)) return;
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); }
                catch (IOException ex) { log.debug("Could not delete temporary transcript file {}", path); }
            });
        } catch (IOException ex) {
            log.debug("Could not clean temporary transcript directory {}", directory);
        }
    }

    private record TranscriptData(String text, String source, String message) { }
}
