package com.youtube.analytics.config;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.Row;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.DependsOn;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Locale;

/**
 * One-time, read-only import of creator records and encrypted OAuth tokens from the
 * previous local H2 file into Cassandra. The original H2 file remains untouched.
 */
@Slf4j
@Component
@DependsOn("cassandraSchemaInitializer")
@ConditionalOnProperty(name = "app.storage.migrate-legacy-h2", havingValue = "true", matchIfMissing = true)
public class LegacyH2DataMigration implements ApplicationRunner {
    private static final String MIGRATION_ID = "legacy-h2-to-cassandra-v1";

    private final CqlSession session;
    private final String databasePath;
    private final String username;
    private final String password;

    public LegacyH2DataMigration(
            CqlSession session,
            @Value("${app.storage.database-path:./data/youtube-analytics/creator-coach-db}") String databasePath,
            @Value("${CREATOR_DB_USERNAME:sa}") String username,
            @Value("${CREATOR_DB_PASSWORD:}") String password) {
        this.session = session;
        this.databasePath = databasePath;
        this.username = username;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        PreparedStatement markerQuery = session.prepare(
                "SELECT migration_id FROM \"youtube_analytics\".cassandra_migration_marker WHERE migration_id=?");
        Row marker = session.execute(markerQuery.bind(MIGRATION_ID)).one();
        if (marker != null) {
            log.info("Legacy H2 to Cassandra migration already completed; leaving existing Cassandra data unchanged.");
            return;
        }

        Path base = Path.of(databasePath).toAbsolutePath().normalize();
        if (!Files.isRegularFile(Path.of(base + ".mv.db"))) {
            log.info("No legacy H2 database file found at configured path; Cassandra will be used for new application data.");
            return;
        }

        Class.forName("org.h2.Driver");
        String url = "jdbc:h2:file:" + base + ";IFEXISTS=TRUE;ACCESS_MODE_DATA=r;DB_CLOSE_ON_EXIT=FALSE";
        long recordCount = 0;
        long tokenCount = 0;
        try (Connection connection = DriverManager.getConnection(url, username, password)) {
            boolean recordsTable = tableExists(connection, "CREATOR_COACH_RECORD");
            boolean tokensTable = tableExists(connection, "OAUTH2_AUTHORIZED_CLIENT");
            if (!recordsTable && !tokensTable) {
                log.warn("Legacy H2 file is present but contains no known creator tables; no data was migrated.");
                return;
            }
            if (recordsTable) recordCount = migrateCreatorRecords(connection);
            if (tokensTable) tokenCount = migrateOAuthTokens(connection);
        } catch (SQLException ex) {
            throw new IllegalStateException("Could not read the legacy H2 database in read-only mode. Stop any old application process and restart to retry the migration.", ex);
        }

        PreparedStatement markerInsert = session.prepare("""
                INSERT INTO "youtube_analytics".cassandra_migration_marker (migration_id, applied_at, details)
                VALUES (?, ?, ?)
                """);
        session.execute(markerInsert.bind(MIGRATION_ID, Instant.now(),
                "creatorCoachRecords=" + recordCount + ",oauthAuthorizedClients=" + tokenCount));
        log.info("Migrated {} creator records and {} encrypted OAuth authorized-client rows from legacy H2 into Cassandra.", recordCount, tokenCount);
    }

    private long migrateCreatorRecords(Connection connection) throws SQLException {
        PreparedStatement save = session.prepare("""
                INSERT INTO "youtube_analytics".creator_coach_record_by_id
                    (owner_id, record_type, record_id, payload, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """);
        PreparedStatement ownerIndex = session.prepare("""
                INSERT INTO "youtube_analytics".creator_coach_record_ids_by_owner
                    (owner_id, record_type, record_id)
                VALUES (?, ?, ?)
                """);
        long count = 0;
        try (var query = connection.prepareStatement("""
                SELECT owner_id, record_type, record_id, payload, created_at, updated_at
                FROM creator_coach_record
                """);
             ResultSet rows = query.executeQuery()) {
            while (rows.next()) {
                String ownerId = rows.getString("owner_id");
                String recordType = rows.getString("record_type");
                String recordId = rows.getString("record_id");
                String payload = rows.getString("payload");
                Instant createdAt = instant(rows.getTimestamp("created_at"));
                Instant updatedAt = instant(rows.getTimestamp("updated_at"));
                session.execute(save.bind(ownerId, recordType, recordId, payload,
                        createdAt == null ? Instant.now() : createdAt,
                        updatedAt == null ? Instant.now() : updatedAt));
                session.execute(ownerIndex.bind(ownerId, recordType, recordId));
                count++;
            }
        }
        return count;
    }

    private long migrateOAuthTokens(Connection connection) throws SQLException {
        PreparedStatement save = session.prepare("""
                INSERT INTO "youtube_analytics".oauth2_authorized_client (
                    client_registration_id, principal_name, access_token_encrypted,
                    access_token_issued_at, access_token_expires_at, access_token_scopes,
                    refresh_token_encrypted, refresh_token_issued_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """);
        long count = 0;
        try (var query = connection.prepareStatement("""
                SELECT client_registration_id, principal_name, access_token_encrypted,
                       access_token_issued_at, access_token_expires_at, access_token_scopes,
                       refresh_token_encrypted, refresh_token_issued_at
                FROM oauth2_authorized_client
                """);
             ResultSet rows = query.executeQuery()) {
            while (rows.next()) {
                // Preserve ciphertext verbatim; the active Cassandra service uses the same AES-GCM key derivation.
                session.execute(save.bind(
                        rows.getString("client_registration_id"),
                        rows.getString("principal_name"),
                        rows.getString("access_token_encrypted"),
                        instant(rows.getTimestamp("access_token_issued_at")),
                        instant(rows.getTimestamp("access_token_expires_at")),
                        rows.getString("access_token_scopes"),
                        rows.getString("refresh_token_encrypted"),
                        instant(rows.getTimestamp("refresh_token_issued_at"))));
                count++;
            }
        }
        return count;
    }

    private boolean tableExists(Connection connection, String tableName) throws SQLException {
        try (ResultSet tables = connection.getMetaData().getTables(null, null, tableName.toUpperCase(Locale.ROOT), new String[]{"TABLE"})) {
            if (tables.next()) return true;
        }
        try (ResultSet tables = connection.getMetaData().getTables(null, null, tableName.toLowerCase(Locale.ROOT), new String[]{"TABLE"})) {
            return tables.next();
        }
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
