package com.youtube.analytics.config;

import com.datastax.oss.driver.api.core.CqlSession;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Collectors;

/** Creates the local keyspace and the app's tables idempotently at startup. */
@Component
public class CassandraSchemaInitializer implements InitializingBean {
    private final CqlSession session;
    private final ResourceLoader resourceLoader;

    public CassandraSchemaInitializer(CqlSession session, ResourceLoader resourceLoader) {
        this.session = session;
        this.resourceLoader = resourceLoader;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        String cql = "CREATE KEYSPACE IF NOT EXISTS \"youtube_analytics\" "
                + "WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1} "
                + "AND durable_writes = true";
        session.execute(cql);

        Resource resource = resourceLoader.getResource("classpath:cassandra-schema.cql");
        String source;
        try (var input = resource.getInputStream()) {
            source = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (String statement : source.split(";")) {
            String normalized = Arrays.stream(statement.split("\\R"))
                    .filter(line -> !line.stripLeading().startsWith("--"))
                    .collect(Collectors.joining("\n"))
                    .trim();
            if (!normalized.isEmpty()) {
                try {
                    session.execute(normalized);
                } catch (RuntimeException ex) {
                    throw new IllegalStateException("Could not apply Cassandra schema statement: " + normalized, ex);
                }
            }
        }
    }
}
