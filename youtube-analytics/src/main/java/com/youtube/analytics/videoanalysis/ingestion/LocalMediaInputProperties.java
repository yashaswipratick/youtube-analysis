package com.youtube.analytics.videoanalysis.ingestion;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "video-analysis")
public record LocalMediaInputProperties(String inputDirectory, @DefaultValue("true") boolean approvalRequired) {
}
