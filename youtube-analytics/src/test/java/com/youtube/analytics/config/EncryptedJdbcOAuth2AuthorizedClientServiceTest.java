package com.youtube.analytics.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class EncryptedJdbcOAuth2AuthorizedClientServiceTest {
    private JdbcTemplate jdbc;
    private ClientRegistration registration;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource source = new DriverManagerDataSource(
                "jdbc:h2:mem:encrypted_oauth_test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("creator-coach-schema.sql")).execute(source);
        jdbc = new JdbcTemplate(source);
        registration = ClientRegistration.withRegistrationId("google")
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
    }

    @Test
    void persistsAccessAndRefreshTokensOnlyAsCiphertextAndCanReloadThem() {
        ClientRegistrationRepository registrations = id -> "google".equals(id) ? registration : null;
        EncryptedJdbcOAuth2AuthorizedClientService service =
                new EncryptedJdbcOAuth2AuthorizedClientService(jdbc, registrations, "stable-test-key", "client-secret");
        Instant now = Instant.now();
        OAuth2AccessToken access = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,
                "plain-access-token-secret", now, now.plusSeconds(3600), Set.of("youtube.readonly"));
        OAuth2RefreshToken refresh = new OAuth2RefreshToken("plain-refresh-token-secret", now);
        OAuth2AuthorizedClient client = new OAuth2AuthorizedClient(registration, "google-subject", access, refresh);

        service.saveAuthorizedClient(client, new TestingAuthenticationToken("google-subject", "not-a-secret"));

        String storedAccess = jdbc.queryForObject("SELECT access_token_encrypted FROM oauth2_authorized_client WHERE principal_name=?",
                String.class, "google-subject");
        String storedRefresh = jdbc.queryForObject("SELECT refresh_token_encrypted FROM oauth2_authorized_client WHERE principal_name=?",
                String.class, "google-subject");
        assertThat(storedAccess).doesNotContain("plain-access-token-secret");
        assertThat(storedRefresh).doesNotContain("plain-refresh-token-secret");
        assertThat(storedAccess).isNotEqualTo(storedRefresh);

        OAuth2AuthorizedClient loaded = service.loadAuthorizedClient("google", "google-subject");
        assertThat(loaded).isNotNull();
        assertThat(loaded.getAccessToken().getTokenValue()).isEqualTo("plain-access-token-secret");
        assertThat(loaded.getRefreshToken().getTokenValue()).isEqualTo("plain-refresh-token-secret");
        assertThat(loaded.getAccessToken().getScopes()).contains("youtube.readonly");

        service.removeAuthorizedClient("google", "google-subject");
        OAuth2AuthorizedClient removed = service.loadAuthorizedClient("google", "google-subject");
        assertThat((Object) removed).isNull();
    }
}
