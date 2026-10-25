package com.youtube.analytics;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "youtube.tokens-directory=/tmp/youtube-test-tokens",
        "spring.security.oauth2.client.registration.google.client-id=test-client-id",
        "spring.security.oauth2.client.registration.google.client-secret=test-client-secret",
        "app.oauth.auto-open-browser=false",
        "openai.validate-on-startup=false"
})
class YoutubeAnalyticsApplicationTests {

    @Autowired
    private Environment environment;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void contextLoads() {
        assertThat(environment.getProperty("spring.security.oauth2.client.registration.google.client-id"))
                .isNotBlank()
                .doesNotStartWith("${");
        assertThat(environment.getProperty("spring.security.oauth2.client.registration.google.client-secret"))
                .isNotBlank()
                .doesNotStartWith("${");
    }

    @Test
    void analyticsRoutesRequireAnAuthenticatedOAuthSession() throws Exception {
        mockMvc.perform(get("/api/youtube-analytics/v1/analytics/reports/capabilities"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void frontendOriginCanCallApiWithCredentialedCors() throws Exception {
        mockMvc.perform(options("/api/youtube-analytics/v1/oauth/me")
                        .header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("Access-Control-Allow-Origin", "http://localhost:3000"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("Access-Control-Allow-Credentials", "true"));
    }

    @Test
    void backendRootReturnsJsonMetadataAndOAuthStatusIsPublicBeforeLogin() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().contentTypeCompatibleWith("application/json"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.service").value("youtube-analytics-api"));

        // Backend must not expose the separately-hosted dashboard assets.
        mockMvc.perform(get("/index.html"))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(get("/api/youtube-analytics/v1/oauth/me"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.status").value("unauthenticated"));
    }
}
