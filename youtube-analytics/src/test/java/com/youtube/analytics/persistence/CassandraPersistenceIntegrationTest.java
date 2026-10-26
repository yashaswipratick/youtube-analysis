package com.youtube.analytics.persistence;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.Row;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youtube.analytics.config.CassandraOAuth2AuthorizedClientService;
import com.youtube.analytics.config.CassandraSchemaInitializer;
import com.youtube.analytics.service.creatorcoach.CreatorCoachStore;
import com.youtube.analytics.service.vlogscript.VlogScriptStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "cassandra.integration", matches = "true")
class CassandraPersistenceIntegrationTest {
    private CqlSession session;
    private CreatorCoachStore store;
    private VlogScriptStore vlogScriptStore;
    private String ownerId;

    @BeforeEach
    void setUp() throws Exception {
        session = CqlSession.builder()
                .addContactPoint(new InetSocketAddress("127.0.0.1", 9042))
                .withLocalDatacenter("datacenter1")
                .build();
        new CassandraSchemaInitializer(session, new DefaultResourceLoader()).afterPropertiesSet();
        ObjectMapper mapper = new ObjectMapper();
        store = new CreatorCoachStore(session, mapper);
        vlogScriptStore = new VlogScriptStore(session, mapper);
        ownerId = "cassandra-it-" + UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        if (vlogScriptStore != null && ownerId != null) vlogScriptStore.deleteAllForOwner(ownerId);
        if (store != null && ownerId != null) store.deleteAllForOwner(ownerId);
        if (session != null) session.close();
    }

    @Test
    void creatorRecordsCanBeWrittenReadListedCountedAndDeleted() {
        store.save(ownerId, "video-plan", "plan-1", Map.of("title", "Coorg Episode 1", "status", "draft"));
        store.save(ownerId, "video-plan", "plan-2", Map.of("title", "Coorg Episode 2", "status", "draft"));

        assertThat(store.get(ownerId, "video-plan", "plan-1").orElseThrow())
                .containsEntry("title", "Coorg Episode 1");
        assertThat(store.list(ownerId, "video-plan")).hasSize(2);
        assertThat(store.count(ownerId, "video-plan")).isEqualTo(2);
        assertThat(store.delete(ownerId, "video-plan", "plan-1")).isTrue();
        assertThat(store.get(ownerId, "video-plan", "plan-1")).isEmpty();
        assertThat(store.count(ownerId, "video-plan")).isEqualTo(1);
    }

    @Test
    void repeatVisitsToOneDestinationRemainSeparateTripsAndKeepOrderedEpisodeScripts() {
        Map<String, Object> opening = Map.of(
                "id", "coorg-episode-1",
                "title", "Episode 1 — Arrival",
                "sections", java.util.List.of(Map.of(
                        "id", "hook",
                        "title", "Opening hook",
                        "script", "The road disappears into the mist.",
                        "notes", "Start with a wide shot.")));
        Map<String, Object> secondEpisode = Map.of(
                "id", "coorg-episode-2",
                "title", "Episode 2 — Hidden Coorg",
                "sections", java.util.List.of(Map.of(
                        "id", "reservoir",
                        "title", "Harangi Reservoir",
                        "script", "Sometimes a view has a deeper story.")));

        vlogScriptStore.saveTrip(ownerId, Map.of(
                "id", "trip-coorg-october",
                "name", "Coorg",
                "tripLabel", "Coorg — October 2026",
                "startDate", "2026-10-20",
                "tripType", "Weekend getaway",
                "episodes", java.util.List.of(opening, secondEpisode)));
        vlogScriptStore.saveTrip(ownerId, Map.of(
                "id", "trip-coorg-next-visit",
                "name", "Coorg",
                "tripLabel", "Coorg — Future visit",
                "episodes", java.util.List.of(Map.of(
                        "id", "episode-single",
                        "title", "One-day escape",
                        "sections", java.util.List.of()))));

        var trips = vlogScriptStore.listTrips(ownerId);
        assertThat(trips).hasSize(2);
        assertThat(trips).extracting(trip -> trip.get("name")).containsOnly("Coorg");
        assertThat(trips).extracting(trip -> trip.get("id"))
                .containsExactlyInAnyOrder("trip-coorg-october", "trip-coorg-next-visit");

        Map<String, Object> savedFirstVisit = vlogScriptStore.getTrip(ownerId, "trip-coorg-october").orElseThrow();
        assertThat(savedFirstVisit.get("tripLabel")).isEqualTo("Coorg — October 2026");
        assertThat((java.util.List<?>) savedFirstVisit.get("episodes")).hasSize(2);
        @SuppressWarnings("unchecked")
        Map<String, Object> savedEpisode = (Map<String, Object>) ((java.util.List<?>) savedFirstVisit.get("episodes")).get(0);
        assertThat(savedEpisode.get("id")).isEqualTo("coorg-episode-1");
        @SuppressWarnings("unchecked")
        Map<String, Object> savedSection = (Map<String, Object>) ((java.util.List<?>) savedEpisode.get("sections")).get(0);
        assertThat(savedSection.get("script")).isEqualTo("The road disappears into the mist.");

        assertThat(vlogScriptStore.deleteTrip(ownerId, "trip-coorg-next-visit")).isTrue();
        assertThat(vlogScriptStore.listTrips(ownerId)).hasSize(1);
    }

    @Test
    void oauthTokensAreStoredOnlyInCassandraAndCanBeReloaded() {
        ClientRegistration registration = ClientRegistration.withRegistrationId("google")
                .clientId("test-client")
                .clientSecret("test-client-secret")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .scope("openid", "email")
                .authorizationUri("https://accounts.example.test/oauth2/authorize")
                .tokenUri("https://accounts.example.test/oauth2/token")
                .userInfoUri("https://accounts.example.test/userinfo")
                .userNameAttributeName("sub")
                .clientName("Google")
                .build();
        ClientRegistrationRepository registrations = id -> "google".equals(id) ? registration : null;
        CassandraOAuth2AuthorizedClientService oauthStore =
                new CassandraOAuth2AuthorizedClientService(session, registrations);
        String principal = ownerId + "-oauth";
        Instant now = Instant.now();
        OAuth2AuthorizedClient client = new OAuth2AuthorizedClient(registration, principal,
                new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "access-token-test-secret",
                        now, now.plusSeconds(3600), Set.of("youtube.readonly")),
                new OAuth2RefreshToken("refresh-token-test-secret", now));
        try {
            oauthStore.saveAuthorizedClient(client, new TestingAuthenticationToken(
                    principal, "not-a-secret", "ROLE_USER"));
            Row stored = session.execute(session.prepare("""
                    SELECT access_token_value, refresh_token_value
                    FROM "youtube_analytics".oauth2_authorized_client
                    WHERE client_registration_id=? AND principal_name=?
                    """).bind("google", principal)).one();
            assertThat(stored.getString("access_token_value")).isEqualTo("access-token-test-secret");
            assertThat(stored.getString("refresh_token_value")).isEqualTo("refresh-token-test-secret");
            OAuth2AuthorizedClient loaded = oauthStore.loadAuthorizedClient("google", principal);
            assertThat(loaded).isNotNull();
            assertThat(loaded.getAccessToken().getTokenValue()).isEqualTo("access-token-test-secret");
            assertThat(loaded.getRefreshToken().getTokenValue()).isEqualTo("refresh-token-test-secret");
            assertThat(loaded.getAccessToken().getScopes()).contains("youtube.readonly");
        } finally {
            oauthStore.removeAuthorizedClient("google", principal);
        }
    }
}
