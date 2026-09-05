package com.youtube.analytics.videoanalysis.analyzer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youtube.analytics.config.OpenAiApiKeyProvider;
import com.youtube.analytics.config.OpenAiConfig;
import com.youtube.analytics.videoanalysis.model.VisualObservation;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Uses an OpenAI vision-capable model to describe representative video frames. */
@Service
public class OpenAiVisualSemanticAnalyzer implements VisualSemanticAnalyzer {

    private final WebClient webClient;
    private final OpenAiConfig config;
    private final OpenAiApiKeyProvider apiKeyProvider;
    private final ObjectMapper objectMapper;

    public OpenAiVisualSemanticAnalyzer(WebClient.Builder webClientBuilder,
                                        OpenAiConfig config,
                                        OpenAiApiKeyProvider apiKeyProvider,
                                        ObjectMapper objectMapper) {
        this.webClient = webClientBuilder.baseUrl(config.resolvedBaseUrl()).build();
        this.config = config;
        this.apiKeyProvider = apiKeyProvider;
        this.objectMapper = objectMapper;
    }

    @Override
    public VisualObservation analyze(Path imageFile) {
        if (imageFile == null || !Files.isRegularFile(imageFile)) {
            throw new IllegalArgumentException("Visual analysis image does not exist: " + imageFile);
        }
        try {
            byte[] imageBytes = Files.readAllBytes(imageFile);
            if (imageBytes.length == 0) throw new IllegalArgumentException("Visual analysis image is empty: " + imageFile);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", config.resolvedModel());
            payload.put("input", List.of(Map.of(
                    "role", "user",
                    "content", List.of(
                            Map.of("type", "input_text", "text", analysisPrompt()),
                            Map.of("type", "input_image", "image_url", dataUrl(imageFile, imageBytes))
                    )
            )));
            payload.put("text", Map.of("format", Map.of(
                    "type", "json_schema", "name", "video_frame_observation", "strict", true,
                    "schema", responseSchema())));

            JsonNode response = webClient.post()
                    .uri("/responses")
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> headers.setBearerAuth(apiKeyProvider.getApiKey()))
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            return parseObservation(extractStructuredOutput(response));
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to read representative frame for visual analysis", ex);
        } catch (Exception ex) {
            if (ex instanceof IllegalStateException) throw (IllegalStateException) ex;
            throw new IllegalStateException("OpenAI visual analysis failed: " + ex.getMessage(), ex);
        }
    }

    private String analysisPrompt() {
        return "Analyze this representative frame from a raw travel/video clip. Describe only visible evidence. "
                + "Return a concise factual summary of the scene, the main visible objects or subjects, and the visible environment. "
                + "Assess visual quality from 0 to 1 based on framing, clarity, exposure, stability, and usefulness as a video-editing shot. "
                + "Do not infer events, audio, speech, identity, location, or intent that cannot be established from the frame. "
                + "Prefer editing-relevant descriptions such as driving POV, road, mountain landscape, people, food, building, interior, landscape, or close-up when visibly supported.";
    }

    private String dataUrl(Path imageFile, byte[] bytes) throws IOException {
        String contentType = Files.probeContentType(imageFile);
        if (contentType == null || !SetOfSupportedImageTypes.contains(contentType.toLowerCase())) {
            contentType = switch (extension(imageFile)) {
                case "png" -> "image/png";
                case "webp" -> "image/webp";
                default -> "image/jpeg";
            };
        }
        return "data:" + contentType + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }

    private String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "jpg" : name.substring(dot + 1).toLowerCase();
    }

    private VisualObservation parseObservation(JsonNode root) {
        JsonNode summary = root.get("summary");
        JsonNode objects = root.get("objects");
        JsonNode environment = root.get("environment");
        JsonNode quality = root.get("qualityScore");
        if (summary == null || !summary.isTextual() || objects == null || !objects.isArray()
                || environment == null || !environment.isTextual() || quality == null || !quality.isNumber()) {
            throw new IllegalStateException("OpenAI visual response did not match the expected schema");
        }
        List<String> objectNames = new java.util.ArrayList<>();
        objects.forEach(node -> {
            if (!node.isTextual()) throw new IllegalStateException("OpenAI visual objects must contain only strings");
            objectNames.add(node.asText().trim());
        });
        return new VisualObservation(summary.asText().trim(), objectNames,
                environment.asText().trim(), quality.asDouble());
    }

    private JsonNode extractStructuredOutput(JsonNode response) {
        if (response == null) throw new IllegalStateException("OpenAI returned an empty visual response");
        JsonNode output = response.get("output");
        if (output != null && output.isArray()) for (JsonNode item : output) {
            JsonNode content = item.get("content");
            if (content != null && content.isArray()) for (JsonNode part : content) {
                JsonNode text = part.get("text");
                if (text != null && text.isTextual()) return parseJsonObject(text.asText());
            }
        }
        JsonNode outputText = response.get("output_text");
        if (outputText != null && outputText.isTextual() && !outputText.asText().isBlank()) return parseJsonObject(outputText.asText());
        throw new IllegalStateException("OpenAI visual response did not contain structured output");
    }

    private JsonNode parseJsonObject(String text) {
        try {
            JsonNode parsed = objectMapper.readTree(text);
            if (!parsed.isObject()) throw new IllegalStateException("OpenAI visual structured output is not an object");
            return parsed;
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to parse OpenAI visual structured output", ex);
        }
    }

    private Map<String, Object> responseSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.put("properties", Map.of(
                "summary", Map.of("type", "string"),
                "objects", Map.of("type", "array", "items", Map.of("type", "string")),
                "environment", Map.of("type", "string"),
                "qualityScore", Map.of("type", "number", "minimum", 0, "maximum", 1)
        ));
        schema.put("required", List.of("summary", "objects", "environment", "qualityScore"));
        return schema;
    }

    private static final java.util.Set<String> SetOfSupportedImageTypes = java.util.Set.of("image/jpeg", "image/png", "image/webp");
}
