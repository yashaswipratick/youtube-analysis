package com.youtube.analytics.config;

import com.datastax.oss.driver.api.core.CqlSession;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

/**
 * OAuth tokens are session-scoped data in Cassandra: clear them before the web
 * application starts accepting requests and during graceful shutdown. The startup
 * clear also removes any legacy encrypted rows left by an older application build.
 */
@Slf4j
@Component
@DependsOn("cassandraSchemaInitializer")
public final class CassandraOAuthTokenLifecycle implements InitializingBean {
    private static final String CLEAR_TOKENS_CQL = "TRUNCATE TABLE \"youtube_analytics\".oauth2_authorized_client";

    private final CqlSession session;

    public CassandraOAuthTokenLifecycle(CqlSession session) {
        this.session = session;
    }

    @Override
    public void afterPropertiesSet() {
        clearTokens("startup");
    }

    @PreDestroy
    public void clearAtShutdown() {
        try {
            clearTokens("shutdown");
        } catch (RuntimeException exception) {
            // Startup always performs another clear; do not mask shutdown with a Cassandra outage.
            log.warn("Could not clear the Cassandra OAuth token table during shutdown; it will be cleared on next startup.");
        }
    }

    private void clearTokens(String lifecycleStage) {
        session.execute(CLEAR_TOKENS_CQL);
        log.info("Cleared the Cassandra OAuth token table during {}. Google authorization is required for this application run.",
                lifecycleStage);
    }
}
