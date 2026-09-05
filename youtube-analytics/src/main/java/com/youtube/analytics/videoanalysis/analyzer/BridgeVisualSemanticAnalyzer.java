package com.youtube.analytics.videoanalysis.analyzer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youtube.analytics.config.BridgeConfig;
import com.youtube.analytics.videoanalysis.model.VisualObservation;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/** Sends representative frames to a Bridge adapter which delegates analysis to ChatGPT. */
@Service
public class BridgeVisualSemanticAnalyzer implements VisualSemanticAnalyzer {
    private final WebClient webClient;
    private final BridgeConfig config;
    private final ObjectMapper objectMapper;

    public BridgeVisualSemanticAnalyzer(WebClient.Builder builder, BridgeConfig config, ObjectMapper objectMapper) {
        this.webClient = builder.baseUrl(config.resolvedBaseUrl()).build();
        this.config = config;
        this.objectMapper = objectMapper;
    }

    @Override
    public VisualObservation analyze(Path imageFile) {
        try {
            byte[] bytes = Files.readAllBytes(imageFile);
            if (bytes.length == 0) throw new IllegalArgumentException("Visual analysis image is empty: " + imageFile);
            Map<String, Object> request = Map.of(
                    "type", "VISUAL_FRAME_ANALYSIS",
                    "image", dataUrl(imageFile, bytes),
                    "prompt", prompt());
            JsonNode response = webClient.post().uri(config.resolvedVisualPath())
                    .contentType(MediaType.APPLICATION_JSON).bodyValue(request).retrieve()
                    .bodyToMono(JsonNode.class).block(Duration.ofSeconds(config.resolvedTimeoutSeconds()));
            JsonNode result = resultNode(response);
            return new VisualObservation(result.path("summary").asText(),
                    objectMapper.convertValue(result.path("objects"), List.class),
                    result.path("environment").asText(), result.path("qualityScore").asDouble());
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to read representative frame for Bridge analysis", ex);
        } catch (Exception ex) {
            if (ex instanceof IllegalStateException) throw (IllegalStateException) ex;
            throw new IllegalStateException("Bridge visual analysis failed: " + ex.getMessage(), ex);
        }
    }

    private JsonNode resultNode(JsonNode response) {
        if (response == null) throw new IllegalStateException("Bridge returned an empty visual response");
        JsonNode result = response.has("result") ? response.get("result") : response;
        if (!result.isObject() || !result.has("summary") || !result.has("objects")
                || !result.has("environment") || !result.has("qualityScore")) {
            throw new IllegalStateException("Bridge visual response did not match the expected schema");
        }
        return result;
    }

    private String dataUrl(Path imageFile, byte[] bytes) throws IOException {
        String contentType = Files.probeContentType(imageFile);
        if (contentType == null) contentType = "image/jpeg";
        return "data:" + contentType + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }

    private String prompt() {
        return "Analyze this representative frame from a raw travel/video clip. Describe only visible evidence. "
                + "Return JSON with summary, objects, environment, and qualityScore (0 to 1). "
                + "Do not infer events, audio, speech, identity, location, or intent that cannot be established from the frame. "
                + "Prefer editing-relevant descriptions such as driving POV, road, mountain landscape, people, food, building, interior, landscape, or close-up when visibly supported.";
    }
}
