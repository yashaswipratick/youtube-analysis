package com.youtube.analytics.config;

import com.youtube.analytics.videoanalysis.config.VisualEffectProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(VisualEffectProperties.class)
public class VisualEffectConfiguration {
}
