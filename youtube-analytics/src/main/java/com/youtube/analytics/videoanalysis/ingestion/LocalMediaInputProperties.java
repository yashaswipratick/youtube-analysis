package com.youtube.analytics.videoanalysis.ingestion;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "video-analysis")
public record LocalMediaInputProperties(String inputDirectory) {
}
