package com.youtube.analytics.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youtube.analytics.config.OpenAiApiKeyProvider;
import com.youtube.analytics.config.OpenAiConfig;
import com.youtube.analytics.model.AiAnalysisRequest;
import com.youtube.analytics.model.AiAnalysisResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Calls the OpenAI Responses API and parses a strict structured analytics response. */
@Slf4j
@Service
public class OpenAiAnalysisService {

    private final WebClient webClient;
    private final OpenAiConfig config;
    private final OpenAiApiKeyProvider apiKeyProvider;
    private final ObjectMapper objectMapper;

    public OpenAiAnalysisService(
            WebClient.Builder webClientBuilder,
            OpenAiConfig config,
            OpenAiApiKeyProvider apiKeyProvider,
            ObjectMapper objectMapper) {
        this.webClient = webClientBuilder.baseUrl(config.resolvedBaseUrl()).build();
        this.config = config;
        this.apiKeyProvider = apiKeyProvider;
        this.objectMapper = objectMapper;
    }

    public AiAnalysisResult analyze(AiAnalysisRequest request) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", config.resolvedModel());
            payload.put("input", buildInput(request));
            payload.put("text", Map.of("format", Map.of(
                    "type", "json_schema",
                    "name", "youtube_analytics_analysis",
                    "strict", true,
                    "schema", responseSchema())));

            JsonNode response = webClient.post()
                    .uri("/responses")
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> headers.setBearerAuth(apiKeyProvider.getApiKey()))
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            JsonNode structured = extractStructuredOutput(response);
            return new AiAnalysisResult(
                    config.resolvedModel(),
                    request.context(),
                    requiredText(structured, "summary"),
                    stringList(structured, "observations"),
                    stringList(structured, "strengths"),
                    stringList(structured, "weaknesses"),
                    stringList(structured, "recommendations"),
                    stringList(structured, "missingData"));
        } catch (Exception e) {
            log.error("OpenAI analytics analysis failed", e);
            throw new IllegalStateException("OpenAI analytics analysis failed: " + e.getMessage(), e);
        }
    }

    private String buildInput(AiAnalysisRequest request) {
        String context = "{}";
        try {
            if (request.context() != null && !request.context().isEmpty()) {
                context = objectMapper.writeValueAsString(request.context());
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to serialize analysis context", e);
        }

        return "You are a YouTube analytics expert. Analyze only the supplied analytics evidence. "
                + "Never invent, estimate, or assume missing metrics or facts. Do not infer video duration, "
                + "retention, impressions, CTR, revenue, or any other value unless it is explicitly provided. "
                + "Distinguish facts from interpretation. When required information is absent, put the "
                + "missing field in missingData and explicitly state that the conclusion cannot be determined "
                + "from the supplied data. Recommendations must be grounded in the supplied evidence. "
                + "Return only the requested JSON structure.\n\n"
                + "User request:\n" + request.prompt() + "\n\n"
                + "Analytics context (JSON):\n" + context;
    }

    private JsonNode extractStructuredOutput(JsonNode response) {
        if (response == null) {
            throw new IllegalStateException("OpenAI returned an empty response");
        }

        JsonNode output = response.get("output");
        if (output != null && output.isArray()) {
            for (JsonNode item : output) {
                JsonNode content = item.get("content");
                if (content == null || !content.isArray()) continue;
                for (JsonNode part : content) {
                    JsonNode text = part.get("text");
                    if (text != null && text.isTextual()) {
                        return parseJsonObject(text.asText());
                    }
                }
            }
        }

        JsonNode outputText = response.get("output_text");
        if (outputText != null && outputText.isTextual() && !outputText.asText().isBlank()) {
            return parseJsonObject(outputText.asText());
        }

        throw new IllegalStateException("OpenAI response did not contain structured output");
    }

    private JsonNode parseJsonObject(String text) {
        try {
            JsonNode parsed = objectMapper.readTree(text);
            if (!parsed.isObject()) {
                throw new IllegalStateException("OpenAI structured output is not a JSON object");
            }
            return parsed;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to parse OpenAI structured output as JSON", e);
        }
    }

    private String requiredText(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual()) {
            throw new IllegalStateException("Missing required AI response field: " + field);
        }
        return value.asText();
    }

    private List<String> stringList(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isArray()) {
            throw new IllegalStateException("Missing required AI response array: " + field);
        }
        List<String> result = new ArrayList<>();
        value.forEach(node -> {
            if (!node.isTextual()) {
                throw new IllegalStateException("AI response field must contain only strings: " + field);
            }
            result.add(node.asText());
        });
        return result;
    }

    private Map<String, Object> responseSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.put("properties", Map.of(
                "summary", Map.of("type", "string"),
                "observations", arraySchema(),
                "strengths", arraySchema(),
                "weaknesses", arraySchema(),
                "recommendations", arraySchema(),
                "missingData", arraySchema()));
        schema.put("required", List.of(
                "summary", "observations", "strengths", "weaknesses", "recommendations", "missingData"));
        return schema;
    }

    private Map<String, Object> arraySchema() {
        return Map.of("type", "array", "items", Map.of("type", "string"));
    }
}
