package com.youtube.analytics.service.vlogscript;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youtube.analytics.config.OpenAiApiKeyProvider;
import com.youtube.analytics.config.OpenAiConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.web.reactive.function.client.WebClient;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StorytellingRewriteServiceTest {
    @Mock
    private WebClient.Builder webClientBuilder;
    @Mock
    private WebClient webClient;
    @Mock
    private OpenAiApiKeyProvider apiKeyProvider;

    private StorytellingRewriteService service;

    @BeforeEach
    void setUp() {
        when(webClientBuilder.baseUrl(anyString())).thenReturn(webClientBuilder);
        when(webClientBuilder.build()).thenReturn(webClient);
        service = new StorytellingRewriteService(webClientBuilder,
                new OpenAiConfig("/not-read-in-this-test", "https://api.openai.com/v1", "test-model"),
                apiKeyProvider, new ObjectMapper());
    }

    @Test
    void identifiesExpectedVoiceOverLabelsButNotOnCamera() {
        assertThat(StorytellingRewriteService.isVoiceOverType("Voice-over / Storytelling")).isTrue();
        assertThat(StorytellingRewriteService.isVoiceOverType("Voice-over / Personal Reflection")).isTrue();
        assertThat(StorytellingRewriteService.isVoiceOverType("On-camera — recorded; script intentionally blank")).isFalse();
        assertThat(StorytellingRewriteService.isVoiceOverType("Script / draft")).isFalse();
    }

    @Test
    void neverSendsOnCameraSectionsToOpenAi() {
        Map<String, Object> onCamera = Map.of(
                "id", "recorded-intro",
                "title", "Recorded intro",
                "type", "On-camera — recorded; script intentionally blank",
                "script", "This is already recorded.");

        assertThatThrownBy(() -> service.rewriteSections("Coorg", "Episode 1", List.of(onCamera)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Only voice-over sections can be rewritten");
        verifyNoInteractions(apiKeyProvider, webClient);
    }

    @Test
    void rejectsEmptyVoiceOverBeforeCallingOpenAi() {
        Map<String, Object> emptyVoiceOver = Map.of(
                "id", "empty-vo",
                "title", "Empty voice-over",
                "type", "Voice-over / Storytelling",
                "script", "   ");

        assertThatThrownBy(() -> service.rewriteSections("Coorg", "Episode 1", List.of(emptyVoiceOver)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty script");
        verifyNoInteractions(apiKeyProvider, webClient);
    }

    @Test
    void rejectsDuplicateFullEpisodeContextIdsBeforeCallingOpenAi() {
        Map<String, Object> selectedVoiceOver = Map.of(
                "id", "vo-1", "type", "Voice-over", "script", "A useful voice-over.");
        Map<String, Object> contextOne = Map.of(
                "id", "same", "type", "On-camera", "script", "An existing on-camera line.");
        Map<String, Object> contextTwo = Map.of(
                "id", "same", "type", "Voice-over", "script", "Another section.");

        assertThatThrownBy(() -> service.rewriteSections("request-1", "Coorg", "Episode 1",
                List.of(selectedVoiceOver), List.of(contextOne, contextTwo)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unique, non-empty ID");
        verifyNoInteractions(apiKeyProvider, webClient);
    }

    @Test
    void auditRejectsDuplicateSectionIdsBeforeCallingOpenAi() {
        Map<String, Object> one = Map.of("id", "same", "type", "Voice-over", "script", "First");
        Map<String, Object> two = Map.of("id", "same", "type", "Voice-over", "script", "Second");

        assertThatThrownBy(() -> service.auditEpisode("request-1", "Coorg", "Episode 2", List.of(one, two)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unique, non-empty ID");
        verifyNoInteractions(apiKeyProvider, webClient);
    }

    @Test
    void rejectsDuplicateSectionIdsBeforeCallingOpenAi() {
        Map<String, Object> one = Map.of("id", "same", "type", "Voice-over", "script", "First");
        Map<String, Object> two = Map.of("id", "same", "type", "Voice-over", "script", "Second");

        assertThatThrownBy(() -> service.rewriteSections("Coorg", "Episode 1", List.of(one, two)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unique ID");
        verifyNoInteractions(apiKeyProvider, webClient);
    }
}
