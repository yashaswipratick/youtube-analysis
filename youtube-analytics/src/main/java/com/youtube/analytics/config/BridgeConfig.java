package com.youtube.analytics.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for the local Bridge analysis adapter. */
@ConfigurationProperties(prefix = "video-analysis.ai.bridge")
public record BridgeConfig(String baseUrl, String visualPath, String transcriptionPath, long timeoutSeconds) {
    public String resolvedBaseUrl() {
        return baseUrl == null || baseUrl.isBlank() ? "http://127.0.0.1:8787" : baseUrl;
    }

    public String resolvedVisualPath() {
        return visualPath == null || visualPath.isBlank() ? "/analysis/visual" : visualPath;
    }

    public String resolvedTranscriptionPath() {
        return transcriptionPath == null || transcriptionPath.isBlank() ? "/analysis/transcription" : transcriptionPath;
    }

    public long resolvedTimeoutSeconds() {
        return timeoutSeconds <= 0 ? 10 * 60 : timeoutSeconds;
    }
}
