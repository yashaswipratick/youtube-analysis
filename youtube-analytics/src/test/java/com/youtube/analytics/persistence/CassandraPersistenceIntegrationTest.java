package com.youtube.analytics.persistence;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.Row;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youtube.analytics.config.CassandraSchemaInitializer;
import com.youtube.analytics.config.LegacyH2DataMigration;
import com.youtube.analytics.config.EncryptedCassandraOAuth2AuthorizedClientService;
import com.youtube.analytics.service.creatorcoach.CreatorCoachStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.DefaultApplicationArguments;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "cassandra.integration", matches = "true")
class CassandraPersistenceIntegrationTest {
    private CqlSession session;
    private CreatorCoachStore store;
    private String ownerId;

    @BeforeEach
    void setUp() throws Exception {
        session = CqlSession.builder()
                .addContactPoint(new InetSocketAddress("127.0.0.1", 9042))
                .withLocalDatacenter("datacenter1")
                .build();
        new CassandraSchemaInitializer(session, new DefaultResourceLoader()).afterPropertiesSet();
        store = new CreatorCoachStore(session, new ObjectMapper());
        ownerId = "cassandra-it-" + UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        if (store != null && ownerId != null) store.deleteAllForOwner(ownerId);
        if (session != null) session.close();
    }

    @Test
    void migratesExistingLegacyH2DataOnceWhenTheLocalFileIsPresent() throws Exception {
        Path databaseBase = Path.of("data/youtube-analytics/creator-coach-db").toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(Path.of(databaseBase + ".mv.db")),
                "No legacy local H2 file is present; there is nothing to migrate in this environment.");

        LegacyH2DataMigration migration =
                new LegacyH2DataMigration(session, databaseBase.toString(), "sa", "");
        migration.run(new DefaultApplicationArguments());

        Row marker = session.execute(session.prepare("""
                SELECT migration_id, details
                FROM youtube_analytics.cassandra_migration_marker
                WHERE migration_id=?
                """).bind("legacy-h2-to-cassandra-v1")).one();
        assertThat(marker).isNotNull();
        assertThat(marker.getString("details"))
                .contains("creatorCoachRecords=3", "oauthAuthorizedClients=1");
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
    void oauthTokensAreStoredEncryptedAndCanBeReloaded() {
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
        EncryptedCassandraOAuth2AuthorizedClientService oauthStore =
                new EncryptedCassandraOAuth2AuthorizedClientService(session, registrations, "stable-test-key", "client-secret");
        String principal = ownerId + "-oauth";
        Instant now = Instant.now();
        OAuth2AuthorizedClient client = new OAuth2AuthorizedClient(registration, principal,
                new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "access-token-test-secret",
                        now, now.plusSeconds(3600), Set.of("youtube.readonly")),
                new OAuth2RefreshToken("refresh-token-test-secret", now));
        try {
            oauthStore.saveAuthorizedClient(client, new TestingAuthenticationToken(principal, "not-a-secret"));
            String cipher = session.execute(session.prepare("""
                    SELECT access_token_encrypted FROM "youtube_analytics".oauth2_authorized_client
                    WHERE client_registration_id=? AND principal_name=?
                    """).bind("google", principal)).one().getString("access_token_encrypted");
            assertThat(cipher).doesNotContain("access-token-test-secret");
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
