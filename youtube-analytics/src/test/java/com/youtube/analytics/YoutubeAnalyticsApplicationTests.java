package com.youtube.analytics;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "youtube.tokens-directory=/tmp/youtube-test-tokens",
        "spring.security.oauth2.client.registration.google.client-id=test-client-id",
        "spring.security.oauth2.client.registration.google.client-secret=test-client-secret",
        "app.oauth.auto-open-browser=false"
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
}
