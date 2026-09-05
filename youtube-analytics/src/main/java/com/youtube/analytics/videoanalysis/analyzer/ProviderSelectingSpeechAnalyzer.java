package com.youtube.analytics.videoanalysis.analyzer;

import com.youtube.analytics.config.AiAnalysisProperties;
import com.youtube.analytics.config.OpenAiApiKeyProvider;
import com.youtube.analytics.videoanalysis.model.SpeechSegment;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;

/** Selects OpenAI or Bridge transcription at runtime. */
@Service
@Primary
public class ProviderSelectingSpeechAnalyzer implements SpeechAnalyzer {
    private final AiAnalysisProperties properties;
    private final OpenAiApiKeyProvider keyProvider;
    private final OpenAiSpeechAnalyzer openAi;
    private final BridgeSpeechAnalyzer bridge;

    public ProviderSelectingSpeechAnalyzer(AiAnalysisProperties properties, OpenAiApiKeyProvider keyProvider,
                                           OpenAiSpeechAnalyzer openAi, BridgeSpeechAnalyzer bridge) {
        this.properties = properties;
        this.keyProvider = keyProvider;
        this.openAi = openAi;
        this.bridge = bridge;
    }

    @Override
    public List<SpeechSegment> transcribe(Path sourceFile) {
        return bridge.transcribe(sourceFile);
    }

    private SpeechAnalyzer selected() {
        return switch (properties.resolvedProvider()) {
            case "bridge" -> bridge;
            case "openai" -> openAi;
            case "auto" -> keyProvider.isConfigured() ? openAi : bridge;
            default -> throw new IllegalStateException("Unsupported video-analysis.ai.provider: " + properties.resolvedProvider()
                    + ". Supported values are openai, bridge, auto");
        };
    }
}
