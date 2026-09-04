package com.youtube.analytics.config;

import com.youtube.analytics.videoanalysis.config.AudioMixProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AudioMixProperties.class)
public class AudioMixConfiguration {
}
