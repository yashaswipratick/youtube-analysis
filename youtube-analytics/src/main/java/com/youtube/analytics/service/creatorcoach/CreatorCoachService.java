package com.youtube.analytics.service.creatorcoach;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youtube.analytics.service.OpenAiAnalysisService;
import com.youtube.analytics.service.YouTubePublicApiService;
import com.youtube.analytics.service.businesslogic.AnalyticsWorkspaceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class CreatorCoachService {
    public static final String PROMPT_VERSION = "coach-v1";
    private static final String PROFILE = "creator-profile";
    private static final String MANUAL = "manual-metrics";
    private static final String TRANSCRIPT = "transcript";
    private static final String JOB = "analysis-job";
    private static final String REPORT = "creator-report";
    private static final String REPORT_INTERNAL = "creator-report-internal";
    private static final String PLAN = "video-plan";
    private static final Pattern TIMESTAMP_LINE = Pattern.compile("^\\s*\\[((?:\\d{1,2}:)?\\d{2}:\\d{2})\\]\\s*(.*)$");
    private static final Set<String> MANUAL_REQUIRED = Set.of("thumbnail_impressions", "thumbnail_click_percent",
            "stayed_past_30s_percent", "intro_watched_percent", "thumbnail_description");
    private static final Map<String, String> GLOSSARY = Map.ofEntries(
            Map.entry("Your first 30 seconds", "The opening half-minute. It should give people a clear reason to stay and match what your title promises."),
            Map.entry("Average watch time", "The average number of seconds people watched. It is not the exact second everyone stopped."),
            Map.entry("Thumbnail click percentage", "Of the times YouTube counted a thumbnail being shown, the share that turned into a view. It helps you assess the title and thumbnail together."),
            Map.entry("Audience curve", "A line showing how often different moments in a video were watched compared with its views. A change can suggest a moment to inspect, but cannot prove why it happened."),
            Map.entry("Small sample", "A small number of views. Results may change quickly, so treat them as early hints rather than a verdict."),
            Map.entry("Title promise", "What someone reasonably expects after reading your title and seeing your thumbnail."),
            Map.entry("Story reward", "A discovery, answer, feeling or useful detail that makes the journey worth following.")
    );

    private final CreatorCoachStore store;
    private final AnalyticsWorkspaceService workspaceService;
    private final YouTubePublicApiService publicApiService;
    private final OpenAiAnalysisService openAi;
    private final ObjectMapper mapper;
    private final Executor executor;
    private final OAuth2AuthorizedClientService authorizedClientService;
    private final int dailyAnalysisLimit;
    private final double inputCostPerMillionUsd;
    private final double outputCostPerMillionUsd;

    public CreatorCoachService(CreatorCoachStore store, AnalyticsWorkspaceService workspaceService,
                               YouTubePublicApiService publicApiService, OpenAiAnalysisService openAi,
                               ObjectMapper mapper,
                               @org.springframework.beans.factory.annotation.Qualifier("creatorCoachExecutor") Executor executor,
                               OAuth2AuthorizedClientService authorizedClientService,
                               @Value("${creator-coach.daily-analysis-limit:10}") int dailyAnalysisLimit,
                               @Value("${creator-coach.input-cost-per-million-usd:0}") double inputCostPerMillionUsd,
                               @Value("${creator-coach.output-cost-per-million-usd:0}") double outputCostPerMillionUsd) {
        this.store = store; this.workspaceService = workspaceService; this.publicApiService = publicApiService;
        this.openAi = openAi; this.mapper = mapper; this.executor = executor;
        this.authorizedClientService = authorizedClientService;
        this.dailyAnalysisLimit = Math.max(1, dailyAnalysisLimit);
        this.inputCostPerMillionUsd = Math.max(0, inputCostPerMillionUsd);
        this.outputCostPerMillionUsd = Math.max(0, outputCostPerMillionUsd);
    }

    public Map<String, Object> authStatus(String ownerId, String email, String name) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("authenticated", true); result.put("name", name == null ? "" : name);
        result.put("email", email == null ? "" : email);
        try {
            result.put("connected", true);
            result.put("channelId", publicApiService.getAuthorizedChannelId());
            result.put("reconnectRequired", false); result.put("message", "YouTube is connected.");
        } catch (RuntimeException ex) {
            result.put("connected", false); result.put("channelId", null);
            result.put("reconnectRequired", true);
            result.put("message", "Please reconnect YouTube before loading private channel data.");
            log.warn("YouTube connection check failed for creator {}. Reconnect is required.", safeOwner(ownerId));
        }
        return result;
    }

    public Map<String, Object> getProfile(String ownerId) {
        Map<String, Object> profile = store.get(ownerId, PROFILE, "mine").orElseGet(this::defaultProfile);
        profile.putIfAbsent("output_language", "hinglish");
        return profile;
    }

    public Map<String, Object> saveProfile(String ownerId, Map<String, Object> input) {
        Map<String, Object> profile = defaultProfile();
        if (input != null) profile.putAll(input);
        String language = string(profile.get("output_language"), "hinglish").toLowerCase(Locale.ROOT);
        if (!Set.of("en", "hi", "hinglish").contains(language)) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "output_language must be en, hi or hinglish.");
        }
        profile.put("output_language", language);
        for (String field : List.of("channel_stage", "style", "languages", "goals")) {
            if (profile.get(field) == null) profile.put(field, "");
        }
        if (!(profile.get("must_keep") instanceof List<?>)) profile.put("must_keep", List.of());
        profile.put("must_keep", rows(profile.get("must_keep")).stream().map(String::valueOf)
                .map(String::trim).filter(s -> !s.isBlank()).limit(20).toList());
        profile.put("updatedAt", Instant.now().toString());
        return store.save(ownerId, PROFILE, "mine", profile);
    }

    private Map<String, Object> defaultProfile() {
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("channel_stage", "New / small creator");
        profile.put("style", "Story-driven, on-camera travel vlogs with cinematic, reflective narration");
        profile.put("languages", List.of("Hindi voice-over", "Hinglish on-camera", "Kannada may appear in captions"));
        profile.put("goals", "Build a loyal audience for Bangalore weekend drives, road trips and hidden destinations");
        profile.put("must_keep", List.of("reflective, poetic narration", "cinematic opening", "personal on-camera voice",
                "sensory details and emotional contrast", "the connection between storytelling voice-over and lived on-camera moments"));
        profile.put("output_language", "hinglish");
        return profile;
    }

    public Map<String, Object> videos(String ownerId) {
        Map<String, Object> catalog = workspaceService.ownedVideoCatalog();
        List<Map<String, Object>> videos = new ArrayList<>();
        for (Map<String, Object> item : mapRows(catalog.get("recentVideos"))) {
            Map<String, Object> video = new LinkedHashMap<>(item);
            String videoId = string(video.get("videoId"), "");
            if (videoId.isBlank()) continue;
            Map<String, Object> manual = store.get(ownerId, MANUAL, videoId).orElse(Map.of());
            Map<String, Object> transcript = store.get(ownerId, TRANSCRIPT, videoId).orElse(Map.of());
            LocalDate published = publishDay(video);
            long age = ageDays(published);
            List<String> missing = inputGaps(manual, transcript);
            if (published == null) missing.add("publish_date");
            String reportId = latestReportId(ownerId, videoId);
            String status = published != null && age < 3 ? "TOO_NEW"
                    : published == null || !missing.isEmpty() ? "NEEDS_YOUR_NUMBERS"
                    : reportId != null ? "REPORT_READY" : "READY_TO_CHECK";
            video.put("videoAgeDays", published == null ? null : age);
            video.put("status", status);
            video.put("missingInputs", missing);
            video.put("latestReportId", reportId);
            videos.add(video);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("videos", videos); result.put("sampleSize", videos.size()); result.put("sampleLimit", 50);
        result.put("note", "The current upload catalog is a sample of up to 50 recent uploads. Duration-based Shorts classification is an approximation.");
        return result;
    }

    public Map<String, Object> getVideoInputs(String ownerId, String videoId, Integer lastNDays) {
        JsonNode resource = ownedVideoDetails(videoId);
        Map<String, Object> video = videoFacts(resource);
        Map<String, Object> transcript = getTranscript(ownerId, videoId, resource);
        Map<String, Object> manual = store.get(ownerId, MANUAL, videoId).orElseGet(LinkedHashMap::new);
        Map<String, Object> workspace = safeVideoAnalytics(videoId, lastNDays);
        List<String> missing = inputGaps(manual, transcript);
        LocalDate published = publishDay(video);
        long age = ageDays(published);
        if (published == null) missing.add("publish_date");
        String status = published != null && age < 3 ? "TOO_NEW"
                : published == null || !missing.isEmpty() ? "NEEDS_YOUR_NUMBERS"
                : latestReportId(ownerId, videoId) != null ? "REPORT_READY" : "READY_TO_CHECK";
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("video", video); response.put("videoAgeDays", published == null ? null : age);
        response.put("status", status); response.put("missingInputs", missing); response.put("manualMetrics", manual);
        response.put("transcript", transcript); response.put("analytics", publicAnalytics(preprocessAnalytics(workspace, video, manual)));
        response.put("profile", getProfile(ownerId)); response.put("creatorAnswers", latestCreatorAnswers(ownerId));
        response.put("howToGetMissingInputs", howToGetInputs(missing));
        response.put("optionalStudioInputs", optionalStudioInputs(manual, publicAnalytics(preprocessAnalytics(workspace, video, manual))));
        return response;
    }

    public Map<String, Object> saveManualMetrics(String ownerId, String videoId, Map<String, Object> input) {
        ownedVideoDetails(videoId);
        Map<String, Object> manual = new LinkedHashMap<>(store.get(ownerId, MANUAL, videoId).orElse(Map.of()));
        if (input != null) manual.putAll(input);
        for (String field : List.of("thumbnail_impressions", "thumbnail_click_percent", "stayed_past_30s_percent", "intro_watched_percent")) {
            if (manual.get(field) instanceof String blank && blank.isBlank()) manual.remove(field);
        }
        for (String field : List.of("thumbnail_impressions")) {
            Double value = number(manual.get(field));
            if (manual.get(field) != null && (value == null || value < 0))
                throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, field + " must be a non-negative number.");
        }
        for (String field : List.of("thumbnail_click_percent", "stayed_past_30s_percent", "intro_watched_percent")) {
            Double value = number(manual.get(field));
            if (manual.get(field) != null && (value == null || value < 0 || value > 100))
                throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, field + " must be a number between 0 and 100.");
        }
        manual.put("thumbnail_description", string(manual.get("thumbnail_description"), "").trim());
        manual.put("updatedAt", Instant.now().toString());
        store.save(ownerId, MANUAL, videoId, manual);
        return getVideoInputs(ownerId, videoId, 365);
    }

    public Map<String, Object> getTranscript(String ownerId, String videoId) {
        return getTranscript(ownerId, videoId, ownedVideoDetails(videoId));
    }

    private Map<String, Object> getTranscript(String ownerId, String videoId, JsonNode details) {
        Map<String, Object> saved = store.get(ownerId, TRANSCRIPT, videoId).orElse(null);
        if (saved != null) return saved;
        List<Map<String, Object>> segments = new ArrayList<>();
        JsonNode captions = details.path("transcript");
        if (captions.isArray()) for (JsonNode caption : captions) {
            String text = caption.path("text").asText("").trim();
            if (text.isBlank()) continue;
            Integer seconds = caption.path("start_sec").isNumber() ? caption.path("start_sec").asInt()
                    : caption.path("start_ms").canConvertToLong() ? (int)Math.max(0, caption.path("start_ms").asLong()/1000)
                    : caption.path("start_time_text").isTextual() ? timestampToSeconds(caption.path("start_time_text").asText()) : null;
            Map<String,Object> line = new LinkedHashMap<>(); line.put("start_sec", seconds); line.put("text", text); segments.add(line);
        }
        if (segments.isEmpty()) segments = parseTranscript(details.path("transcript_only_text").asText(""));
        String text = renderTranscript(segments);
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("videoId", videoId); result.put("segments", segments); result.put("text", text);
        result.put("wordCount", wordCount(text)); result.put("usable", wordCount(text) >= 100);
        result.put("source", segments.isEmpty() ? "UNAVAILABLE" : "YOUTUBE_CAPTIONS_API");
        result.put("editedByCreator", false); result.put("updatedAt", Instant.now().toString());
        return segments.isEmpty() ? result : store.save(ownerId, TRANSCRIPT, videoId, result);
    }

    public Map<String, Object> saveTranscript(String ownerId, String videoId, Map<String, Object> input) {
        ownedVideoDetails(videoId);
        String text = string(input == null ? null : input.get("text"), "");
        List<Map<String,Object>> segments;
        if (input != null && input.get("segments") instanceof List<?>) {
            segments = normalizeSegments(mapRows(input.get("segments"))); text = renderTranscriptUnbounded(segments);
        } else segments = parseTranscript(text);
        if (text.length() > 60000) throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Transcript is limited to 60,000 characters.");
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("videoId", videoId); result.put("segments", segments); result.put("text", text);
        result.put("wordCount", wordCount(text)); result.put("usable", wordCount(text) >= 100);
        result.put("source", "CREATOR_EDITED"); result.put("editedByCreator", true);
        result.put("updatedAt", Instant.now().toString());
        result.put("message", wordCount(text) < 100 ? "This transcript is too short for a full story review. Add the complete transcript before running a report." : "");
        return store.save(ownerId, TRANSCRIPT, videoId, result);
    }

    public Map<String, Object> startAnalysis(String ownerId, String videoId, Integer lastNDays) {
        int days = lastNDays == null ? 365 : lastNDays;
        if (days < 1 || days > 365) throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "lastNDays must be between 1 and 365.");
        JsonNode details = ownedVideoDetails(videoId);
        Map<String,Object> video = videoFacts(details);
        LocalDate published = publishDay(video);
        long age = ageDays(published);
        if (published == null) {
            return Map.of("status", "NEEDS_YOUR_NUMBERS", "missingInputs", List.of("publish_date"),
                    "message", "The video's publication date is unavailable, so the system cannot safely check whether it is at least three days old. No report was created.",
                    "reportCreated", false);
        }
        if (age < 3) {
            Map<String,Object> result = new LinkedHashMap<>();
            result.put("status", "TOO_NEW"); result.put("videoAgeDays", age);
            result.put("message", "Wait until the video is at least three days old before creating a report.");
            result.put("reportCreated", false); return result;
        }
        Map<String,Object> manual = store.get(ownerId, MANUAL, videoId).orElse(Map.of());
        Map<String,Object> transcript = getTranscript(ownerId, videoId, details);
        List<String> gaps = inputGaps(manual, transcript);
        if (!gaps.isEmpty()) {
            Map<String,Object> result = new LinkedHashMap<>();
            result.put("status", "NEEDS_YOUR_NUMBERS"); result.put("missingInputs", gaps);
            result.put("howToGetMissingInputs", howToGetInputs(gaps));
            result.put("message", "Fill the missing inputs before running an analysis. The report has not been created.");
            result.put("reportCreated", false); return result;
        }
        if (analysesStartedToday(ownerId) >= dailyAnalysisLimit)
            throw new ResponseStatusException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "Daily analysis limit reached. Try again tomorrow.");
        Map<String,Object> profile = getProfile(ownerId);
        Map<String,Object> analyticsBeforeQueue = preprocessAnalytics(safeVideoAnalytics(videoId, days), video, manual);
        String fingerprint = hashJson(Map.of("videoId", videoId, "transcript", transcript.get("text"),
                "manualMetrics", manual, "analyticsHash", hashJson(analyticsBeforeQueue),
                "profile", profile, "promptVersion", PROMPT_VERSION, "reportingDays", days));
        for (Map<String,Object> existing : store.list(ownerId, JOB)) {
            if (fingerprint.equals(existing.get("inputFingerprint"))
                    && Set.of("QUEUED","READING","CHECKING").contains(string(existing.get("status"), ""))) {
                return Map.of("jobId", existing.get("id"), "status", existing.get("status"), "deduplicated", true);
            }
        }
        String jobId = UUID.randomUUID().toString();
        Map<String,Object> job = new LinkedHashMap<>();
        job.put("id", jobId); job.put("videoId", videoId); job.put("title", video.get("title"));
        job.put("status", "QUEUED"); job.put("inputFingerprint", fingerprint); job.put("promptVersion", PROMPT_VERSION);
        job.put("createdAt", Instant.now().toString()); job.put("updatedAt", Instant.now().toString());
        job.put("progressMessage", "Queued for analysis.");
        store.save(ownerId, JOB, jobId, job);
        executor.execute(() -> executeAnalysis(ownerId, jobId, videoId, days, fingerprint));
        return Map.of("jobId", jobId, "status", "queued", "message", "Your analysis has been queued. Poll the job endpoint for its status.");
    }

    private int analysesStartedToday(String ownerId) {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        int count = 0;
        for (Map<String,Object> job : store.list(ownerId, JOB)) {
            LocalDate date = parseDate(string(job.get("createdAt"), string(job.get("_createdAt"), "")));
            if (date != null && date.equals(today)) count++;
        }
        return count;
    }

    private void executeAnalysis(String ownerId, String jobId, String videoId, int days, String initialFingerprint) {
        Map<String,Object> job = store.get(ownerId, JOB, jobId).orElse(null);
        if (job == null) return;
        try {
            updateJob(ownerId, job, "READING", "Reading your story and collecting available numbers.");
            JsonNode details = ownedVideoDetails(videoId);
            Map<String,Object> video = videoFacts(details);
            Map<String,Object> transcript = getTranscript(ownerId, videoId, details);
            Map<String,Object> manual = store.get(ownerId, MANUAL, videoId).orElse(Map.of());
            Map<String,Object> profile = getProfile(ownerId);
            String transcriptText = string(transcript.get("text"), "");
            if (wordCount(transcriptText) < 100) throw new IllegalStateException("The saved transcript has fewer than about 100 words. Edit it and retry.");
            Map<String,Object> workspace = safeVideoAnalytics(videoId, days);
            Map<String,Object> analytics = preprocessAnalytics(workspace, video, manual);
            video.put("thumbnail_description", manual.get("thumbnail_description"));
            video.put("small_sample", analytics.get("small_sample"));
            List<Map<String,Object>> sourceSegments = normalizeSegments(mapRows(transcript.get("segments")));
            if (sourceSegments.isEmpty()) sourceSegments = parseTranscript(transcriptText);
            String renderedTranscript = renderTranscript(sourceSegments);
            Map<String,Object> history = previousAnalyses(ownerId, videoId, string(video.get("publish_date"), ""), string(video.get("content_format"), "UNKNOWN"));
            List<Map<String,Object>> previous = mapRows(history.get("videos"));
            List<Map<String,Object>> moments = mapRows(analytics.get("audience_moments"));
            Map<String,Object> context = new LinkedHashMap<>();
            context.put("output_language", profile.getOrDefault("output_language","hinglish"));
            context.put("creator_profile", profile);
            context.put("video_facts", video);
            context.put("numbers", analytics);
            context.put("audience_moments", moments);
            context.put("average_watch", analytics.get("average_watch"));
            context.put("previous_videos_enough", Boolean.TRUE.equals(history.get("enough_history")));
            context.put("previous_videos", Boolean.TRUE.equals(history.get("enough_history")) ? previous : List.of());
            context.put("creator_answers", latestCreatorAnswers(ownerId));
            context.put("notes", string(transcript.get("creator_notes"), string(manual.get("creator_notes"), "")));
            context.put("transcript", renderedTranscript);
            context.put("transcript_segments", sourceSegments);
            context.put("transcript_word_count", wordCount(renderedTranscript));
            context.put("transcript_truncated", false);
            context.put("small_sample", analytics.get("small_sample"));
            context.put("has_audience_curve", analytics.get("has_audience_curve"));
            context.put("video_age_days", video.get("video_age_days"));
            context.put("prompt_version", PROMPT_VERSION);
            String inputHash = hashJson(context);
            Map<String,Object> cached = findCachedReport(ownerId, videoId, inputHash);
            if (cached != null) {
                job.put("reportId", cached.get("id")); updateJob(ownerId, job, "DONE", "Matching report reused."); return;
            }

            updateJob(ownerId, job, "CHECKING", "Checking the story, evidence and numbers.");
            Map<String,Object> outcome = openAi.analyzeCreatorStory(context);
            JsonNode ai = mapper.valueToTree(outcome.get("analysis"));
            Map<String,Object> processed = postProcessReport(ai, renderedTranscript, sourceSegments, video, analytics, profile,
                    previous, Boolean.TRUE.equals(history.get("enough_history")), (int)Math.max(0, Math.round(number(video.get("duration_seconds")) == null ? 0 : number(video.get("duration_seconds")))));
            String feedback = postProcessFeedback(processed, profile);
            if (!feedback.isBlank()) {
                Map<String,Object> rewritten = openAi.analyzeCreatorStory(context, feedback);
                outcome = rewritten;
                processed = postProcessReport(mapper.valueToTree(rewritten.get("analysis")), renderedTranscript, sourceSegments,
                        video, analytics, profile, previous, Boolean.TRUE.equals(history.get("enough_history")),
                        (int)Math.max(0, Math.round(number(video.get("duration_seconds")) == null ? 0 : number(video.get("duration_seconds")))));
                if (!postProcessFeedback(processed, profile).isBlank()) {
                    processed.put("postProcessNote", "Some wording could not be automatically simplified; review the suggestions before using them.");
                    scrubJargon(processed);
                }
            }

            String reportId = UUID.randomUUID().toString();
            processed.put("id", reportId); processed.put("videoId", videoId); processed.put("title", video.get("title"));
            processed.put("publish_date", video.get("publish_date")); processed.put("duration_seconds", video.get("duration_seconds"));
            processed.put("content_format", video.get("content_format"));
            processed.put("video_age_days", video.get("video_age_days")); processed.put("small_sample", analytics.get("small_sample"));
            processed.put("number_status", analytics.get("number_status")); processed.put("metrics", publicAnalytics(analytics));
            processed.put("promptVersion", PROMPT_VERSION); processed.put("model", outcome.get("model"));
            processed.put("inputHash", inputHash);
            processed.put("createdAt", Instant.now().toString()); processed.put("fixFirstStatus", "NOT_YET");
            processed.put("remindBeforeNextVideo", false); processed.put("checklistState", Map.of());
            processed.put("creatorAnswers", Map.of()); processed.put("habit_flags", processed.getOrDefault("habits", Map.of()));
            store.save(ownerId, REPORT, reportId, processed);

            Map<String,Object> internal = new LinkedHashMap<>();
            internal.put("reportId", reportId); internal.put("videoId", videoId);
            internal.put("rawResponse", outcome.get("rawResponse")); internal.put("promptVersion", outcome.getOrDefault("promptVersion", PROMPT_VERSION));
            internal.put("model", outcome.get("model")); internal.put("responseId", outcome.get("responseId"));
            internal.put("inputTokens", outcome.get("inputTokens")); internal.put("outputTokens", outcome.get("outputTokens"));
            internal.put("attempt", outcome.get("attempt")); internal.put("inputHash", inputHash);
            internal.put("inputFingerprint", initialFingerprint); internal.put("computedAnalytics", analytics);
            internal.put("analyticsSnapshot", videoAnalyticsSnapshot(workspace));
            internal.put("createdAt", Instant.now().toString());
            Double estimatedCostUsd = estimatedCost(outcome);
            if (estimatedCostUsd != null) internal.put("estimatedCostUsd", estimatedCostUsd);
            store.save(ownerId, REPORT_INTERNAL, reportId, internal);
            log.info("Creator report stored | reportId={} | videoId={} | promptVersion={} | model={} | inputTokens={} | outputTokens={} | estimatedCostUsd={}",
                    reportId, videoId, PROMPT_VERSION, outcome.get("model"), outcome.get("inputTokens"), outcome.get("outputTokens"),
                    estimatedCostUsd == null ? "rate-not-configured" : String.format(Locale.ROOT, "%.6f", estimatedCostUsd));
            job.put("reportId", reportId); updateJob(ownerId, job, "DONE", "Your report is ready.");
        } catch (Exception ex) {
            String message = safeError(ex);
            job.put("error", message); updateJob(ownerId, job, "FAILED", "The report could not be completed. Check the missing inputs or retry.");
            log.warn("Creator analysis failed for video {} job {}: {}", videoId, jobId, message);
        }
    }

    private Double estimatedCost(Map<String, Object> outcome) {
        if (inputCostPerMillionUsd <= 0 && outputCostPerMillionUsd <= 0) return null;
        Double inputTokens = number(outcome.get("inputTokens"));
        Double outputTokens = number(outcome.get("outputTokens"));
        if (inputTokens == null || outputTokens == null) return null;
        return (inputTokens * inputCostPerMillionUsd + outputTokens * outputCostPerMillionUsd) / 1_000_000d;
    }

    private void updateJob(String ownerId, Map<String,Object> job, String status, String message) {
        job.put("status", status); job.put("progressMessage", message); job.put("updatedAt", Instant.now().toString());
        store.save(ownerId, JOB, string(job.get("id"), ""), job);
    }

    public Map<String,Object> job(String ownerId, String jobId) {
        Map<String,Object> result = store.get(ownerId, JOB, jobId)
                .orElseThrow(() -> new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Analysis job not found."));
        result.remove("inputFingerprint");
        result.put("status", string(result.get("status"), "QUEUED").toLowerCase(Locale.ROOT));
        return result;
    }

    public Map<String,Object> latestReport(String ownerId, String videoId) {
        ownedVideoDetails(videoId);
        return findLatestReport(ownerId, videoId);
    }

    public Map<String,Object> report(String ownerId, String reportId) {
        return store.get(ownerId, REPORT, reportId)
                .orElseThrow(() -> new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Report not found."));
    }

    /**
     * Returns only chart-ready points and event markers for the signed-in creator's own report.
     * The raw YouTube Analytics response remains in the internal record and is never sent to the browser.
     */
    public Map<String,Object> audienceCurve(String ownerId, String reportId) {
        report(ownerId, reportId); // Enforce report ownership before reading internal analytics.
        Map<String,Object> internal = store.get(ownerId, REPORT_INTERNAL, reportId)
                .orElse(Map.of());
        Map<String,Object> computed = asMap(internal.get("computedAnalytics"));
        List<Map<String,Object>> source = mapRows(computed.get("audience_curve"));
        List<Map<String,Object>> points = new ArrayList<>();
        if (source.size() > 250) {
            double step = (source.size() - 1d) / 249d;
            for (int i = 0; i < 250; i++) {
                Map<String,Object> point = source.get((int)Math.round(i * step));
                points.add(curveDisplayPoint(point));
            }
        } else {
            for (Map<String,Object> point : source) points.add(curveDisplayPoint(point));
        }
        List<Map<String,Object>> moments = mapRows(computed.get("audience_moments")).stream().map(moment -> {
            Map<String,Object> safe = new LinkedHashMap<>();
            safe.put("id", moment.get("id")); safe.put("type", moment.get("type"));
            safe.put("at_sec", moment.get("at_sec"));
            if (moment.containsKey("lost_percent_points")) safe.put("change_percent_points", moment.get("lost_percent_points"));
            else if (moment.containsKey("rise_percent_points")) safe.put("change_percent_points", moment.get("rise_percent_points"));
            return safe;
        }).toList();
        boolean available = points.size() >= 3;
        Map<String,Object> response = new LinkedHashMap<>();
        response.put("available", available);
        response.put("points", available ? points : List.of());
        response.put("moments", available ? moments : List.of());
        response.put("message", available ? "These are simplified chart points from your own available audience data. A change is a moment to inspect, not proof of why viewers behaved that way."
                : "We need your audience graph from YouTube Studio to show this. Open YouTube Studio → Content → select this video → Analytics → Engagement → Audience retention. The graph may be unavailable for some videos.");
        return response;
    }

    private Map<String,Object> curveDisplayPoint(Map<String,Object> source) {
        Map<String,Object> point = new LinkedHashMap<>();
        point.put("at_sec", integer(source.get("at_sec")));
        Double ratio = number(source.get("audienceWatchRatio"));
        point.put("relative_watch_percent", ratio == null ? null : Math.round(ratio * 1000d) / 10d);
        return point;
    }

    public Map<String,Object> updateFixFirst(String ownerId, String reportId, Map<String,Object> input) {
        Map<String,Object> report = report(ownerId, reportId);
        String status = string(input == null ? null : input.get("status"), "NOT_YET").toUpperCase(Locale.ROOT);
        if (!Set.of("NOT_YET","DONE","SKIPPED").contains(status))
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "status must be NOT_YET, DONE or SKIPPED.");
        report.put("fixFirstStatus", status); report.put("fixFirstStatusUpdatedAt", Instant.now().toString());
        report.put("remindBeforeNextVideo", Boolean.TRUE.equals(input == null ? null : input.get("remindBeforeNextVideo")));
        if ("DONE".equals(status)) report.put("fixFirstDoneAt", Instant.now().toString());
        store.save(ownerId, REPORT, reportId, report);
        return report;
    }

    public Map<String,Object> saveChecklist(String ownerId, String reportId, Map<String,Object> input) {
        Map<String,Object> report = report(ownerId, reportId);
        Map<String,Object> state = asMap(input == null ? null : input.get("checkedItems"));
        report.put("checklistState", state); report.put("checklistUpdatedAt", Instant.now().toString());
        store.save(ownerId, REPORT, reportId, report);
        return Map.of("reportId", reportId, "checkedItems", state, "saved", true);
    }

    public Map<String,Object> saveAnswers(String ownerId, String reportId, Map<String,Object> input) {
        Map<String,Object> report = report(ownerId, reportId);
        Map<String,Object> answers = asMap(input == null ? null : input.get("answers"));
        report.put("creatorAnswers", answers); report.put("answersUpdatedAt", Instant.now().toString());
        store.save(ownerId, REPORT, reportId, report);
        return Map.of("reportId", reportId, "answers", answers, "saved", true);
    }

    public Map<String,Object> progress(String ownerId) {
        List<Map<String,Object>> reports = store.list(ownerId, REPORT).stream()
                .sorted(Comparator.comparing(r -> string(r.get("publish_date"), string(r.get("createdAt"), "")))).toList();
        List<Map<String,Object>> trend = new ArrayList<>();
        for (Map<String,Object> report : reports) {
            Map<String,Object> metrics = asMap(report.get("metrics"));
            Map<String,Object> point = new LinkedHashMap<>();
            point.put("reportId", report.get("id")); point.put("videoId", report.get("videoId"));
            point.put("title", report.get("title")); point.put("publish_date", report.get("publish_date"));
            point.put("views", metrics.get("views")); point.put("average_watch_percent", metrics.get("average_watch_percent"));
            point.put("average_watch_seconds", metrics.get("avg_watch_seconds")); point.put("shares", metrics.get("shares"));
            point.put("habits", report.get("habits")); point.put("fixFirstStatus", report.get("fixFirstStatus"));
            trend.add(point);
        }
        List<Map<String,Object>> outstanding = reports.stream()
                .filter(r -> Boolean.TRUE.equals(r.get("remindBeforeNextVideo"))
                        && !"DONE".equals(r.get("fixFirstStatus")) && !"SKIPPED".equals(r.get("fixFirstStatus")))
                .map(r -> {
                    Map<String,Object> item = new LinkedHashMap<>();
                    item.put("reportId", r.get("id")); item.put("videoId", r.get("videoId")); item.put("title", r.get("title"));
                    item.put("action", asMap(r.get("fix_first")).get("action")); item.put("status", r.get("fixFirstStatus"));
                    return item;
                }).toList();
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("analysedVideoCount", reports.size()); result.put("enoughHistory", reports.size() >= 3);
        result.put("historyNote", reports.size() >= 3
                ? "Patterns are still directional because videos can have different audiences and exposure."
                : "Needs 3 analysed videos before looking for patterns in your own history.");
        result.put("trend", trend); result.put("outstandingReminders", outstanding);
        List<Map<String,Object>> comparisons = new ArrayList<>();
        for (int i = 1; i < reports.size(); i++) {
            Map<String,Object> before = reports.get(i - 1), after = reports.get(i);
            if (!"DONE".equals(before.get("fixFirstStatus"))) continue;
            Map<String,Object> bm = asMap(before.get("metrics")), am = asMap(after.get("metrics"));
            Map<String,Object> comparison = new LinkedHashMap<>();
            comparison.put("beforeReportId", before.get("id")); comparison.put("afterReportId", after.get("id"));
            comparison.put("focusAction", asMap(before.get("fix_first")).get("action"));
            comparison.put("beforeAverageWatchPercent", bm.get("average_watch_percent"));
            comparison.put("afterAverageWatchPercent", am.get("average_watch_percent"));
            comparison.put("interpretation", "Only a hint. View counts, exposure and audiences can differ; this comparison does not prove the completed fix caused a change.");
            comparisons.add(comparison);
        }
        result.put("afterFixComparisons", comparisons);
        return result;
    }

    public Map<String,Object> plans(String ownerId) {
        return Map.of("plans", store.list(ownerId, PLAN),
                "outlineParts", List.of("opening","personal_reason","journey_discoveries","reflection","ending"));
    }

    public Map<String,Object> plan(String ownerId, String planId) {
        return store.get(ownerId, PLAN, planId)
                .orElseThrow(() -> new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Video plan not found."));
    }

    public Map<String,Object> savePlan(String ownerId, Map<String,Object> input, String planId) {
        Map<String,Object> plan = new LinkedHashMap<>();
        if (planId != null) plan.putAll(store.get(ownerId, PLAN, planId)
                .orElseThrow(() -> new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Video plan not found.")));
        if (input != null) plan.putAll(input);
        String id = planId == null ? UUID.randomUUID().toString() : planId;
        plan.put("id", id); plan.putIfAbsent("title", "");
        plan.putIfAbsent("output_language", getProfile(ownerId).get("output_language"));
        Map<String,Object> outline = asMap(plan.get("outline"));
        for (String part : List.of("opening","personal_reason","journey_discoveries","reflection","ending")) outline.putIfAbsent(part, "");
        plan.put("outline", outline); plan.put("updatedAt", Instant.now().toString());
        return store.save(ownerId, PLAN, id, plan);
    }

    public Map<String,Object> duplicatePlan(String ownerId, String planId) {
        Map<String,Object> original = store.get(ownerId, PLAN, planId)
                .orElseThrow(() -> new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Video plan not found."));
        original.remove("_createdAt"); original.remove("_updatedAt"); original.remove("id");
        original.put("title", string(original.get("title"), "") + " (copy)"); original.put("duplicatedFrom", planId);
        return savePlan(ownerId, original, null);
    }

    public Map<String,Object> titleCheck(String ownerId, String planId, Map<String,Object> input) {
        Map<String,Object> plan = store.get(ownerId, PLAN, planId)
                .orElseThrow(() -> new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Video plan not found."));
        String title = string(input == null ? null : input.get("title"), string(plan.get("title"), "")).trim();
        if (title.isBlank()) throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Provide a title to check.");
        Map<String,Object> outline = asMap(plan.get("outline"));
        String firstThirtySeconds = string(input == null ? null : input.get("first_30_seconds"),
                string(input == null ? null : input.get("opening_preview"), string(outline.get("opening"), "")));
        String opening = (string(outline.get("opening"), "") + " " + firstThirtySeconds).toLowerCase(Locale.ROOT);
        Set<String> words = java.util.Arrays.stream(title.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                .filter(w -> w.length() >= 4).filter(w -> !Set.of("with","from","that","this","your","travel","vlog","video").contains(w))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<String> unsupported = words.stream().filter(w -> !opening.contains(w)).toList();
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("title", title);
        result.put("warning", unsupported.isEmpty()
                ? "The title's main words appear in your opening plan. Check that the finished video's first 30 seconds really set up this promise."
                : "Some important title words do not appear in the opening plan. Consider showing the promise early or adjusting the title so it matches your video.");
        result.put("unsupportedTitleWords", unsupported);
        result.put("method", "Simple word-overlap warning based on the outline's opening and your first-30-seconds note; not an AI judgement or performance prediction.");
        return result;
    }

    public Map<String,Object> glossary(String ownerId) {
        List<Map<String,Object>> result = new ArrayList<>();
        Map<String,Object> latest = store.list(ownerId, REPORT).stream().findFirst().orElse(Map.of());
        Map<String,Object> metrics = asMap(latest.get("metrics"));
        Map<String,Object> titleCheck = asMap(latest.get("title_check"));
        Map<String,Object> first30 = asMap(latest.get("first_30_seconds"));
        List<Map<String,Object>> strengths = mapRows(latest.get("keep_doing"));
        List<Map<String,Object>> audienceExplanations = mapRows(latest.get("audience_explanations"));
        String storyQuote = strengths.stream().map(s -> string(s.get("quote"), "")).filter(s -> !s.isBlank()).findFirst().orElse(null);
        for (Map.Entry<String,String> e : GLOSSARY.entrySet()) {
            String example = switch (e.getKey()) {
                case "Your first 30 seconds" -> string(first30.get("explanation"),
                        firstSegmentExample(latest));
                case "Average watch time" -> {
                    Double seconds = number(metrics.get("avg_watch_seconds"));
                    yield seconds == null ? null : "For this video, the average watch time was " + Math.round(seconds) + " seconds. That is an average, not an exact stopping point.";
                }
                case "Thumbnail click percentage" -> {
                    Double clickRate = number(metrics.get("thumbnail_click_percent"));
                    yield clickRate == null ? null : "Your supplied or measured thumbnail click percentage for this video was " + String.format(Locale.ROOT, "%.1f%%", clickRate) + ".";
                }
                case "Audience curve" -> {
                    Map<String,Object> moment = metrics.isEmpty() ? Map.of()
                            : mapRows(metrics.get("audience_moments")).stream().findFirst().orElse(Map.of());
                    String observed = audienceExplanations.stream().map(a -> string(a.get("what_was_happening"), ""))
                            .filter(s -> !s.isBlank()).findFirst().orElse("");
                    if (!observed.isBlank()) yield observed;
                    Integer second = integer(moment.get("at_sec"));
                    yield second == null ? null : "One supplied audience-curve moment was around " + formatTimestamp(second) + ". Check the matching scene before drawing a conclusion.";
                }
                case "Small sample" -> string(latest.get("smallSampleNote"), string(latest.get("early_data_note"), null));
                case "Title promise" -> {
                    String promise = string(titleCheck.get("title_promise"), "");
                    Integer second = integer(titleCheck.get("first_delivered_at_sec"));
                    if (promise.isBlank()) yield null;
                    yield second == null ? "Your video title promised: " + promise
                            : "Your video title promised: " + promise + ". The report marked the first delivery at " + formatTimestamp(second) + ".";
                }
                case "Story reward" -> storyQuote == null ? null : "Your report highlighted this line: “" + storyQuote + "”.";
                default -> null;
            };
            Map<String,Object> item = new LinkedHashMap<>();
            item.put("term", e.getKey());
            item.put("meaning", e.getValue());
            item.put("example_from_your_video", example);
            item.put("example_note", example == null
                    ? "No suitable example is available from your saved reports yet."
                    : "Example is taken from your own saved report or numbers; it does not imply that one moment caused performance.");
            result.add(item);
        }
        return Map.of("entries", result);
    }

    private String firstSegmentExample(Map<String,Object> report) {
        List<Map<String,Object>> segments = mapRows(report.get("segments"));
        return segments.isEmpty() ? null : "Your opening part was labelled “" + string(segments.get(0).get("label"), "opening") + "”.";
    }


    public void deleteCreatorData(String ownerId) {
        store.deleteAllForOwner(ownerId);
        try { authorizedClientService.removeAuthorizedClient("google", ownerId); }
        catch (RuntimeException ignored) { log.warn("Could not remove OAuth authorized client during creator data deletion."); }
    }

    private Map<String,Object> findLatestReport(String ownerId, String videoId) {
        return store.list(ownerId, REPORT).stream()
                .filter(r -> videoId.equals(string(r.get("videoId"), "")))
                .max(Comparator.comparing(r -> string(r.get("createdAt"), "")))
                .orElseThrow(() -> new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "No report exists for this video yet."));
    }

    private String latestReportId(String ownerId, String videoId) {
        return store.list(ownerId, REPORT).stream()
                .filter(r -> videoId.equals(string(r.get("videoId"), "")))
                .max(Comparator.comparing(r -> string(r.get("createdAt"), "")))
                .map(r -> string(r.get("id"), "")).orElse(null);
    }

    private Map<String,Object> findCachedReport(String ownerId, String videoId, String inputHash) {
        return store.list(ownerId, REPORT).stream()
                .filter(r -> videoId.equals(string(r.get("videoId"), "")) && inputHash.equals(r.get("inputHash")))
                .findFirst().orElse(null);
    }

    private List<Map<String,Object>> latestCreatorAnswers(String ownerId) {
        return store.list(ownerId, REPORT).stream().map(r -> {
            Map<String,Object> item = new LinkedHashMap<>();
            item.put("videoId", r.get("videoId")); item.put("title", r.get("title")); item.put("answers", r.get("creatorAnswers"));
            return item;
        }).filter(item -> item.get("answers") instanceof Map<?,?> map && !map.isEmpty()).limit(10).toList();
    }

    private Map<String,Object> previousAnalyses(String ownerId, String currentVideoId, String publishTimestamp, String currentFormat) {
        LocalDate currentDate = parseDate(publishTimestamp);
        Map<String,Map<String,Object>> newestReportByVideo = new LinkedHashMap<>();
        store.list(ownerId, REPORT).stream()
                .filter(r -> !currentVideoId.equals(string(r.get("videoId"), "")))
                .filter(r -> currentFormat.equals(string(r.get("content_format"), "UNKNOWN")))
                .filter(r -> {
                    LocalDate prior = parseDate(string(r.get("publish_date"), ""));
                    return currentDate != null && prior != null && prior.isBefore(currentDate);
                })
                .sorted(Comparator.comparing(r -> string(r.get("createdAt"), ""), Comparator.reverseOrder()))
                .forEach(report -> newestReportByVideo.putIfAbsent(string(report.get("videoId"), ""), report));
        List<Map<String,Object>> history = newestReportByVideo.values().stream()
                .sorted(Comparator.comparing(r -> string(r.get("publish_date"), ""), Comparator.reverseOrder()))
                .toList();
        boolean enough = history.size() >= 3;
        List<Map<String,Object>> previous = new ArrayList<>();
        if (enough) for (Map<String,Object> r : history.stream().limit(3).toList()) {
            Map<String,Object> item = new LinkedHashMap<>();
            item.put("title", r.get("title")); item.put("publish_date", r.get("publish_date"));
            item.put("views", asMap(r.get("metrics")).get("views"));
            item.put("average_view_percent", asMap(r.get("metrics")).get("average_watch_percent"));
            item.put("habits", r.get("habits")); item.put("hook_style", r.get("first_30_seconds"));
            item.put("notes", string(asMap(r.get("fix_first")).get("action"), ""));
            previous.add(item);
        }
        return Map.of("enough_history", enough, "videos", previous);
    }

    private Map<String,Object> preprocessAnalytics(Map<String,Object> workspace, Map<String,Object> video, Map<String,Object> manual) {
        Map<String,Object> metrics = asMap(asMap(workspace.get("analytics")).get("metrics"));
        Map<String,Object> reach = asMap(workspace.get("reach"));
        Map<String,Object> normalized = new LinkedHashMap<>();
        // Sample-size decisions use the selected Analytics reporting window only; a public lifetime counter is not a substitute.
        normalized.put("views", metrics.get("views"));
        normalized.put("avg_watch_seconds", first(metrics.get("averageViewDuration"), metrics.get("averageViewDurationSeconds")));
        normalized.put("average_watch_percent", metrics.get("averageViewPercentage"));
        normalized.put("likes", first(metrics.get("likes"), video.get("likeCount")));
        normalized.put("comments", first(metrics.get("comments"), video.get("commentCount")));
        normalized.put("shares", metrics.get("shares")); normalized.put("new_subscribers", metrics.get("subscribersGained"));
        normalized.put("lost_subscribers", metrics.get("subscribersLost"));
        normalized.put("impressions", first(manual.get("thumbnail_impressions"), reach.get("videoThumbnailImpressions"), reach.get("impressions")));
        normalized.put("thumbnail_click_percent", first(manual.get("thumbnail_click_percent"), reach.get("impressionsClickThroughRate"), reach.get("videoThumbnailImpressionsClickRate")));
        normalized.put("stayed_past_30s_percent", manual.get("stayed_past_30s_percent"));
        normalized.put("intro_watched_percent", manual.get("intro_watched_percent"));
        Double views = number(normalized.get("views"));
        boolean smallSample = views == null || views < 1000;
        normalized.put("small_sample", smallSample); normalized.put("small_sample_known", views != null);
        List<Map<String,Object>> curve = retentionCurve(workspace.get("retention"), number(video.get("duration_seconds")));
        List<Map<String,Object>> moments = curveMoments(curve, number(video.get("duration_seconds")));
        normalized.put("audience_curve", curve); normalized.put("audience_moments", moments);
        normalized.put("has_audience_curve", curve.size() >= 3);
        normalized.put("average_watch", averageWatch(normalized.get("avg_watch_seconds")));
        normalized.put("traffic_share", trafficShare(workspace.get("trafficSources")));
        normalized.put("reporting_window", workspace.get("range"));
        normalized.put("analytics_available", !Boolean.TRUE.equals(workspace.get("publicOnly")) && !metrics.isEmpty());
        normalized.put("number_status", numberStatus(normalized, smallSample));
        normalized.put("early_data_note", smallSample ? (views == null
                ? "The available view count is missing. It is too early to judge these numbers."
                : "Fewer than 1,000 views are available in this reporting window. It is too early to judge these numbers.") : null);
        return normalized;
    }

    /** Whitelists calculated values for the API; the raw minute-by-minute audience curve stays server-side. */
    private Map<String,Object> publicAnalytics(Map<String,Object> analytics) {
        Map<String,Object> safe = new LinkedHashMap<>();
        for (String key : List.of("views", "avg_watch_seconds", "average_watch_percent", "likes", "comments",
                "shares", "new_subscribers", "lost_subscribers", "impressions", "thumbnail_click_percent",
                "stayed_past_30s_percent", "intro_watched_percent", "small_sample", "small_sample_known",
                "has_audience_curve", "audience_moments", "average_watch", "traffic_share", "reporting_window",
                "number_status", "early_data_note", "analytics_available")) {
            if (analytics.containsKey(key)) safe.put(key, analytics.get(key));
        }
        return safe;
    }

    private Map<String,Object> numberStatus(Map<String,Object> metrics, boolean smallSample) {
        Map<String,Object> result = new LinkedHashMap<>();
        for (String field : List.of("views","avg_watch_seconds","average_watch_percent","likes","comments","shares",
                "new_subscribers","lost_subscribers","impressions","thumbnail_click_percent","stayed_past_30s_percent","intro_watched_percent")) {
            Map<String,Object> entry = new LinkedHashMap<>();
            entry.put("value", metrics.get(field)); entry.put("number_status", metrics.get(field) == null ? "unavailable" : smallSample ? "too early" : "available");
            entry.put("tone", "neutral"); result.put(field, entry);
        }
        return result;
    }

    private List<Map<String,Object>> retentionCurve(Object raw, Double duration) {
        if (duration == null || duration <= 0) return List.of();
        List<Map<String,Object>> out = new ArrayList<>();
        for (Map<String,Object> p : mapRows(asMap(raw).get("retention"))) {
            Double elapsed = number(p.get("elapsedVideoTimeRatio")), ratio = number(p.get("audienceWatchRatio"));
            if (elapsed == null || ratio == null) continue;
            Map<String,Object> item = new LinkedHashMap<>();
            item.put("at_sec", Math.max(0, Math.min((int)Math.round(duration), (int)Math.round(elapsed * duration))));
            item.put("audienceWatchRatio", ratio); out.add(item);
        }
        out.sort(Comparator.comparingInt(p -> integer(p.get("at_sec")))); return out;
    }

    private List<Map<String,Object>> curveMoments(List<Map<String,Object>> curve, Double duration) {
        return duration == null ? List.of() : CreatorCoachQualityRules.audienceMoments(curve, duration);
    }

    private Map<String,Object> averageWatch(Object secondsValue) {
        Map<String,Object> result = new LinkedHashMap<>();
        Double seconds = number(secondsValue); result.put("at_sec", seconds == null ? null : Math.max(0, (int)Math.round(seconds)));
        result.put("falls_in_segment", null); result.put("is_average_not_dropoff", true); return result;
    }

    private Map<String,Object> trafficShare(Object rawTraffic) {
        List<Map<String,Object>> rows = mapRows(asMap(rawTraffic).get("rows"));
        double total = rows.stream().mapToDouble(r -> Math.max(0, number(r.get("views")) == null ? 0 : number(r.get("views")))).sum();
        List<Map<String,Object>> sources = new ArrayList<>();
        for (Map<String,Object> row : rows) {
            Double views = number(row.get("views")); if (views == null || views < 0) continue;
            Map<String,Object> item = new LinkedHashMap<>();
            item.put("source_type", first(row.get("insightTrafficSourceType"), row.get("trafficSourceType"), row.get("source")));
            item.put("views", views); item.put("percent_of_views", total <= 0 ? null : Math.round(views / total * 1000d) / 10d);
            sources.add(item);
        }
        return Map.of("available", total > 0, "total_views", total, "sources", sources);
    }

    private Map<String,Object> postProcessReport(JsonNode ai, String transcript, List<Map<String,Object>> sourceSegments,
                                                  Map<String,Object> video, Map<String,Object> analytics, Map<String,Object> profile,
                                                  List<Map<String,Object>> previous, boolean historyEnough, int duration) {
        Map<String,Object> result = mapper.convertValue(ai, LinkedHashMap.class);
        List<Map<String,Object>> rawAiSegments = mapRows(ai.path("segments"));
        List<Map<String,Object>> segments = postProcessSegments(rawAiSegments, duration);
        result.put("segments", segments);
        result.put("recommendations", mapRows(result.get("recommendations")).stream().limit(2).toList());
        Map<String,Object> first30 = asMap(result.get("first_30_seconds"));
        Integer spokenAt = integer(first30.get("speaks_to_camera_at_sec"));
        if (spokenAt != null && (spokenAt < 0 || spokenAt > duration)) first30.put("speaks_to_camera_at_sec", null);
        result.put("first_30_seconds", first30);
        result.put("style_shifts", mapRows(result.get("style_shifts")).stream().filter(shift -> {
            Integer at = integer(shift.get("at_sec"));
            return at != null && at >= 0 && at <= duration;
        }).toList());
        Map<String,Object> title = asMap(result.get("title_check"));
        List<Integer> indexes = rows(title.get("promise_segment_indexes")).stream().map(this::integer)
                .filter(i -> i != null && i >= 0 && i < rawAiSegments.size()).distinct().toList();
        title.put("promise_segment_indexes", indexes);
        Set<Integer> promiseSegmentIndexes = new HashSet<>(indexes);
        double promiseSeconds = segments.stream()
                .filter(segment -> promiseSegmentIndexes.contains(integer(segment.get("source_segment_index"))))
                .mapToDouble(segment -> Math.max(0, integer(segment.get("end_sec")) - integer(segment.get("start_sec")))).sum();
        title.put("percent_on_title_promise", duration <= 0 ? null : Math.round(promiseSeconds * 1000d / duration) / 10d);
        Integer delivered = integer(title.get("first_delivered_at_sec"));
        title.put("first_delivered_at_sec", delivered == null || delivered < 0 || delivered > duration ? null : delivered);
        segments.forEach(segment -> segment.remove("source_segment_index"));
        result.put("title_check", title);
        Map<String,Object> avg = asMap(analytics.get("average_watch"));
        Integer at = integer(avg.get("at_sec")); String found = null;
        for (Map<String,Object> segment : segments) if (at != null && at >= integer(segment.get("start_sec")) && at <= integer(segment.get("end_sec"))) {
            found = string(segment.get("label"), null); break;
        }
        avg.put("falls_in_segment", found); result.put("average_watch", avg);
        result.put("keep_doing", quoteCheck(mapRows(result.get("keep_doing")), transcript, sourceSegments, duration, true));
        List<Map<String,Object>> findings = mapRows(result.get("findings"));
        for (Map<String,Object> finding : findings) finding.put("evidence", quoteCheck(mapRows(finding.get("evidence")), transcript, sourceSegments, duration, false));
        result.put("findings", findings);
        Set<String> ids = mapRows(analytics.get("audience_moments")).stream().map(m -> string(m.get("id"), "")).collect(Collectors.toSet());
        result.put("audience_explanations", mapRows(result.get("audience_explanations")).stream()
                .filter(e -> ids.contains(string(e.get("moment_id"), ""))).toList());
        if (!Boolean.TRUE.equals(analytics.get("has_audience_curve"))) capConfidence(result);
        boolean small = Boolean.TRUE.equals(analytics.get("small_sample"));
        if (small) { result.put("early_data_note", analytics.get("early_data_note")); neutralizeSmallSampleLanguage(result); }
        if (!historyEnough) result.put("previous_videos_text", null);
        result.put("creator_profile", profile); result.put("previous_videos_enough", historyEnough);
        result.put("previous_videos", historyEnough ? previous : List.of());
        result.put("not_judgeable_from_transcript", result.getOrDefault("cannot_tell_you", List.of()));
        result.put("dataQualityNotes", List.of("Report text is based on the supplied transcript, available Analytics data and creator notes.",
                "A curve change is a moment to review, not proof of why viewers behaved that way.",
                "Visual, music, camera, B-roll and edit quality cannot be judged from transcript text alone."));
        return result;
    }

    private List<Map<String,Object>> postProcessSegments(List<Map<String,Object>> raw, int duration) {
        int max = Math.max(0, duration);
        List<Map<String,Object>> items = new ArrayList<>();
        int sourceIndex = 0;
        for (Map<String,Object> source : raw) {
            int currentSourceIndex = sourceIndex++;
            Integer start = integer(source.get("start_sec")), end = integer(source.get("end_sec"));
            if (start == null || end == null || end <= start) continue;
            start = Math.max(0, Math.min(max, start)); end = Math.max(0, Math.min(max, end));
            if (end <= start) continue;
            Map<String,Object> item = new LinkedHashMap<>();
            item.put("label", string(source.get("label"), "Part of the story"));
            item.put("start_sec", start); item.put("end_sec", end);
            item.put("what_happens", string(source.get("what_happens"), ""));
            item.put("source_segment_index", currentSourceIndex);
            items.add(item);
        }
        items.sort(Comparator.comparingInt(s -> integer(s.get("start_sec"))));
        List<Map<String,Object>> result = new ArrayList<>(); int cursor = 0;
        for (Map<String,Object> item : items) {
            int start = integer(item.get("start_sec")), end = integer(item.get("end_sec"));
            if (start > cursor) {
                Map<String,Object> gap = new LinkedHashMap<>();
                gap.put("label","Transition"); gap.put("start_sec",cursor); gap.put("end_sec",start);
                gap.put("what_happens","The transcript boundaries do not describe this interval clearly.");
                gap.put("percent_of_runtime", max <= 0 ? null : Math.round((start-cursor)*1000d/max)/10d); result.add(gap);
            }
            start = Math.max(cursor, start); if (end <= start) continue;
            item.put("start_sec", start); item.put("end_sec", end);
            item.put("percent_of_runtime", max <= 0 ? null : Math.round((end-start)*1000d/max)/10d);
            result.add(item); cursor = end;
        }
        if (cursor < max) {
            Map<String,Object> rest = new LinkedHashMap<>();
            rest.put("label","Remainder / ending"); rest.put("start_sec",cursor); rest.put("end_sec",max);
            rest.put("what_happens","The supplied transcript segmentation did not assign a specific label to this remaining time.");
            rest.put("percent_of_runtime", max <= 0 ? null : Math.round((max-cursor)*1000d/max)/10d); result.add(rest);
        }
        return result;
    }

    private List<Map<String,Object>> quoteCheck(List<Map<String,Object>> evidence, String transcript, List<Map<String,Object>> sourceSegments,
                                                 int duration, boolean keepDoing) {
        List<Map<String,Object>> result = new ArrayList<>();
        for (Map<String,Object> original : evidence) {
            Map<String,Object> item = new LinkedHashMap<>(original);
            Integer at = integer(first(item.get("at_sec"), item.get("timestamp_sec")));
            String quote = string(item.get("quote"), "");
            boolean valid = CreatorCoachQualityRules.isQuoteValid(transcript, sourceSegments, quote, at, duration);
            if (!valid) {
                item.put("quote", null); item.put("at_sec", null);
                item.put("note", "The quote could not be verified against the timestamped transcript, so it was removed.");
            } else item.put("at_sec", at);
            result.add(item);
        }
        return result;
    }

    private Integer matchedQuoteTimestamp(List<Map<String,Object>> sourceSegments, String quote) {
        String normalized = normalizeText(quote);
        if (normalized.isBlank()) return null;
        String firstWords = normalized.split(" ").length > 4 ? String.join(" ", java.util.Arrays.copyOf(normalized.split(" "), 4)) : normalized;
        for (Map<String,Object> segment : sourceSegments) {
            String cue = normalizeText(string(segment.get("text"), ""));
            if (!cue.isBlank() && (cue.contains(firstWords) || normalized.startsWith(cue) || cue.startsWith(normalized))) {
                Integer seconds = integer(segment.get("start_sec"));
                if (seconds != null) return seconds;
            }
        }
        return null;
    }

    private String postProcessFeedback(Map<String,Object> report, Map<String,Object> profile) {
        List<String> issues = new ArrayList<>();
        List<String> jargon = findJargon(report);
        if (!jargon.isEmpty()) issues.add("Rewrite reader-facing text to remove these jargon terms: " + String.join(", ", jargon) + ". Use ordinary words. Preserve transcript quotes exactly.");
        List<String> conflicts = findProtectedStyleConflicts(report, rows(profile.get("must_keep")));
        if (!conflicts.isEmpty()) issues.add("Rewrite recommendations that contradict protected creator style items: " + String.join(", ", conflicts) + ". Suggest additions or small reorderings, not removing those elements.");
        return String.join(" ", issues);
    }

    private List<String> findJargon(Map<String,Object> report) {
        Set<String> banned = Set.of("hypothesis","ctr","retention","segment","payoff","hook","enum","json","metric","benchmark","funnel","engagement rate");
        Set<String> found = new LinkedHashSet<>(); scanJargon(report, banned, found, null); return new ArrayList<>(found);
    }

    private void scanJargon(Object value, Set<String> banned, Set<String> found, String key) {
        if (value instanceof Map<?,?> map) {
            for (Map.Entry<?,?> e : map.entrySet()) {
                String childKey = String.valueOf(e.getKey());
                if (Set.of("quote","area","tag","id","at_sec","start_sec","end_sec","timestamp").contains(childKey)) continue;
                scanJargon(e.getValue(), banned, found, childKey);
            }
        } else if (value instanceof List<?> list) {
            for (Object item : list) scanJargon(item, banned, found, key);
        } else if (value instanceof String text && key != null
                && !Set.of("term","type","label","language","promptVersion","number_status").contains(key)) {
            String lower = text.toLowerCase(Locale.ROOT);
            for (String term : banned) if (Pattern.compile("\\b" + Pattern.quote(term) + "\\b").matcher(lower).find()) found.add(term);
        }
    }

    private List<String> findProtectedStyleConflicts(Map<String,Object> report, List<?> mustKeep) {
        List<String> advice = new ArrayList<>();
        Map<String,Object> fix = asMap(report.get("fix_first"));
        advice.add(string(fix.get("action"), ""));
        advice.add(string(fix.get("why"), ""));
        for (Object step : rows(fix.get("steps"))) advice.add(String.valueOf(step));
        for (Map<String,Object> recommendation : mapRows(report.get("recommendations"))) {
            advice.add(string(recommendation.get("action"), ""));
            advice.add(string(recommendation.get("reason"), ""));
            for (Object step : rows(recommendation.get("test_steps"))) advice.add(String.valueOf(step));
            advice.add(string(recommendation.get("success_signal"), ""));
        }
        String text = String.join(" ", advice).toLowerCase(Locale.ROOT);
        Pattern destructive = Pattern.compile("\\b(remove|drop|stop using|abandon|replace|avoid|cut out|give up|do not use|don't use)\\b");
        List<String> destructiveSentences = java.util.Arrays.stream(text.split("[.!?\\n]+"))
                .map(String::trim).filter(sentence -> destructive.matcher(sentence).find()).toList();
        if (destructiveSentences.isEmpty()) return List.of();
        List<String> conflicts = new ArrayList<>();
        for (Object kept : mustKeep) {
            String style = String.valueOf(kept).toLowerCase(Locale.ROOT);
            List<String> protectedTerms = new ArrayList<>();
            for (String token : style.split("[^\\p{L}\\p{N}]+")) if (token.length() >= 5) protectedTerms.add(token);
            if (style.contains("narration") || style.contains("voice-over") || style.contains("voice over")) {
                protectedTerms.addAll(List.of("narration", "voice-over", "voice over", "voiceover", "voice over"));
            }
            if (style.contains("cinematic")) protectedTerms.addAll(List.of("cinematic", "cinematic opening"));
            if (style.contains("on-camera") || style.contains("on camera")) {
                protectedTerms.addAll(List.of("on-camera", "on camera", "talking to camera", "speak to camera", "camera presence"));
            }
            if (style.contains("sensory") || style.contains("contrast")) {
                protectedTerms.addAll(List.of("sensory", "sensory detail", "emotional contrast", "contrast", "sense of place"));
            }
            if (style.contains("personal") || style.contains("lived")) {
                protectedTerms.addAll(List.of("personal", "lived experience", "your own experience", "personal moments"));
            }
            if (style.contains("story")) protectedTerms.addAll(List.of("story style", "storytelling", "travel vlog story"));
            boolean contradiction = protectedTerms.stream().filter(t -> !t.isBlank())
                    .anyMatch(term -> destructiveSentences.stream().anyMatch(sentence -> sentence.contains(term)));
            if (contradiction) conflicts.add(String.valueOf(kept));
        }
        return conflicts;
    }

    private void capConfidence(Map<String,Object> report) {
        CreatorCoachQualityRules.capConfidence(report, false);
    }

    private void neutralizeSmallSampleLanguage(Map<String,Object> report) {
        report.putAll(neutralizeSmallSampleValue(report));
        report.put("smallSampleNote", "Too early to judge. Treat numbers as hints, not a verdict.");
    }

    private Map<String,Object> neutralizeSmallSampleValue(Map<String,Object> source) {
        Map<String,Object> result = new LinkedHashMap<>();
        Set<String> skip = Set.of("quote", "creator_profile", "number_status", "id", "tag", "area",
                "at_sec", "start_sec", "end_sec", "source_segment_index", "term", "type", "label",
                "language", "promptVersion");
        source.forEach((key, value) -> result.put(key, skip.contains(key) ? value : neutralizeSmallSampleAny(value, key)));
        return result;
    }

    private Object neutralizeSmallSampleAny(Object value, String key) {
        if (value instanceof Map<?,?> raw) {
            return neutralizeSmallSampleValue(asMap(raw));
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>();
            for (Object item : list) result.add(neutralizeSmallSampleAny(item, key));
            return result;
        }
        if (value instanceof String text && !Set.of("term","type","label","language","promptVersion").contains(key)) {
            return neutralizeSmallSampleText(text);
        }
        return value;
    }

    private String neutralizeSmallSampleText(String text) {
        return text.replaceAll("(?i)\\b(high-performing|low-performing|above average|below average)\\b", "not enough data to judge")
                .replaceAll("(?i)\\b(good|bad|strong|weak|excellent|poor|best|worst|successful|unsuccessful)\\b", "early");
    }

    private void scrubJargon(Map<String,Object> report) {
        report.putAll(replaceJargonValue(report));
    }

    private Map<String,Object> replaceJargonValue(Map<String,Object> source) {
        Map<String,Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(key, "quote".equals(key) ? value : replaceJargonAny(value, key)));
        return result;
    }

    private Object replaceJargonAny(Object value, String key) {
        if (value instanceof Map<?,?> raw) return replaceJargonValue(asMap(raw));
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>();
            for (Object item : list) result.add("quote".equals(key) ? item : replaceJargonAny(item, key));
            return result;
        }
        if (value instanceof String text && !"quote".equals(key)) return replaceJargon(text);
        return value;
    }

    private String replaceJargon(String text) {
        return text.replaceAll("(?i)hypothesis","possible explanation").replaceAll("(?i)CTR","thumbnail click percentage")
                .replaceAll("(?i)retention","how long people kept watching").replaceAll("(?i)segments?","parts")
                .replaceAll("(?i)payoff","reward").replaceAll("(?i)hooks?","opening").replaceAll("(?i)enum","option")
                .replaceAll("(?i)JSON","structured output").replaceAll("(?i)metrics?","numbers")
                .replaceAll("(?i)benchmarks?","comparison").replaceAll("(?i)funnels?","journeys")
                .replaceAll("(?i)engagement rate","interaction percentage");
    }

    /** Internal-only raw Analytics snapshot; the public report never exposes this object. */
    private Map<String,Object> videoAnalyticsSnapshot(Map<String,Object> workspace) {
        Map<String,Object> snapshot = new LinkedHashMap<>();
        for (String key : List.of("analytics", "retention", "reach", "trafficSources", "range")) {
            if (workspace.containsKey(key)) snapshot.put(key, workspace.get(key));
        }
        return snapshot;
    }

    private Map<String,Object> safeVideoAnalytics(String videoId, Integer days) {
        try { return workspaceService.video(videoId, days == null ? 365 : days); }
        catch (RuntimeException ex) {
            log.info("Private Analytics unavailable for video {}: {}", videoId, ex.getClass().getSimpleName());
            Map<String,Object> result = new LinkedHashMap<>();
            result.put("publicOnly", false); result.put("analytics", Map.of("available", false));
            result.put("retention", Map.of("available", false)); result.put("reach", Map.of("available", false));
            result.put("trafficSources", Map.of("available", false)); result.put("range", Map.of("available", false));
            return result;
        }
    }

    private JsonNode ownedVideoDetails(String videoId) {
        if (videoId == null || !videoId.matches("[A-Za-z0-9_-]{11}"))
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "videoId must be an 11-character YouTube video ID.");
        try {
            JsonNode details = publicApiService.getVideoDetails(videoId);
            String ownerChannel = details.path("channel").path("id").asText("");
            if (ownerChannel.isBlank() || !ownerChannel.equals(publicApiService.getAuthorizedChannelId()))
                throw new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN, "The selected video is not owned by the connected YouTube channel.");
            return details;
        } catch (ResponseStatusException ex) { throw ex; }
        catch (RuntimeException ex) {
            String message = safeError(ex).toLowerCase(Locale.ROOT);
            if (message.contains("401") || message.contains("token") || message.contains("authorization"))
                throw new ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED, "Please reconnect YouTube.");
            throw ex;
        }
    }

    private Map<String,Object> videoFacts(JsonNode details) {
        Map<String,Object> v = new LinkedHashMap<>();
        v.put("videoId",details.path("id").asText("")); v.put("title",details.path("title").asText(""));
        v.put("thumbnail_url",details.path("thumbnail").asText("")); v.put("description",details.path("description").asText(""));
        v.put("publish_date",details.path("publish_date").asText(""));
        v.put("duration_seconds",details.path("duration_ms").isNumber() ? details.path("duration_ms").asLong()/1000d : null);
        Double durationSeconds = number(v.get("duration_seconds"));
        v.put("content_format", durationSeconds == null ? "UNKNOWN" : durationSeconds <= 180 ? "SHORT_APPROXIMATION" : "LONG_FORM");
        v.put("duration",details.path("duration_formatted").asText(""));
        v.put("viewCount",details.path("view_count_int").isNumber() ? details.path("view_count_int").asLong() : null);
        v.put("likeCount",details.path("like_count_int").isNumber() ? details.path("like_count_int").asLong() : null);
        v.put("commentCount",details.path("comment_count_int").isNumber() ? details.path("comment_count_int").asLong() : null);
        LocalDate published = publishDay(v);
        v.put("video_age_days",published == null ? null : ageDays(published));
        return v;
    }

    private LocalDate publishDay(Map<String,Object> video) {
        String raw = string(first(video.get("publish_date"),video.get("publishedAt")),"");
        if (raw.isBlank()) return null;
        try { return Instant.parse(raw).atZone(ZoneId.of("Asia/Kolkata")).toLocalDate(); }
        catch (RuntimeException ignored) {
            try { return LocalDate.parse(raw.length() >= 10 ? raw.substring(0,10) : raw); }
            catch (RuntimeException ignoredAgain) { return null; }
        }
    }

    private long ageDays(LocalDate date) {
        return date == null ? -1 : Math.max(0, ChronoUnit.DAYS.between(date,LocalDate.now(ZoneId.of("Asia/Kolkata"))));
    }

    /** Manual Studio values are optional; only a usable transcript blocks story analysis. */
    private List<String> inputGaps(Map<String,Object> manual, Map<String,Object> transcript) {
        List<String> missing = new ArrayList<>();
        if (string(transcript.get("text"),"").isBlank()) missing.add("transcript");
        else if (wordCount(string(transcript.get("text"),"")) < 100) missing.add("transcript_under_100_words");
        return missing;
    }

    private List<Map<String,Object>> optionalStudioInputs(Map<String,Object> manual, Map<String,Object> analytics) {
        List<Map<String,Object>> result = new ArrayList<>();
        addOptionalInput(result, "thumbnail_impressions", "Times your thumbnail was shown",
                first(manual.get("thumbnail_impressions"), analytics.get("impressions")),
                "In YouTube Studio, open Content, select this video, then Analytics → Reach. Find the number of times the thumbnail was shown.");
        addOptionalInput(result, "thumbnail_click_percent", "Out of people who saw your thumbnail, how many clicked",
                first(manual.get("thumbnail_click_percent"), analytics.get("thumbnail_click_percent")),
                "In YouTube Studio, open Content, select this video, then Analytics → Reach. Find the thumbnail click-through percentage.");
        addOptionalInput(result, "stayed_past_30s_percent", "People who stayed past 30 seconds",
                manual.get("stayed_past_30s_percent"),
                "In YouTube Studio, open Content, select this video, then Analytics → Engagement. Open the audience-retention detail and look for the first 30 seconds, when available.");
        addOptionalInput(result, "intro_watched_percent", "People who watched the intro",
                manual.get("intro_watched_percent"),
                "In YouTube Studio, open Content, select this video, then Analytics → Engagement. Check the intro/first-part value if Studio shows one.");
        addOptionalInput(result, "thumbnail_description", "What your thumbnail shows",
                manual.get("thumbnail_description"),
                "Describe the image in one line: what is visible, where you are positioned, and any words shown.");
        return result;
    }

    private void addOptionalInput(List<Map<String,Object>> target, String key, String label, Object value, String help) {
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("key", key);
        row.put("label", label);
        row.put("provided", value != null && !(value instanceof String s && s.isBlank()));
        row.put("help", help);
        target.add(row);
    }

    private Map<String,Object> howToGetInputs(List<String> missing) {
        Map<String,Object> help = new LinkedHashMap<>();
        for (String field : missing) help.put(field, switch (field) {
            case "thumbnail_impressions" -> "In YouTube Studio, open the video's Analytics, then Reach. Find the number of times the thumbnail was shown.";
            case "thumbnail_click_percent" -> "In YouTube Studio, open the video's Analytics, then Reach. Copy the video-level impressions click-through percentage.";
            case "stayed_past_30s_percent" -> "In YouTube Studio, open Engagement analytics. Look for the intro or first-30-seconds value when available.";
            case "intro_watched_percent" -> "In YouTube Studio, open the audience-retention detail and copy the intro value if shown.";
            case "thumbnail_description" -> "Describe the image in one line: what is visible, where you are positioned and any words shown.";
            case "transcript" -> "Open the transcript, copy it and paste it into the transcript editor. Keep timestamps if shown.";
            case "transcript_under_100_words" -> "The transcript is too short for a full story review. Add the complete transcript; do not guess missing words.";
            default -> "Provide this field if it is available for the video.";
        });
        return help;
    }

    private List<Map<String,Object>> parseTranscript(String text) {
        if (text == null || text.isBlank()) return new ArrayList<>();
        String normalized = text.replaceAll("\\s+(?=\\[\\d{1,2}:\\d{2}(?::\\d{2})?\\])","\n").trim();
        List<Map<String,Object>> result = new ArrayList<>();
        for (String raw : normalized.split("\\R")) {
            String line = raw.trim(); if (line.isBlank()) continue;
            Integer seconds = null; Matcher m = TIMESTAMP_LINE.matcher(line);
            if (m.matches()) { seconds = timestampToSeconds(m.group(1)); line = m.group(2).trim(); }
            if (line.isBlank()) continue;
            Map<String,Object> item = new LinkedHashMap<>(); item.put("start_sec",seconds); item.put("text",line); result.add(item);
        }
        return result;
    }

    private List<Map<String,Object>> normalizeSegments(List<Map<String,Object>> input) {
        List<Map<String,Object>> out = new ArrayList<>();
        for (Map<String,Object> s : input) {
            String text = string(s.get("text"),"").trim(); if (text.isBlank()) continue;
            Integer start = integer(s.get("start_sec"));
            if (start == null && s.get("timestamp") != null) start = timestampToSeconds(string(s.get("timestamp"),""));
            Map<String,Object> item = new LinkedHashMap<>(); item.put("start_sec",start == null ? null : Math.max(0,start)); item.put("text",text); out.add(item);
        }
        out.sort(Comparator.comparing(s -> integer(s.get("start_sec")) == null ? Integer.MAX_VALUE : integer(s.get("start_sec"))));
        return out;
    }

    private String renderTranscript(List<Map<String,Object>> segments) {
        String joined = renderTranscriptUnbounded(segments);
        return joined.length() <= 60000 ? joined : joined.substring(0,60000);
    }

    private String renderTranscriptUnbounded(List<Map<String,Object>> segments) {
        List<String> lines = new ArrayList<>();
        for (Map<String,Object> s : segments) {
            String text = string(s.get("text"),"").trim(); if (text.isBlank()) continue;
            Integer sec = integer(s.get("start_sec")); lines.add((sec == null ? "" : "["+formatTimestamp(sec)+"] ")+text);
        }
        return String.join("\n",lines);
    }

    private Integer timestampToSeconds(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String[] p = raw.trim().split(":");
        try { if (p.length == 2) return Integer.parseInt(p[0])*60+Integer.parseInt(p[1]);
            if (p.length == 3) return Integer.parseInt(p[0])*3600+Integer.parseInt(p[1])*60+Integer.parseInt(p[2]); }
        catch (NumberFormatException ignored) {}
        return null;
    }

    private String formatTimestamp(int seconds) {
        int s = Math.max(0,seconds);
        return s >= 3600 ? String.format(Locale.ROOT,"%02d:%02d:%02d",s/3600,(s%3600)/60,s%60)
                : String.format(Locale.ROOT,"%02d:%02d",s/60,s%60);
    }

    private int wordCount(String text) { return text == null || text.isBlank() ? 0 : text.trim().split("\\s+").length; }
    private String normalizeText(String value) { return value == null ? "" : value.replaceAll("\\s+"," ").trim().toLowerCase(Locale.ROOT); }

    private Map<String,Object> asMap(Object value) {
        if (value instanceof Map<?,?> map) { Map<String,Object> out=new LinkedHashMap<>(); map.forEach((k,v)->out.put(String.valueOf(k),v)); return out; }
        if (value == null) return new LinkedHashMap<>();
        try { return mapper.convertValue(value,LinkedHashMap.class); } catch (IllegalArgumentException ignored) { return new LinkedHashMap<>(); }
    }

    private List<Map<String,Object>> mapRows(Object value) {
        List<Map<String,Object>> out = new ArrayList<>();
        if (value instanceof List<?> list) for (Object item : list) out.add(asMap(item));
        return out;
    }
    private List<?> rows(Object value) { return value instanceof List<?> list ? list : List.of(); }
    private Object first(Object... values) { for (Object v: values) if (v != null && !(v instanceof String s && s.isBlank())) return v; return null; }
    private String string(Object value,String fallback) { return value == null ? fallback : String.valueOf(value); }
    private Double number(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        if (value == null || String.valueOf(value).isBlank()) return null;
        try { return Double.parseDouble(String.valueOf(value)); } catch (NumberFormatException ignored) { return null; }
    }
    private Integer integer(Object value) { Double n=number(value); return n == null ? null : n.intValue(); }

    private String hashJson(Object input) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(input))); }
        catch (Exception ex) { throw new IllegalStateException("Could not fingerprint analysis input.",ex); }
    }

    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) return null;
        try { if (value.length() >= 10 && value.charAt(4)=='-' && value.charAt(7)=='-') return LocalDate.parse(value.substring(0,10));
            return Instant.parse(value).atZone(ZoneId.of("Asia/Kolkata")).toLocalDate(); } catch (RuntimeException ignored) { return null; }
    }

    private String safeError(Exception ex) {
        String s = ex.getMessage(); if (s == null || s.isBlank()) return "Analysis could not be completed.";
        s = s.replaceAll("(?i)sk-[A-Za-z0-9_-]{20,}","[redacted]").replaceAll("(?i)Bearer\\s+[A-Za-z0-9._~-]+","Bearer [redacted]");
        return s.length()>350 ? s.substring(0,350) : s;
    }

    private String safeOwner(String owner) {
        return owner == null || owner.length()<6 ? "[creator]" : owner.substring(0,3)+"…"+owner.substring(owner.length()-2);
    }
}
