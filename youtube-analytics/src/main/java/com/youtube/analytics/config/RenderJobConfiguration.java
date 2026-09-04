package com.youtube.analytics.config;

import com.youtube.analytics.videoanalysis.config.RenderJobProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(RenderJobProperties.class)
public class RenderJobConfiguration {}
