package com.youtube.analytics.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;

/**
 * Routes every authorized-client read/write directly to the Cassandra-backed service.
 * It deliberately does not fall back to HttpSessionOAuth2AuthorizedClientRepository
 * or another local/session cache for anonymous principals.
 */
public final class CassandraOnlyOAuth2AuthorizedClientRepository implements OAuth2AuthorizedClientRepository {
    private final OAuth2AuthorizedClientService authorizedClientService;

    public CassandraOnlyOAuth2AuthorizedClientRepository(OAuth2AuthorizedClientService authorizedClientService) {
        this.authorizedClientService = authorizedClientService;
    }

    @Override
    public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(
            String clientRegistrationId, Authentication principal, HttpServletRequest request) {
        if (!isAuthenticatedUser(principal)) {
            return null;
        }
        return authorizedClientService.loadAuthorizedClient(clientRegistrationId, principal.getName());
    }

    @Override
    public void saveAuthorizedClient(
            OAuth2AuthorizedClient authorizedClient,
            Authentication principal,
            HttpServletRequest request,
            HttpServletResponse response) {
        if (!isAuthenticatedUser(principal)) {
            throw new IllegalStateException("Refusing to store an OAuth token without an authenticated principal.");
        }
        authorizedClientService.saveAuthorizedClient(authorizedClient, principal);
    }

    @Override
    public void removeAuthorizedClient(
            String clientRegistrationId,
            Authentication principal,
            HttpServletRequest request,
            HttpServletResponse response) {
        if (isAuthenticatedUser(principal)) {
            authorizedClientService.removeAuthorizedClient(clientRegistrationId, principal.getName());
        }
    }

    private boolean isAuthenticatedUser(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)
                && authentication.getName() != null
                && !authentication.getName().isBlank()
                && !"anonymousUser".equals(authentication.getName());
    }
}
