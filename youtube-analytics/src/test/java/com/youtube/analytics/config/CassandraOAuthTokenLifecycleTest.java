package com.youtube.analytics.config;

import com.datastax.oss.driver.api.core.CqlSession;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class CassandraOAuthTokenLifecycleTest {

    @Test
    void clearsCassandraTokenTableAtStartupAndGracefulShutdown() {
        CqlSession session = mock(CqlSession.class);
        CassandraOAuthTokenLifecycle lifecycle = new CassandraOAuthTokenLifecycle(session);

        lifecycle.afterPropertiesSet();
        verify(session).execute("TRUNCATE TABLE \"youtube_analytics\".oauth2_authorized_client");

        lifecycle.clearAtShutdown();
        verify(session, times(2)).execute("TRUNCATE TABLE \"youtube_analytics\".oauth2_authorized_client");
    }
}
