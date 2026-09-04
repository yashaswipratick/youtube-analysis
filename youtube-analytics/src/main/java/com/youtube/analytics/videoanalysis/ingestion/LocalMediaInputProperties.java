package com.youtube.analytics.videoanalysis.ingestion;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "video-analysis")
public record LocalMediaInputProperties(
        String inputDirectory,
        @DefaultValue("true") boolean approvalRequired,
        @DefaultValue("renders") String outputDirectory,
        @DefaultValue("analysis-cache") String analysisCacheDirectory,
        @DefaultValue("true") boolean analysisCacheEnabled,
        @DefaultValue("4") int maxConcurrentVideos) {

    @ConstructorBinding
    public LocalMediaInputProperties {
    }

    public LocalMediaInputProperties(String inputDirectory, boolean approvalRequired, String outputDirectory) {
        this(inputDirectory, approvalRequired, outputDirectory, "analysis-cache", true, 4);
    }
}
