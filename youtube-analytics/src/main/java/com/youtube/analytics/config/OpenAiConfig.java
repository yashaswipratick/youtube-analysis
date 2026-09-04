package com.youtube.analytics.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for the OpenAI analysis integration. */
@ConfigurationProperties(prefix = "openai")
public record OpenAiConfig(String apiKeyFile, String baseUrl, String model, String transcriptionModel) {
    public String resolvedBaseUrl() {
        return baseUrl == null || baseUrl.isBlank() ? "https://api.openai.com/v1" : baseUrl;
    }

    public String resolvedModel() {
        return model == null || model.isBlank() ? "gpt-5.6-luna" : model;
    }

    public String resolvedTranscriptionModel() {
        return transcriptionModel == null || transcriptionModel.isBlank() ? "gpt-4o-mini-transcribe" : transcriptionModel;
    }
}
