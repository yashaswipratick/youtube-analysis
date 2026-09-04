package com.youtube.analytics.videoanalysis.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "video-analysis.render")
public record RenderJobProperties(int maxConcurrentJobs) {
    public RenderJobProperties {
        if (maxConcurrentJobs < 1 || maxConcurrentJobs > 4) throw new IllegalArgumentException("video-analysis.render.max-concurrent-jobs must be between 1 and 4");
    }
}
