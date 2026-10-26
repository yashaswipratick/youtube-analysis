package com.youtube.analytics.controller;

import com.youtube.analytics.service.vlogscript.StorytellingRewriteService;
import com.youtube.analytics.service.vlogscript.VlogScriptStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VlogScriptControllerTest {
    @Mock
    private VlogScriptStore store;

    @Mock
    private StorytellingRewriteService storytellingRewriteService;

    @InjectMocks
    private VlogScriptController controller;

    @Test
    void syncTripsDerivesOwnerFromGoogleSubjectAndAllowsSameDestinationOnDifferentTripIds() {
        OAuth2User principal = mock(OAuth2User.class);
        when(principal.<String>getAttribute("sub")).thenReturn("creator-subject");
        Map<String, Object> firstVisit = Map.of(
                "id", "trip-coorg-october",
                "name", "Coorg",
                "episodes", List.of(Map.of("id", "episode-1", "title", "Episode 1")));
        Map<String, Object> secondVisit = Map.of(
                "id", "trip-coorg-next-visit",
                "name", "Coorg",
                "episodes", List.of(Map.of("id", "episode-1", "title", "Episode 1")));
        when(store.saveTrips(eq("creator-subject"), anyList()))
                .thenReturn(List.of(firstVisit, secondVisit));

        Map<String, Object> response = controller.syncTrips(principal, Map.of(
                "trips", List.of(firstVisit, secondVisit)));

        assertThat((List<?>) response.get("trips")).hasSize(2);
        verify(store).saveTrips(eq("creator-subject"), anyList());
    }

    @Test
    void rewriteVoiceOverSectionsReturnsPreviewForTheAuthenticatedCreator() {
        OAuth2User principal = mock(OAuth2User.class);
        when(principal.<String>getAttribute("sub")).thenReturn("creator-subject");
        Map<String, Object> section = Map.of(
                "id", "p1-hook",
                "title", "Opening hook",
                "type", "Voice-over / Storytelling",
                "timing", "Opening",
                "notes", "Keep it intimate",
                "script", "Kabhi socha hai?");
        Map<String, String> rewritten = Map.of(
                "id", "p1-hook",
                "rewrittenScript", "Kabhi kabhi ek jagah ki kahani, ek sawaal se shuru hoti hai...",
                "changesMade", "Reframed the hook as a spoken curiosity beat.");
        when(storytellingRewriteService.rewriteSections(anyString(), eq("Coorg"), eq("Episode 1"), anyList(), anyList()))
                .thenReturn(List.of(rewritten));

        Map<String, Object> response = controller.rewriteVoiceOverSections(principal, Map.of(
                "destinationName", "Coorg",
                "episodeTitle", "Episode 1",
                "sections", List.of(section)));

        assertThat((List<?>) response.get("sections")).hasSize(1);
        assertThat(((List<?>) response.get("sections")).get(0)).isEqualTo(rewritten);
        assertThat(response.get("requestId")).isNotNull();
        assertThat(response.get("model")).isEqualTo("openai");
        verify(storytellingRewriteService).rewriteSections(anyString(), eq("Coorg"), eq("Episode 1"), anyList(), anyList());
    }

    @Test
    void storyAuditReturnsV23AuditForTheAuthenticatedCreator() {
        OAuth2User principal = mock(OAuth2User.class);
        when(principal.<String>getAttribute("sub")).thenReturn("creator-subject");
        Map<String, Object> section = Map.of(
                "id", "coorg-hook",
                "title", "Opening",
                "type", "Voice-over",
                "timing", "0:00",
                "notes", "Use the real mist shot if available",
                "script", "Coorg ki subah ka ek alag ehsaas hai.");
        Map<String, Object> audit = Map.of(
                "requestId", "audit-123",
                "model", "test-model",
                "storyFormatVersion", "2.3",
                "audit", Map.of("storyCompass", Map.of("episodePromise", "A specific discovery")));
        when(storytellingRewriteService.auditEpisode(anyString(), eq("Coorg"), eq("Episode 2"), anyList()))
                .thenReturn(audit);

        Map<String, Object> response = controller.auditEpisodeStory(principal, Map.of(
                "destinationName", "Coorg",
                "episodeTitle", "Episode 2",
                "sections", List.of(section)));

        assertThat(response).isEqualTo(audit);
        verify(storytellingRewriteService).auditEpisode(anyString(), eq("Coorg"), eq("Episode 2"), anyList());
    }

    @Test
    void storyAuditRequiresAnAuthenticatedCreator() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        controller.auditEpisodeStory(null, Map.of("sections", List.of())))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("Sign in with Google");
    }

    @Test
    void rewriteVoiceOverSectionsRequiresAnAuthenticatedCreator() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        controller.rewriteVoiceOverSections(null, Map.of("sections", List.of())))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("Sign in with Google");
    }

    @Test
    void getTripReturns404WhenTripDoesNotExist() {
        OAuth2User principal = mock(OAuth2User.class);
        when(principal.<String>getAttribute("sub")).thenReturn("creator-subject");
        org.mockito.Mockito.when(store.getTrip("creator-subject", "missing-trip"))
                .thenReturn(java.util.Optional.empty());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.getTrip(principal, "missing-trip"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("Trip not found");
    }

    @Test
    void deleteTripReturnsNoContentAfterStoreConfirmsDeletion() {
        OAuth2User principal = mock(OAuth2User.class);
        when(principal.<String>getAttribute("sub")).thenReturn("creator-subject");
        when(store.deleteTrip("creator-subject", "trip-1")).thenReturn(true);

        var response = controller.deleteTrip(principal, "trip-1");

        assertThat(response.getStatusCode().value()).isEqualTo(204);
    }
}
