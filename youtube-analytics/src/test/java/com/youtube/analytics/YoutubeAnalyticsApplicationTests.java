package com.youtube.analytics;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
        "youtube.client-secret-file=test_client_secret.json",
        "youtube.tokens-directory=/tmp/youtube-test-tokens"
})
class YoutubeAnalyticsApplicationTests {

    @Test
    void contextLoads() {
        // Context loads without OAuth (beans are @Lazy)
    }
}
