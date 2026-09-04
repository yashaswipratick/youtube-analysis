package com.youtube.analytics.videoanalysis.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "video-analysis.effects")
public record VisualEffectProperties(boolean fadeEnabled, long fadeDurationMs) {
    public VisualEffectProperties {
        if (fadeDurationMs < 0) throw new IllegalArgumentException("video-analysis.effects.fade-duration-ms must be >= 0");
    }
}
