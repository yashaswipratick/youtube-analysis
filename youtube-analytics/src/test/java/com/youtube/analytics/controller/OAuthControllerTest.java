package com.youtube.analytics.controller;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OAuthControllerTest {

    @Test
    void oauthSuccessReturnsBackendJsonAfterSignIn() {
        OAuth2User principal = mock(OAuth2User.class);
        when(principal.<String>getAttribute("name")).thenReturn("Test User");
        when(principal.<String>getAttribute("email")).thenReturn("test@example.com");

        var response = new OAuthController().oauthSuccess(principal);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody())
                .containsEntry("status", "authenticated")
                .containsEntry("message", "OAuth2 login successful! You can now call the analytics APIs.")
                .containsEntry("user", "Test User")
                .containsEntry("email", "test@example.com");
    }

    @Test
    void meReturnsAuthenticatedAttributesWithoutGenericOverloadCast() {
        OAuth2User principal = mock(OAuth2User.class);
        when(principal.<String>getAttribute("name")).thenReturn("Test User");
        when(principal.<String>getAttribute("email")).thenReturn("test@example.com");

        Map<String, Object> response = new OAuthController().me(principal).getBody();

        assertThat(response)
                .containsEntry("status", "authenticated")
                .containsEntry("name", "Test User")
                .containsEntry("email", "test@example.com");
    }
}
