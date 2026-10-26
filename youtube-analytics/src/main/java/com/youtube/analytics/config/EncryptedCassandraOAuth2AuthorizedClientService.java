package com.youtube.analytics.config;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.Row;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Set;

/** Persists OAuth tokens encrypted with AES-GCM in Cassandra. Tokens never leave the server or appear in logs. */
@Service
@DependsOn("cassandraSchemaInitializer")
public class EncryptedCassandraOAuth2AuthorizedClientService implements OAuth2AuthorizedClientService {
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final CqlSession session;
    private final ClientRegistrationRepository registrations;
    private final byte[] key;
    private final SecureRandom random = new SecureRandom();
    private final PreparedStatement loadClient;
    private final PreparedStatement saveClient;
    private final PreparedStatement deleteClient;

    public EncryptedCassandraOAuth2AuthorizedClientService(
            CqlSession session,
            ClientRegistrationRepository registrations,
            @Value("${app.oauth.token-encryption-key:}") String configuredKey,
            @Value("${spring.security.oauth2.client.registration.google.client-secret:}") String clientSecret) {
        this.session = session;
        this.registrations = registrations;
        String source = configuredKey == null || configuredKey.isBlank() ? clientSecret : configuredKey;
        if (source == null || source.isBlank()) {
            throw new IllegalStateException("Set YOUTUBE_TOKEN_ENCRYPTION_KEY (recommended) or GOOGLE_CLIENT_SECRET to encrypt stored OAuth tokens.");
        }
        try {
            this.key = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("Could not initialize OAuth token encryption.", ex);
        }
        this.loadClient = session.prepare("""
                SELECT access_token_encrypted, access_token_issued_at, access_token_expires_at,
                       access_token_scopes, refresh_token_encrypted, refresh_token_issued_at
                FROM "youtube_analytics".oauth2_authorized_client
                WHERE client_registration_id=? AND principal_name=?
                """);
        this.saveClient = session.prepare("""
                INSERT INTO "youtube_analytics".oauth2_authorized_client (
                    client_registration_id, principal_name, access_token_encrypted,
                    access_token_issued_at, access_token_expires_at, access_token_scopes,
                    refresh_token_encrypted, refresh_token_issued_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """);
        this.deleteClient = session.prepare("""
                DELETE FROM "youtube_analytics".oauth2_authorized_client
                WHERE client_registration_id=? AND principal_name=?
                """);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(String clientRegistrationId, String principalName) {
        Row row = session.execute(loadClient.bind(clientRegistrationId, principalName)).one();
        if (row == null) return null;
        ClientRegistration registration = registrations.findByRegistrationId(clientRegistrationId);
        if (registration == null) return null;

        Instant issuedAt = row.getInstant("access_token_issued_at");
        Instant expiresAt = row.getInstant("access_token_expires_at");
        Set<String> scopes = new LinkedHashSet<>();
        String scopesText = row.getString("access_token_scopes");
        if (scopesText != null && !scopesText.isBlank()) {
            for (String scope : scopesText.split(" ")) if (!scope.isBlank()) scopes.add(scope);
        }
        OAuth2AccessToken accessToken = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,
                decrypt(row.getString("access_token_encrypted")), issuedAt, expiresAt, scopes);
        String refreshCipher = row.getString("refresh_token_encrypted");
        OAuth2RefreshToken refreshToken = refreshCipher == null || refreshCipher.isBlank() ? null
                : new OAuth2RefreshToken(decrypt(refreshCipher), row.getInstant("refresh_token_issued_at"));
        return (T) new OAuth2AuthorizedClient(registration, principalName, accessToken, refreshToken);
    }

    @Override
    public void saveAuthorizedClient(OAuth2AuthorizedClient client, Authentication principal) {
        String principalName = principal.getName();
        String access = encrypt(client.getAccessToken().getTokenValue());
        OAuth2RefreshToken refreshToken = client.getRefreshToken();
        String refresh = refreshToken == null ? null : encrypt(refreshToken.getTokenValue());
        Set<String> scopes = client.getAccessToken().getScopes();
        String scopeText = scopes == null ? "" : String.join(" ", scopes);
        session.execute(saveClient.bind(
                client.getClientRegistration().getRegistrationId(), principalName, access,
                client.getAccessToken().getIssuedAt(), client.getAccessToken().getExpiresAt(),
                scopeText, refresh, refreshToken == null ? null : refreshToken.getIssuedAt()));
    }

    @Override
    public void removeAuthorizedClient(String clientRegistrationId, String principalName) {
        session.execute(deleteClient.bind(clientRegistrationId, principalName));
    }

    private String encrypt(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] output = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, output, 0, nonce.length);
            System.arraycopy(encrypted, 0, output, nonce.length, encrypted.length);
            return Base64.getEncoder().encodeToString(output);
        } catch (Exception ex) {
            throw new IllegalStateException("Could not encrypt OAuth token. The token was not stored.", ex);
        }
    }

    private String decrypt(String value) {
        try {
            byte[] combined = Base64.getDecoder().decode(value);
            if (combined.length <= NONCE_BYTES) throw new IllegalArgumentException("Invalid encrypted token");
            byte[] nonce = java.util.Arrays.copyOfRange(combined, 0, NONCE_BYTES);
            byte[] encrypted = java.util.Arrays.copyOfRange(combined, NONCE_BYTES, combined.length);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("Stored OAuth token could not be decrypted. Reconnect YouTube and check YOUTUBE_TOKEN_ENCRYPTION_KEY.", ex);
        }
    }
}
