package com.youtube.analytics.videoanalysis.analyzer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youtube.analytics.config.OpenAiApiKeyProvider;
import com.youtube.analytics.config.OpenAiConfig;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiVisualSemanticAnalyzerTest {

    @Test
    void sendsRepresentativeFrameToResponsesApiAndParsesVisualObservation() throws Exception {
        AtomicReference<String> requestUrl = new AtomicReference<>();
        ExchangeFunction exchange = request -> {
            requestUrl.set(request.url().toString());
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .body("{\"model\":\"gpt-5.6-luna\",\"output_text\":\"{\\\"summary\\\":\\\"Mountain road with a scenic valley visible from a driving viewpoint.\\\",\\\"objects\\\":[\\\"road\\\",\\\"mountains\\\",\\\"car dashboard\\\"],\\\"environment\\\":\\\"outdoor mountain landscape\\\",\\\"qualityScore\\\":0.88}\"}")
                    .build());
        };

        OpenAiConfig config = new OpenAiConfig(
                "/tmp/openai-key", "https://api.openai.com/v1", "gpt-5.6-luna", "gpt-4o-mini-transcribe", true);
        OpenAiApiKeyProvider keyProvider = new OpenAiApiKeyProvider(config) {
            @Override
            public String getApiKey() {
                return "test-key";
            }
        };
        OpenAiVisualSemanticAnalyzer analyzer = new OpenAiVisualSemanticAnalyzer(
                WebClient.builder().exchangeFunction(exchange), config, keyProvider, new ObjectMapper());

        Path image = Files.createTempFile("youtube-analysis-visual-test-", ".jpg");
        try {
            Files.write(image, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0});
            var observation = analyzer.analyze(image);

            assertThat(requestUrl.get()).isEqualTo("https://api.openai.com/v1/responses");
            assertThat(observation.summary()).contains("Mountain road");
            assertThat(observation.objects()).containsExactly("road", "mountains", "car dashboard");
            assertThat(observation.environment()).isEqualTo("outdoor mountain landscape");
            assertThat(observation.qualityScore()).isEqualTo(0.88);
        } finally {
            Files.deleteIfExists(image);
        }
    }
}
