package com.youtube.analytics.service;

import com.youtube.analytics.config.OpenAiConfig;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Reads an API key from a local file configured outside source control. */
@Component
public class OpenAiApiKeyProvider {

    private final OpenAiConfig config;

    public OpenAiApiKeyProvider(OpenAiConfig config) {
        this.config = config;
    }

    public String getApiKey() {
        if (config.apiKeyFile() == null || config.apiKeyFile().isBlank()) {
            throw new IllegalStateException("openai.api-key-file is not configured");
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
