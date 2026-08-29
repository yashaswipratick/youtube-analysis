package com.youtube.analytics.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Loads Google OAuth client credentials from a local JSON file before OAuth2
 * client properties are bound. Credential values are deliberately never logged.
 */
public class GoogleCredentialsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
    private static final String DEFAULT_CREDENTIALS_FILE =
            "/Users/yashaswipratick/Documents/youtube-analytics/screts.json";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String configuredPath = environment.getProperty(
                "youtube.oauth.credentials-file", DEFAULT_CREDENTIALS_FILE);
        Path credentialsFile = Path.of(configuredPath);

        if (!Files.isRegularFile(credentialsFile)) {
            throw new IllegalStateException("Google OAuth credentials file was not found: " + credentialsFile);
        }

        try {
            JsonNode credentials = OBJECT_MAPPER.readTree(Files.readString(credentialsFile));
            String clientId = requiredValue(credentials, "client-id", credentialsFile);
            String clientSecret = requiredValue(credentials, "client-secret", credentialsFile);

            environment.getPropertySources().addFirst(new MapPropertySource(
                    "googleOAuthCredentialsFile",
                    Map.of("GOOGLE_CLIENT_ID", clientId, "GOOGLE_CLIENT_SECRET", clientSecret)));
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to read Google OAuth credentials file: " + credentialsFile, ex);
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    private String requiredValue(JsonNode credentials, String field, Path credentialsFile) {
        String value = credentials.path(field).asText();
        if (value.isBlank()) {
            throw new IllegalStateException(
                    "Google OAuth credentials file is missing required field '" + field + "': " + credentialsFile);
        }
        return value;
    }
}
