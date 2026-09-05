package com.youtube.analytics.videoanalysis.analyzer;

import com.youtube.analytics.config.AiAnalysisProperties;
import com.youtube.analytics.config.OpenAiConfig;import com.youtube.analytics.config.OpenAiApiKeyProvider;
import com.youtube.analytics.videoanalysis.model.VisualObservation;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

/** Selects OpenAI or Bridge at runtime without changing the video-analysis pipeline. */
@Service
@Primary
public class ProviderSelectingVisualSemanticAnalyzer implements VisualSemanticAnalyzer {
    private final AiAnalysisProperties properties;
    private final OpenAiApiKeyProvider keyProvider;
    private final OpenAiConfig openAiConfig;
    private final OpenAiVisualSemanticAnalyzer openAi;
    private final BridgeVisualSemanticAnalyzer bridge;
    private final BasicVisualSemanticAnalyzer basic;

    public ProviderSelectingVisualSemanticAnalyzer(AiAnalysisProperties properties, OpenAiApiKeyProvider keyProvider,
                                                    OpenAiConfig openAiConfig,
                                                    OpenAiVisualSemanticAnalyzer openAi,
                                                    BridgeVisualSemanticAnalyzer bridge,
                                                    BasicVisualSemanticAnalyzer basic) {
        this.properties = properties;
        this.keyProvider = keyProvider;
        this.openAiConfig = openAiConfig;
        this.openAi = openAi;
        this.bridge = bridge;
        this.basic = basic;
    }

    @Override
    public VisualObservation analyze(Path imageFile) {
        return basic.analyze(imageFile);
    }

    private boolean openAiEnabled() {
        return openAiConfig.visualAnalysisEnabled();
    }

    private VisualSemanticAnalyzer selected() {
        return switch (properties.resolvedProvider()) {
            case "bridge" -> bridge;
            case "openai" -> openAi;
            case "auto" -> keyProvider.isConfigured() ? openAi : bridge;
            default -> throw new IllegalStateException("Unsupported video-analysis.ai.provider: " + properties.resolvedProvider()
                    + ". Supported values are openai, bridge, auto");
        };
    }
}
