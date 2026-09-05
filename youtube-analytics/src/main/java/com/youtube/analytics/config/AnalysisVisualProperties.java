package com.youtube.analytics.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Controls optional AI enhancement of deterministic local visual analysis. */
@ConfigurationProperties(prefix = "analysis.visual")
public record AnalysisVisualProperties(boolean includeAi) {
}
