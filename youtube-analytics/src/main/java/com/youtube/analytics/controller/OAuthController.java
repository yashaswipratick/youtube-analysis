package com.youtube.analytics.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * OAuth helper endpoints.
 *
 *  GET /oauth/success  — shown after Google OAuth consent completes
 *  GET /oauth/me       — returns the currently authenticated Google user info
 */
@Slf4j
@RestController
@RequestMapping("/oauth")
public class OAuthController {

    @GetMapping("/success")
    public ResponseEntity<Map<String, Object>> oauthSuccess(
            @AuthenticationPrincipal OAuth2User principal) {

        String name  = principal != null ? principal.getAttribute("name")  : "unknown";
        String email = principal != null ? principal.getAttribute("email") : "unknown";

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
                "name", String.valueOf(principal.getAttribute("name")),
                "email", String.valueOf(principal.getAttribute("email"))
        ));
    }
}
