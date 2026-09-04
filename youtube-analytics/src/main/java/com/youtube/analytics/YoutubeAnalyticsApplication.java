package com.youtube.analytics;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.youtube.analytics.videoanalysis.ingestion.LocalMediaInputProperties;

@SpringBootApplication
@EnableConfigurationProperties(LocalMediaInputProperties.class)
public class YoutubeAnalyticsApplication {

    public static void main(String[] args) {
        SpringApplication.run(YoutubeAnalyticsApplication.class, args);
    }
}
