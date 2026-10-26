package com.youtube.analytics.config;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.Row;
import org.springframework.context.annotation.DependsOn;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The sole authorized-client store. Access and refresh token values are stored only
 * in the local Cassandra table. This service keeps no token cache of its own.
 * Tokens are cleared at startup and shutdown so a fresh OAuth authorization is
 * required for each application run.
 *
 * Google OAuth tokens necessarily exist transiently in request-processing memory
 * while an authenticated HTTP request is made; they are not cached in a session.
 */
@Service
@DependsOn("cassandraSchemaInitializer")
public class CassandraOAuth2AuthorizedClientService implements OAuth2AuthorizedClientService {
    private final CqlSession session;
    private final ClientRegistrationRepository registrations;
    private final PreparedStatement loadClient;
    private final PreparedStatement saveClient;
    private final PreparedStatement deleteClient;

    public CassandraOAuth2AuthorizedClientService(
            CqlSession session,
            ClientRegistrationRepository registrations) {
        this.session = session;
        this.registrations = registrations;
        this.loadClient = session.prepare("""
                SELECT access_token_value, access_token_issued_at, access_token_expires_at,
                       access_token_scopes, refresh_token_value, refresh_token_issued_at
                FROM "youtube_analytics".oauth2_authorized_client
                WHERE client_registration_id=? AND principal_name=?
                """);
        this.saveClient = session.prepare("""
                INSERT INTO "youtube_analytics".oauth2_authorized_client (
                    client_registration_id, principal_name, access_token_value,
                    access_token_issued_at, access_token_expires_at, access_token_scopes,
                    refresh_token_value, refresh_token_issued_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """);
        this.deleteClient = session.prepare("""
                DELETE FROM "youtube_analytics".oauth2_authorized_client
                WHERE client_registration_id=? AND principal_name=?
                """);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(
            String clientRegistrationId, String principalName) {
        Row row = session.execute(loadClient.bind(clientRegistrationId, principalName)).one();
        if (row == null) {
            return null;
        }

        ClientRegistration registration = registrations.findByRegistrationId(clientRegistrationId);
        if (registration == null) {
            return null;
        }

        String accessTokenValue = row.getString("access_token_value");
        if (accessTokenValue == null || accessTokenValue.isBlank()) {
            return null;
        }

        Instant issuedAt = row.getInstant("access_token_issued_at");
        Instant expiresAt = row.getInstant("access_token_expires_at");
        Set<String> scopes = new LinkedHashSet<>();
        String scopesText = row.getString("access_token_scopes");
        if (scopesText != null && !scopesText.isBlank()) {
            for (String scope : scopesText.split(" ")) {
                if (!scope.isBlank()) {
                    scopes.add(scope);
                }
            }
        }

        OAuth2AccessToken accessToken = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER, accessTokenValue, issuedAt, expiresAt, scopes);
        String refreshTokenValue = row.getString("refresh_token_value");
        Instant refreshIssuedAt = row.getInstant("refresh_token_issued_at");
        OAuth2RefreshToken refreshToken = refreshTokenValue == null || refreshTokenValue.isBlank()
                ? null : new OAuth2RefreshToken(refreshTokenValue, refreshIssuedAt);

        return (T) new OAuth2AuthorizedClient(registration, principalName, accessToken, refreshToken);
    }

    @Override
    public void saveAuthorizedClient(OAuth2AuthorizedClient client, Authentication principal) {
        if (client == null || principal == null || principal.getName() == null || principal.getName().isBlank()) {
            throw new IllegalArgumentException("An authenticated Google principal is required to store an OAuth token.");
        }

        String principalName = principal.getName();
        OAuth2AccessToken accessToken = client.getAccessToken();
        OAuth2RefreshToken refreshToken = client.getRefreshToken();
        Set<String> scopes = accessToken.getScopes();
        String scopeText = scopes == null ? "" : String.join(" ", scopes);

        session.execute(saveClient.bind(
                client.getClientRegistration().getRegistrationId(),
                principalName,
                accessToken.getTokenValue(),
                accessToken.getIssuedAt(),
                accessToken.getExpiresAt(),
                scopeText,
                refreshToken == null ? null : refreshToken.getTokenValue(),
                refreshToken == null ? null : refreshToken.getIssuedAt()));
    }

    @Override
    public void removeAuthorizedClient(String clientRegistrationId, String principalName) {
        session.execute(deleteClient.bind(clientRegistrationId, principalName));
    }
}
