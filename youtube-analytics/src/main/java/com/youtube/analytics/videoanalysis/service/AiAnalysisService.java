package com.youtube.analytics.videoanalysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youtube.analytics.config.AiAnalysisProperties;
import com.youtube.analytics.config.BridgeConfig;
import com.youtube.analytics.videoanalysis.model.RawVideoClipAnalysis;
import com.youtube.analytics.videoanalysis.model.SceneSegment;
import com.youtube.analytics.videoanalysis.model.SpeechSegment;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Executes AI analysis from the persisted request exchange and returns the enriched video analysis. */
@Service
public class AiAnalysisService {
    private final AnalysisRequestExchangeService requestExchangeService;
    private final WebClient webClient;
    private final AiAnalysisProperties properties;
    private final BridgeConfig bridgeConfig;
    private final ObjectMapper objectMapper;

    public AiAnalysisService(AnalysisRequestExchangeService requestExchangeService,
                             WebClient.Builder webClientBuilder,
                             AiAnalysisProperties properties,
                             BridgeConfig bridgeConfig,
                             ObjectMapper objectMapper) {
        this.requestExchangeService = requestExchangeService;
        this.webClient = webClientBuilder.build();
        this.properties = properties;
        this.bridgeConfig = bridgeConfig;
        this.objectMapper = objectMapper;
    }

    public RawVideoClipAnalysis analyze(java.nio.file.Path sourceFile, RawVideoClipAnalysis prepared) {
        String exchangeFile = sourceFile.getFileName().toString();
        AnalysisRequestExchangeService.RequestDocument document =
                requestExchangeService.readRequestDocument(exchangeFile + ".json");
        if (document.videoRequests().isEmpty() && document.speechRequests().isEmpty()) {
            return prepared;
        }
        if (!"bridge".equals(properties.resolvedProvider())) {
            throw new IllegalStateException("Persisted-request AI analysis currently requires video-analysis.ai.provider=bridge");
        }

        List<SceneSegment> scenes = new ArrayList<>(prepared.scenes());
        for (int i = 0; i < document.videoRequests().size() && i < scenes.size(); i++) {
            JsonNode response = callBridge(bridgeConfig.resolvedVisualPath(),
                    Map.of("type", "VISUAL_FRAME_ANALYSIS", "requestBody", document.videoRequests().get(i).body()));
            JsonNode result = resultNode(response);
            String summary = result.path("summary").asText(scenes.get(i).visualSummary());
            double score = result.path("qualityScore").isNumber()
                    ? result.path("qualityScore").asDouble() : scenes.get(i).visualScore();
            scenes.set(i, new SceneSegment(scenes.get(i).startMs(), scenes.get(i).endMs(), summary, score));
        }

        List<SpeechSegment> speech = prepared.speechSegments();
        if (!document.speechRequests().isEmpty()) {
            JsonNode response = callBridge(bridgeConfig.resolvedTranscriptionPath(),
                    Map.of("type", "SPEECH_TRANSCRIPTION", "requestBody", document.speechRequests().get(0).body()));
            speech = parseSpeech(response, prepared.durationMs());
        }

        double visualScore = scenes.isEmpty() ? prepared.visualQualityScore()
                : scenes.stream().mapToDouble(SceneSegment::visualScore).average().orElse(prepared.visualQualityScore());
        return new RawVideoClipAnalysis(prepared.sourceFileName(), prepared.durationMs(), scenes, speech,
                prepared.audio(), visualScore);
    }

    private JsonNode callBridge(String path, Object body) {
        try {
            JsonNode response = webClient.post()
                    .uri(bridgeConfig.resolvedBaseUrl() + path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(java.time.Duration.ofSeconds(bridgeConfig.resolvedTimeoutSeconds()));
            if (response == null) throw new IllegalStateException("Bridge returned an empty AI response");
            return response;
        } catch (Exception ex) {
            if (ex instanceof IllegalStateException) throw (IllegalStateException) ex;
            throw new IllegalStateException("Bridge AI analysis failed: " + ex.getMessage(), ex);
        }
    }

    private JsonNode resultNode(JsonNode response) {
        JsonNode result = response.has("result") ? response.get("result") : response;
        if (!result.isObject()) throw new IllegalStateException("Bridge visual response did not contain an object result");
        return result;
    }

    private List<SpeechSegment> parseSpeech(JsonNode response, long durationMs) {
        JsonNode result = resultNode(response);
        JsonNode segments = result.get("segments");
        if (segments == null || !segments.isArray()) {
            String text = result.path("text").asText("").trim();
            return text.isBlank() ? List.of() : List.of(new SpeechSegment(0, durationMs, text, 1.0));
        }
        List<SpeechSegment> parsed = new ArrayList<>();
        for (JsonNode segment : segments) {
            long start = segment.path("startMs").asLong(Math.round(segment.path("start").asDouble(0) * 1000));
            long end = segment.path("endMs").asLong(Math.round(segment.path("end").asDouble(durationMs / 1000.0) * 1000));
            String text = segment.path("text").asText("").trim();
            if (!text.isBlank()) parsed.add(new SpeechSegment(Math.max(0, start), Math.max(start, end), text,
                    segment.path("clarityScore").isNumber() ? segment.path("clarityScore").asDouble() : 1.0));
        }
        return parsed;
    }
}
