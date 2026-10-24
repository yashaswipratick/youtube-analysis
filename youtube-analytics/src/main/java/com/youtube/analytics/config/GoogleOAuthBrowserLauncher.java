package com.youtube.analytics.config;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.net.URI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Opens Google's OAuth login flow in the default browser for local development.
 * Google still controls sign-in and consent; this class only opens the browser URL.
 */
@Component
public class GoogleOAuthBrowserLauncher {

    private static final Logger log = LoggerFactory.getLogger(GoogleOAuthBrowserLauncher.class);

    private final boolean enabled;
    private final boolean sslEnabled;
    private final String contextPath;

    public GoogleOAuthBrowserLauncher(
            @Value("${app.oauth.auto-open-browser:true}") boolean enabled,
            @Value("${server.ssl.enabled:false}") boolean sslEnabled,
            @Value("${server.servlet.context-path:}") String contextPath) {
        this.enabled = enabled;
        this.sslEnabled = sslEnabled;
        this.contextPath = contextPath == null ? "" : contextPath.trim();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void openGoogleAuthorizationPage(ApplicationReadyEvent event) {
        if (!enabled) {
            log.info("Automatic Google OAuth browser launch is disabled (app.oauth.auto-open-browser=false).");
            return;
        }

        if (!(event.getApplicationContext() instanceof WebServerApplicationContext webServerContext)) {
            log.info("No web server is available for automatic Google OAuth launch.");
            return;
        }

        String url = authorizationUrl(webServerContext);
        if (GraphicsEnvironment.isHeadless() || !Desktop.isDesktopSupported()
                || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            log.info("No desktop browser is available. Open this URL to authorize Google OAuth: {}", url);
            return;
        }

        try {
            Desktop.getDesktop().browse(URI.create(url));
            log.info("Opened Google OAuth authorization page: {}", url);
        } catch (IOException | IllegalArgumentException | UnsupportedOperationException ex) {
            log.warn("Could not open a browser automatically. Open this URL manually: {}", url, ex);
        }
    }

    private String authorizationUrl(WebServerApplicationContext applicationContext) {
        int port = applicationContext.getWebServer().getPort();
        String scheme = sslEnabled ? "https" : "http";
        String normalizedContextPath = contextPath.isBlank()
                ? ""
                : "/" + contextPath.replaceAll("^/+|/+$", "");
        return scheme + "://localhost:" + port + normalizedContextPath + "/oauth2/authorization/google";
    }
}
