package com.youtube.analytics;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
        "youtube.client-secret-file=test_client_secret.json",
        "youtube.tokens-directory=/tmp/youtube-test-tokens"
})
class YoutubeAnalyticsApplicationTests {

    @Autowired
    private Environment environment;

    @Test
    void contextLoads() {
        assertThat(environment.getProperty("spring.security.oauth2.client.registration.google.client-id"))
                .isNotBlank()
                .doesNotStartWith("${");
        assertThat(environment.getProperty("spring.security.oauth2.client.registration.google.client-secret"))
                .isNotBlank()
                .doesNotStartWith("${");
    }
}
