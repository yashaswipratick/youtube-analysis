package com.youtube.analytics.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youtube.analytics.config.OpenAiApiKeyProvider;
import com.youtube.analytics.config.OpenAiConfig;
import com.youtube.analytics.model.AiAnalysisRequest;
import com.youtube.analytics.model.AiAnalysisResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiAnalysisServiceTest {

    @Test
    void sendsAnalysisRequestToResponsesApiAndParsesStructuredOutput() {
        AtomicReference<String> requestUrl = new AtomicReference<>();
        ExchangeFunction exchange = request -> {
            requestUrl.set(request.url().toString());
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .body("{\"model\":\"gpt-5.6-luna\",\"output_text\":\"{\\\"summary\\\":\\\"Views are strong.\\\",\\\"observations\\\":[\\\"57 views\\\"],\\\"strengths\\\":[\\\"Good engagement\\\"],\\\"weaknesses\\\":[\\\"Low reach\\\"],\\\"recommendations\\\":[{\\\"recommendation\\\":\\\"Improve packaging\\\",\\\"type\\\":\\\"EVIDENCE_BASED\\\",\\\"reason\\\":\\\"Search and related traffic exist.\\\"}],\\\"missingData\\\":[\\\"retention\\\"]}\"}")
                    .build());
        };

        OpenAiConfig config = new OpenAiConfig(
                "/tmp/openai-key", "https://api.openai.com/v1", "gpt-5.6-luna");
        OpenAiApiKeyProvider keyProvider = new OpenAiApiKeyProvider(config) {
            @Override
            public String getApiKey() {
                return "test-key";
            }
        };

        OpenAiAnalysisService service = new OpenAiAnalysisService(
                WebClient.builder().exchangeFunction(exchange),
                config,
                keyProvider,
                new ObjectMapper());

        AiAnalysisResult result = service.analyze(new AiAnalysisRequest(
                "Analyze the trend",
                Map.of("views", 3681)));

        assertThat(requestUrl.get()).isEqualTo("https://api.openai.com/v1/responses");
        assertThat(result.model()).isEqualTo("gpt-5.6-luna");
        assertThat(result.summary()).isEqualTo("Views are strong.");
        assertThat(result.observations()).containsExactly("57 views");
        assertThat(result.strengths()).containsExactly("Good engagement");
        assertThat(result.weaknesses()).containsExactly("Low reach");
        assertThat(result.recommendations()).hasSize(1);
        assertThat(result.recommendations().get(0).recommendation()).isEqualTo("Improve packaging");
        assertThat(result.recommendations().get(0).type())
                .isEqualTo(AiAnalysisResult.RecommendationType.EVIDENCE_BASED);
        assertThat(result.recommendations().get(0).reason())
                .isEqualTo("Search and related traffic exist.");
        assertThat(result.missingData()).containsExactly("retention");
    }

    @Test
    void comparesTranscriptsWithTheExpandedEvidenceSchema() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> structured = new java.util.LinkedHashMap<>();
        structured.put("summary", "The competitor opens with a clear question.");
        structured.put("plainLanguageSummary", "Give viewers a reason to be curious right away, then deliver on that promise in your own voice.");
        structured.put("creatorPattern", Map.of("patternName", "Curiosity to discovery", "whyItFitsMyStyle", "It leaves room for personal travel moments and reflection.", "steps", java.util.List.of("Open with a real question", "Show why this trip matters", "Share a specific discovery", "End with what changed for you"), "nextVideoExercise", "Try one opening question in your next vlog and compare retention with a previous upload."));
        structured.put("termsExplained", java.util.List.of(Map.of("term", "Hook", "meaning", "The opening reason to keep watching.", "whyItMatters", "It sets a clear promise for the viewer.")));
        structured.put("performanceContext", "Public lifetime views are not comparable with reporting-window views.");
        structured.put("hookComparison", "The competitor states a question immediately.");
        structured.put("structureComparison", "The available text suggests a setup followed by discovery.");
        structured.put("dataQuality", Map.of("myTranscriptOk", true, "competitorTranscriptOk", true, "notes", java.util.List.of()));
        structured.put("findings", java.util.List.of(Map.of(
                "area", "Opening hook",
                "observation", "A specific question opens the competitor transcript.",
                "evidence", java.util.List.of(Map.of("source", "competitor", "timestamp", "00:02", "quote", "What happens next?")),
                "hypothesis", "The question may establish curiosity quickly.",
                "confidence", "MEDIUM")));
        structured.put("competitorStrengths", java.util.List.of());
        structured.put("missingFromMyTranscript", java.util.List.of());
        structured.put("analyticsLinks", java.util.List.of());
        structured.put("recommendations", java.util.List.of(Map.of(
                "rank", 1, "change", "Test a more specific opening question.",
                "evidence", "The competitor transcript opens with a question.",
                "type", "EXPERIMENTAL", "test", "Compare two opening-hook variants.",
                "successMetric", "First-30-second retention", "confidence", "MEDIUM")));
        structured.put("notJudgeableFromTranscript", java.util.List.of("Visual composition and music."));
        structured.put("missingData", java.util.List.of("No timestamped retention curve was supplied."));
        String openAiResponse = mapper.writeValueAsString(Map.of("output_text", mapper.writeValueAsString(structured)));
        AtomicReference<String> requestUrl = new AtomicReference<>();
        ExchangeFunction exchange = request -> {
            requestUrl.set(request.url().toString());
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .body(openAiResponse)
                    .build());
        };
        OpenAiConfig config = new OpenAiConfig("/tmp/openai-key", "https://api.openai.com/v1", "gpt-5.6-luna");
        OpenAiApiKeyProvider keyProvider = new OpenAiApiKeyProvider(config) {
            @Override
            public String getApiKey() {
                return "test-key";
            }
        };
        OpenAiAnalysisService service = new OpenAiAnalysisService(
                WebClient.builder().exchangeFunction(exchange), config, keyProvider, mapper);

        JsonNode result = service.compareTranscripts(Map.of(
                "myTranscript", "[00:01] I started the journey.",
                "competitorTranscript", "[00:02] What happens next?"));

        assertThat(requestUrl.get()).isEqualTo("https://api.openai.com/v1/responses");
        assertThat(result.path("findings").get(0).path("evidence").get(0).path("timestamp").asText())
                .isEqualTo("00:02");
        assertThat(result.path("dataQuality").path("myTranscriptOk").asBoolean()).isTrue();
        assertThat(result.path("plainLanguageSummary").asText()).contains("own voice");
        assertThat(result.path("creatorPattern").path("steps")).hasSize(4);
        assertThat(result.path("termsExplained").get(0).path("term").asText()).isEqualTo("Hook");
    }


    @Test
    void analyzesSingleOwnedVideoWithStrictCreatorCoachSchema() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> structured = new java.util.LinkedHashMap<>();
        structured.put("data_quality", Map.of(
                "transcript_ok", true, "sample_size_note", "Directional for a new channel.",
                "video_age_days", 5, "history_available", false, "notes", java.util.List.of("Retention curve unavailable.")));
        structured.put("plain_summary", "Keep the cinematic opening; give viewers a clear reason for the journey sooner.");
        structured.put("segment_map", java.util.List.of(Map.of(
                "label", "Cinematic opening", "start", "00:00", "end", "00:22", "percent_of_runtime", 8.2)));
        structured.put("promise_vs_delivery", Map.of(
                "title_promise", "A quiet hidden destination near Bangalore",
                "first_delivered_at", "00:37", "runtime_on_promise_percent", 62.0,
                "observation", "The destination reveal starts after the road sequence.", "confidence", "medium"));
        structured.put("keep_doing", java.util.List.of(Map.of(
                "strength", "Reflective sensory narration",
                "evidence", Map.of("timestamp", "00:08", "quote", "Pahaadon par chhayi dhundh"))));
        structured.put("findings", java.util.List.of(Map.of(
                "area", "hook", "observation", "The opening establishes atmosphere but the journey question arrives later.",
                "evidence", java.util.List.of(Map.of("timestamp", "00:08", "quote", "Pahaadon par chhayi dhundh", "note", "Opening imagery.")),
                "hypothesis", "An earlier question may give new viewers a reason to continue.",
                "confidence", "medium")));
        structured.put("analytics_links", java.util.List.of(Map.of(
                "metric", "Average view duration", "value", "95 seconds", "falls_in_segment", "Road sequence",
                "hypothesis", "The average viewing duration overlaps the road sequence.",
                "limitation", "Average duration is not a point where viewers dropped off.")));
        Map<String, Object> historyData = new java.util.LinkedHashMap<>();
        historyData.put("enough_history", false);
        historyData.put("pattern", null);
        historyData.put("note", "Fewer than three eligible earlier uploads are available.");
        structured.put("vs_previous_videos", historyData);
        structured.put("fix_first", Map.of(
                "action", "Add one clear curiosity question early.",
                "why", "It can pair the cinematic mood with a reason to follow the journey.",
                "how_to_do_it", "Keep the reflective opening, then ask a natural on-camera question.",
                "confidence", "medium"));
        structured.put("recommendations", java.util.List.of(Map.of(
                "rank", 1, "label", "EXPERIMENTAL", "action", "Try one earlier on-camera question.",
                "reason", "The transcript places the question after the initial setup.",
                "test", "Use this in the next vlog and compare the first 30-second retention.",
                "success_metric", "Compare stayed-past-30-second retention against the next similar upload.",
                "confidence", "medium")));
        structured.put("next_video_checklist", java.util.List.of("Preserve the cinematic opening.", "Add one early curiosity question."));
        structured.put("questions_for_creator", java.util.List.of("Which part of the opening was on camera?"));
        structured.put("not_judgeable_from_transcript", java.util.List.of("Camera framing, B-roll, music and edit rhythm."));
        String responseBody = mapper.writeValueAsString(Map.of("output_text", mapper.writeValueAsString(structured)));
        AtomicReference<String> requestUrl = new AtomicReference<>();
        ExchangeFunction exchange = request -> {
            requestUrl.set(request.url().toString());
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .body(responseBody)
                    .build());
        };
        OpenAiConfig config = new OpenAiConfig("/tmp/openai-key", "https://api.openai.com/v1", "gpt-5.6-luna");
        OpenAiApiKeyProvider keyProvider = new OpenAiApiKeyProvider(config) {
            @Override public String getApiKey() { return "test-key"; }
        };
        OpenAiAnalysisService service = new OpenAiAnalysisService(
                WebClient.builder().exchangeFunction(exchange), config, keyProvider, mapper);

        JsonNode result = service.analyzeCreatorVideo(Map.of(
                "output_language", "hinglish",
                "creator_profile", Map.of("style", "Hindi voice-over + natural Hinglish on-camera"),
                "video", Map.of("title", "Coorg road trip"),
                "transcript", "[00:08] Pahaadon par chhayi dhundh",
                "analytics", Map.of("retention_curve_available", false),
                "previous_videos", java.util.List.of()));

        assertThat(requestUrl.get()).isEqualTo("https://api.openai.com/v1/responses");
        assertThat(result.path("fix_first").path("action").asText()).contains("curiosity question");
        assertThat(result.path("vs_previous_videos").path("enough_history").asBoolean()).isFalse();
        assertThat(result.path("recommendations")).hasSize(1);
        assertThat(result.path("segment_map").get(0).path("start").asText()).isEqualTo("00:00");
    }


    @Test
    void runsVersionedCreatorStoryPromptAndReturnsInternalUsageMetadata() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> report = new java.util.LinkedHashMap<>();
        report.put("transcript_ok", true);
        report.put("early_data_note", "Too early to judge these numbers.");
        report.put("plain_summary", "Keep your reflective opening and add one clear question.");
        report.put("segments", java.util.List.of(Map.of(
                "label", "Cinematic opening", "start_sec", 0, "end_sec", 20, "what_happens", "Reflective narration")));
        Map<String, Object> title = new java.util.LinkedHashMap<>();
        title.put("title_promise", "A quiet trip into the hills");
        title.put("promise_segment_indexes", java.util.List.of(1));
        title.put("first_delivered_at_sec", null);
        title.put("explanation", "The transcript does not give enough timing evidence.");
        title.put("how_sure", "fairly");
        report.put("title_check", title);
        Map<String, Object> first30 = new java.util.LinkedHashMap<>();
        first30.put("speaks_to_camera_at_sec", null);
        first30.put("has_clear_question_or_promise", true);
        first30.put("matches_title", true);
        first30.put("explanation", "The transcript contains an opening question; camera delivery cannot be confirmed.");
        report.put("first_30_seconds", first30);
        report.put("style_shifts", java.util.List.of());
        report.put("keep_doing", java.util.List.of(Map.of(
                "strength", "Reflective sensory narration", "quote", "Pahaadon par chhayi dhundh", "at_sec", 8)));
        report.put("findings", java.util.List.of());
        report.put("audience_explanations", java.util.List.of());
        report.put("average_watch_explanation", null);
        report.put("habits", Map.of(
                "opened_with_clear_question", true,
                "answered_title_promise_early", false,
                "ended_with_clear_answer", false,
                "asked_to_comment_or_subscribe", false,
                "spoke_to_camera_within_30s", false));
        report.put("previous_videos_text", null);
        report.put("fix_first", Map.of(
                "action", "Add one clear curiosity question early.",
                "why", "It gives viewers a reason to follow the journey.",
                "steps", java.util.List.of("Keep the cinematic opening.", "Add a natural question on camera."),
                "how_sure", "fairly"));
        report.put("recommendations", java.util.List.of(Map.of(
                "action", "Try the question in the next upload.",
                "reason", "The transcript leaves room for a clearer question.",
                "tag", "try_and_see",
                "test_steps", java.util.List.of("Use one early question."),
                "success_signal", "Compare the first-30-seconds percentage on the next similar video.",
                "how_sure", "fairly")));
        report.put("next_video_checklist", java.util.List.of("Keep the reflective opening.", "Add one question.", "Deliver the title promise."));
        report.put("questions_for_creator", java.util.List.of("Which moment was actually on camera?"));
        report.put("cannot_tell_you", java.util.List.of("Music and B-roll quality cannot be judged from transcript text."));

        Map<String, Object> openAiResponse = new java.util.LinkedHashMap<>();
        openAiResponse.put("id", "resp-test-123");
        openAiResponse.put("model", "test-coach-model");
        openAiResponse.put("usage", Map.of("input_tokens", 1234, "output_tokens", 321));
        openAiResponse.put("output_text", mapper.writeValueAsString(report));
        String responseBody = mapper.writeValueAsString(openAiResponse);
        AtomicReference<String> requestUrl = new AtomicReference<>();
        ExchangeFunction exchange = request -> {
            requestUrl.set(request.url().toString());
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .body(responseBody)
                    .build());
        };
        OpenAiConfig config = new OpenAiConfig("/tmp/openai-key", "https://api.openai.com/v1", "gpt-5.6-luna");
        OpenAiApiKeyProvider keyProvider = new OpenAiApiKeyProvider(config) {
            @Override public String getApiKey() { return "test-key"; }
        };
        OpenAiAnalysisService service = new OpenAiAnalysisService(
                WebClient.builder().exchangeFunction(exchange), config, keyProvider, mapper);

        Map<String, Object> result = service.analyzeCreatorStory(Map.of(
                "output_language", "hinglish",
                "creator_profile", Map.of("must_keep", java.util.List.of("cinematic opening")),
                "transcript", "[00:08] Pahaadon par chhayi dhundh"));

        assertThat(requestUrl.get()).isEqualTo("https://api.openai.com/v1/responses");
        assertThat(result.get("promptVersion")).isEqualTo("coach-v1");
        assertThat(result.get("model")).isEqualTo("test-coach-model");
        assertThat(result.get("inputTokens")).isEqualTo(1234);
        assertThat(result.get("outputTokens")).isEqualTo(321);
        assertThat(result.get("rawResponse").toString()).contains("resp-test-123", "test-coach-model");
    }

    @Test
    void validatesApiKeyAgainstModelsEndpointWithoutGeneratingContent() {
        AtomicReference<String> requestUrl = new AtomicReference<>();
        ExchangeFunction exchange = request -> {
            requestUrl.set(request.url().toString());
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .body("""
                    {"data":[{"id":"gpt-5.6-luna"}]}
                    """)
                    .build());
        };
        OpenAiConfig config = new OpenAiConfig("/tmp/openai-key", "https://api.openai.com/v1", "gpt-5.6-luna");
        OpenAiApiKeyProvider keyProvider = new OpenAiApiKeyProvider(config) {
            @Override
            public String getApiKey() {
                return "test-key";
            }
        };
        OpenAiAnalysisService service = new OpenAiAnalysisService(
                WebClient.builder().exchangeFunction(exchange), config, keyProvider, new ObjectMapper());

        Map<String, Object> result = service.validateApiKey();

        assertThat(requestUrl.get()).isEqualTo("https://api.openai.com/v1/models");
        assertThat(result.get("configured")).isEqualTo(true);
        assertThat(result.get("valid")).isEqualTo(true);
        assertThat(result.get("modelListed")).isEqualTo(true);
        assertThat(result.get("status")).isEqualTo("VALID");
    }

    @Test
    void reportsUnauthorizedApiKeyFromModelsEndpoint() {
        ExchangeFunction exchange = request -> Mono.just(ClientResponse.create(HttpStatus.UNAUTHORIZED)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body("""
                {"error":{"message":"Incorrect API key provided"}}
                """)
                .build());
        OpenAiConfig config = new OpenAiConfig("/tmp/openai-key", "https://api.openai.com/v1", "gpt-5.6-luna");
        OpenAiApiKeyProvider keyProvider = new OpenAiApiKeyProvider(config) {
            @Override
            public String getApiKey() {
                return "bad-test-key";
            }
        };
        OpenAiAnalysisService service = new OpenAiAnalysisService(
                WebClient.builder().exchangeFunction(exchange), config, keyProvider, new ObjectMapper());

        Map<String, Object> result = service.validateApiKey();

        assertThat(result.get("configured")).isEqualTo(true);
        assertThat(result.get("valid")).isEqualTo(false);
        assertThat(result.get("status")).isEqualTo("INVALID_OR_REVOKED_API_KEY");
        assertThat(result.get("httpStatus")).isEqualTo(401);
        assertThat(result.get("message").toString()).contains("rejected the API key");
    }
}
