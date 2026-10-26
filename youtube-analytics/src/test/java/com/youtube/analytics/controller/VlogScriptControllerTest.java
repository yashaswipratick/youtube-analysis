package com.youtube.analytics.controller;

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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VlogScriptControllerTest {
    @Mock
    private VlogScriptStore store;

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
