package com.youtube.analytics.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Selects how AI-assisted video analysis is performed. */
@ConfigurationProperties(prefix = "video-analysis.ai")
public record AiAnalysisProperties(String provider) {
    public String resolvedProvider() {
        if (provider == null || provider.isBlank()) return "auto";
        return provider.trim().toLowerCase();
    }
}
