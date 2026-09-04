package com.youtube.analytics.videoanalysis.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "video-analysis.audio")
public record AudioMixProperties(
        String musicDirectory,
        String musicFileName,
        double musicVolume,
        boolean duckSpeech) {

    public AudioMixProperties {
        if (musicVolume < 0.0 || musicVolume > 1.0) {
            throw new IllegalArgumentException("video-analysis.audio.music-volume must be between 0 and 1");
        }
    }
}
