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

import java.util.LinkedHashMap;
import java.util.Map;

/** Calls the OpenAI Responses API to analyze YouTube analytics context. */
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

            JsonNode response = webClient.post()
                    .uri("/responses")
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> headers.setBearerAuth(apiKeyProvider.getApiKey()))
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            String analysis = extractOutputText(response);
            return new AiAnalysisResult(config.resolvedModel(), request.context(), analysis);
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

        return "You are a YouTube analytics expert. Analyze the supplied YouTube analytics data "
                + "and answer the user's request. Be evidence-driven, concise, and explicitly "
                + "separate observations from recommendations.\n\n"
                + "User request:\n" + request.prompt() + "\n\n"
                + "Analytics context (JSON):\n" + context;
    }

    private String extractOutputText(JsonNode response) {
        if (response == null) {
            throw new IllegalStateException("OpenAI returned an empty response");
        }

        JsonNode outputText = response.get("output_text");
        if (outputText != null && outputText.isTextual() && !outputText.asText().isBlank()) {
            return outputText.asText();
        }

        JsonNode output = response.get("output");
        if (output != null && output.isArray()) {
            StringBuilder text = new StringBuilder();
            for (JsonNode item : output) {
                JsonNode content = item.get("content");
                if (content == null || !content.isArray()) continue;
                for (JsonNode part : content) {
                    JsonNode value = part.get("text");
                    if (value != null && value.isTextual()) {
                        if (text.length() > 0) text.append('\n');
                        text.append(value.asText());
                    }
                }
            }
            if (!text.isEmpty()) return text.toString();
        }

        throw new IllegalStateException("OpenAI response did not contain output text");
    }
}
