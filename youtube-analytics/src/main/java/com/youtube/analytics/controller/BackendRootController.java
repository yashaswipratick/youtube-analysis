package com.youtube.analytics.controller;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Backend-only root response. The analytics dashboard is hosted separately. */
@RestController
public class BackendRootController {

    @GetMapping(value = "/", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> root() {
        return Map.of(
                "service", "youtube-analytics-api",
                "status", "running",
                "apiBasePath", "/api/youtube-analytics/v1",
                "oauthAuthorizationUrl", "/oauth2/authorization/google",
                "oauthSuccessUrl", "/api/youtube-analytics/v1/oauth/success"
        );
    }
}
