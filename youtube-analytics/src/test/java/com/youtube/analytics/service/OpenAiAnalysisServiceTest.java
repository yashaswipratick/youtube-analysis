package com.youtube.analytics.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youtube.analytics.config.OpenAiApiKeyProvider;
import com.youtube.analytics.config.OpenAiConfig;
import com.youtube.analytics.model.AiAnalysisRequest;
import com.youtube.analytics.model.AiAnalysisResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiAnalysisServiceTest {

    @Test
    void sendsAnalysisRequestToResponsesApiAndExtractsOutputText() {
        AtomicReference<String> requestBody = new AtomicReference<>();
        ExchangeFunction exchange = request -> {
            requestBody.set(request.body() == null ? null : request.url().toString());
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .body("{\"model\":\"gpt-5.6-luna\",\"output_text\":\"Views increased 25%.\"}")
                    .build());
        };

        OpenAiConfig config = new OpenAiConfig("/tmp/openai-key", "https://api.openai.com/v1", "gpt-5.6-luna");
        OpenAiApiKeyProvider keyProvider = new OpenAiApiKeyProvider(config) {
            @Override
            public String getApiKey() {
                return "test-key";
            }
        };

        OpenAiAnalysisService service = new OpenAiAnalysisService(
                () -> WebClient.builder().exchangeFunction(exchange),
                config,
                keyProvider,
                new ObjectMapper());

        AiAnalysisResult result = service.analyze(new AiAnalysisRequest(
                "Analyze the trend",
                Map.of("views", 3681)));

        assertThat(requestBody.get()).isEqualTo("https://api.openai.com/v1/responses");
        assertThat(result.model()).isEqualTo("gpt-5.6-luna");
        assertThat(result.analysis()).isEqualTo("Views increased 25%.");
    }
}
