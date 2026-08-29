package com.youtube.analytics.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youtube.analytics.config.OpenAiApiKeyProvider;
import com.youtube.analytics.config.OpenAiConfig;
import com.youtube.analytics.model.AiAnalysisRequest;
import com.youtube.analytics.model.AiAnalysisResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiAnalysisServiceTest {

    @Test
    void sendsAnalysisRequestToResponsesApiAndParsesStructuredOutput() {
        AtomicReference<String> requestUrl = new AtomicReference<>();
        ExchangeFunction exchange = request -> {
            requestUrl.set(request.url().toString());
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .body("{\"model\":\"gpt-5.6-luna\",\"output_text\":\"{\\\"summary\\\":\\\"Views are strong.\\\",\\\"observations\\\":[\\\"57 views\\\"],\\\"strengths\\\":[\\\"Good engagement\\\"],\\\"weaknesses\\\":[\\\"Low reach\\\"],\\\"recommendations\\\":[\\\"Improve packaging\\\"],\\\"missingData\\\":[\\\"retention\\\"]}\"}")
                    .build());
        };

        OpenAiConfig config = new OpenAiConfig(
                "/tmp/openai-key", "https://api.openai.com/v1", "gpt-5.6-luna");
        OpenAiApiKeyProvider keyProvider = new OpenAiApiKeyProvider(config) {
            @Override
            public String getApiKey() {
                return "test-key";
            }
        };

        OpenAiAnalysisService service = new OpenAiAnalysisService(
                WebClient.builder().exchangeFunction(exchange),
                config,
                keyProvider,
                new ObjectMapper());

        AiAnalysisResult result = service.analyze(new AiAnalysisRequest(
                "Analyze the trend",
                Map.of("views", 3681)));

        assertThat(requestUrl.get()).isEqualTo("https://api.openai.com/v1/responses");
        assertThat(result.model()).isEqualTo("gpt-5.6-luna");
        assertThat(result.summary()).isEqualTo("Views are strong.");
        assertThat(result.observations()).containsExactly("57 views");
        assertThat(result.strengths()).containsExactly("Good engagement");
        assertThat(result.weaknesses()).containsExactly("Low reach");
        assertThat(result.recommendations()).containsExactly("Improve packaging");
        assertThat(result.missingData()).containsExactly("retention");
    }
}
