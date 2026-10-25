package com.youtube.analytics.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.net.URI;

import java.util.Map;
import java.util.Objects;

/**
 * OAuth helper endpoints.
 *
 *  GET /api/youtube-analytics/v1/oauth/success — shown after Google OAuth consent completes
 *  GET /api/youtube-analytics/v1/oauth/me      — returns the currently authenticated Google user info
 */
@Slf4j
@RestController
@RequestMapping("/api/youtube-analytics/v1/oauth")
public class OAuthController {

    public static final String RETURN_TO_UI_SESSION_ATTRIBUTE = "YOUTUBE_ANALYTICS_RETURN_TO_UI";

    /** Start OAuth from the separate UI. Direct visits to Spring Security's OAuth URL remain backend-only. */
    @GetMapping("/start")
    public ResponseEntity<Void> startFromUi(HttpSession session) {
        session.setAttribute(RETURN_TO_UI_SESSION_ATTRIBUTE, Boolean.TRUE);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create("/oauth2/authorization/google"))
                .build();
    }

    @GetMapping("/success")
    public ResponseEntity<Map<String, Object>> oauthSuccess(
            @AuthenticationPrincipal OAuth2User principal) {

        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "status", "unauthenticated",
                    "message", "Complete Google OAuth sign-in before opening this endpoint."
            ));
        }

        String name  = Objects.toString((Object) principal.getAttribute("name"), "");
        String email = Objects.toString((Object) principal.getAttribute("email"), "");

        log.info("OAuth2 login successful for: {} ({})", name, email);

        return ResponseEntity.ok(Map.of(
                "status", "authenticated",
                "message", "OAuth2 login successful! You can now call the analytics APIs.",
                "user", name,
                "email", email
        ));
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(
            @AuthenticationPrincipal OAuth2User principal) {

        if (principal == null) {
            return ResponseEntity.ok(Map.of(
                    "status", "unauthenticated",
                    "loginUrl", "/oauth2/authorization/google"
            ));
        }

        return ResponseEntity.ok(Map.of(
                "status", "authenticated",
                // OAuth2User#getAttribute is generic; String.valueOf has a char[] overload,
                // which can make Java infer char[] and cause a runtime ClassCastException.
                "name", Objects.toString((Object) principal.getAttribute("name"), ""),
                "email", Objects.toString((Object) principal.getAttribute("email"), "")
        ));
    }
}
