package com.youtube.analytics.config;

/**
 * YouTube API configuration is handled via application.yml.
 * OAuth2 client credentials (client-id, client-secret) are set under:
 *
 *   spring.security.oauth2.client.registration.google.*
 *
 * YouTube-specific settings (default metrics, date windows) are set under:
 *
 *   youtube.analytics.*
 *
 * See application.yml for the full configuration reference.
 */
public final class YouTubeConfig {
    private YouTubeConfig() {}
}
