package com.youtube.analytics.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * Test-only replacement for the production credential loader.
 * Supplies non-secret OAuth values without reading a credential file.
 */
public class GoogleCredentialsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        environment.getPropertySources().addFirst(new MapPropertySource(
                "testGoogleOAuthCredentials",
                Map.of(
                        "GOOGLE_CLIENT_ID", "test-client-id",
                        "GOOGLE_CLIENT_SECRET", "test-client-secret")));
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
