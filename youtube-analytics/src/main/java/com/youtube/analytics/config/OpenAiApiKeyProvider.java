package com.youtube.analytics.config;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Reads the OpenAI API key from the configured local file. */
@Component
public class OpenAiApiKeyProvider {

    private final OpenAiConfig config;

    public OpenAiApiKeyProvider(OpenAiConfig config) {
        this.config = config;
    }

    public boolean isConfigured() {
        if (config.apiKeyFile() == null || config.apiKeyFile().isBlank()) return false;
        try {
            return Files.isRegularFile(Path.of(config.apiKeyFile()))
                    && !Files.readString(Path.of(config.apiKeyFile())).trim().isBlank();
        } catch (IOException e) {
            return false;
        }
    }

    public String getApiKey() {
        if (config.apiKeyFile() == null || config.apiKeyFile().isBlank()) {
            throw new IllegalStateException("OpenAI API key file is not configured");
        }

        try {
            String key = Files.readString(Path.of(config.apiKeyFile())).trim();
            if (key.isBlank()) {
                throw new IllegalStateException("OpenAI API key file is empty: " + config.apiKeyFile());
            }
            return key;
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read OpenAI API key file: " + config.apiKeyFile(), e);
        }
    }
}
