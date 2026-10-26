package com.youtube.analytics.service.creatorcoach;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CreatorCoachStoreTest {
    private CreatorCoachStore store;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:creator_coach_store;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("creator-coach-schema.sql")).execute(dataSource);
        store = new CreatorCoachStore(new JdbcTemplate(dataSource), new ObjectMapper());
    }

    @Test
    void savesUpdatesAndScopesRecordsToAuthenticatedOwner() {
        store.save("google-sub-1", "creator-profile", "mine", Map.of("output_language", "hinglish", "must_keep", List.of("cinematic opening")));
        assertThat(store.get("google-sub-1", "creator-profile", "mine").orElseThrow())
                .containsEntry("output_language", "hinglish");
        store.save("google-sub-1", "creator-profile", "mine", Map.of("output_language", "en"));
        assertThat(store.get("google-sub-1", "creator-profile", "mine").orElseThrow())
                .containsEntry("output_language", "en");
        assertThat(store.get("google-sub-2", "creator-profile", "mine")).isEmpty();
        assertThat(store.count("google-sub-1", "creator-profile")).isEqualTo(1);
        assertThat(store.list("google-sub-1", "creator-profile")).hasSize(1);
        assertThat(store.delete("google-sub-1", "creator-profile", "mine")).isTrue();
        assertThat(store.get("google-sub-1", "creator-profile", "mine")).isEmpty();
    }
}
