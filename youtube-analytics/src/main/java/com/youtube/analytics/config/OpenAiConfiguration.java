package com.youtube.analytics.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({OpenAiConfig.class, AiAnalysisProperties.class, BridgeConfig.class, AnalysisVisualProperties.class})
public class OpenAiConfiguration {
}
