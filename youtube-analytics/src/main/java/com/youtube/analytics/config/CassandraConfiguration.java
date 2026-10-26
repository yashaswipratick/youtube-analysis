package com.youtube.analytics.config;

import com.datastax.oss.driver.api.core.CqlSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.InetSocketAddress;

/** Local Cassandra connection. The keyspace is created by CassandraSchemaInitializer. */
@Configuration
public class CassandraConfiguration {
    @Bean(destroyMethod = "close")
    public CqlSession cassandraSession(
            @Value("${app.cassandra.contact-point:127.0.0.1}") String contactPoint,
            @Value("${app.cassandra.port:9042}") int port,
            @Value("${app.cassandra.local-datacenter:datacenter1}") String localDatacenter) {
        return CqlSession.builder()
                .addContactPoint(new InetSocketAddress(contactPoint, port))
                .withLocalDatacenter(localDatacenter)
                .build();
    }
}
