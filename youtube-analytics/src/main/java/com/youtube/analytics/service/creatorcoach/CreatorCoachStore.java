package com.youtube.analytics.service.creatorcoach;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.Row;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.context.annotation.DependsOn;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * JSON-document persistence for creator-owned records.
 *
 * Production writes use Cassandra. The JdbcTemplate constructor remains as a
 * legacy test adapter for the old H2 schema while the migration is verified.
 * Every record is scoped to the authenticated Google subject; callers never
 * supply owner_id through public request payloads.
 */
@Repository
@DependsOn("cassandraSchemaInitializer")
public class CreatorCoachStore {
    private static final String KEYSPACE = "\"youtube_analytics\"";
    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final CqlSession session;
    private final JdbcTemplate legacyJdbc;
    private final ObjectMapper mapper;

    private final PreparedStatement getRecord;
    private final PreparedStatement listRecords;
    private final PreparedStatement listOwnerRecordIds;
    private final PreparedStatement insertRecord;
    private final PreparedStatement insertOwnerRecordId;
    private final PreparedStatement deleteRecord;
    private final PreparedStatement deleteOwnerRecordId;
    private final PreparedStatement deleteOwnerRecordIds;

    @Autowired
    public CreatorCoachStore(CqlSession session, ObjectMapper mapper) {
        this.session = session;
        this.legacyJdbc = null;
        this.mapper = mapper;
        this.getRecord = session.prepare("""
                SELECT payload, created_at, updated_at
                FROM "youtube_analytics".creator_coach_record_by_id
                WHERE owner_id=? AND record_type=? AND record_id=?
                """);
        this.listRecords = session.prepare("""
                SELECT record_id, payload, created_at, updated_at
                FROM "youtube_analytics".creator_coach_record_by_id
                WHERE owner_id=? AND record_type=?
                """);
        this.listOwnerRecordIds = session.prepare("""
                SELECT record_type, record_id
                FROM "youtube_analytics".creator_coach_record_ids_by_owner
                WHERE owner_id=?
                """);
        this.insertRecord = session.prepare("""
                INSERT INTO "youtube_analytics".creator_coach_record_by_id
                    (owner_id, record_type, record_id, payload, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """);
        this.insertOwnerRecordId = session.prepare("""
                INSERT INTO "youtube_analytics".creator_coach_record_ids_by_owner
                    (owner_id, record_type, record_id)
                VALUES (?, ?, ?)
                """);
        this.deleteRecord = session.prepare("""
                DELETE FROM "youtube_analytics".creator_coach_record_by_id
                WHERE owner_id=? AND record_type=? AND record_id=?
                """);
        this.deleteOwnerRecordId = session.prepare("""
                DELETE FROM "youtube_analytics".creator_coach_record_ids_by_owner
                WHERE owner_id=? AND record_type=? AND record_id=?
                """);
        this.deleteOwnerRecordIds = session.prepare("""
                DELETE FROM "youtube_analytics".creator_coach_record_ids_by_owner
                WHERE owner_id=?
                """);
    }

    /**
     * Legacy adapter for isolated H2 unit tests. Spring injects the Cassandra
     * constructor above because it is explicitly @Autowired.
     */
    public CreatorCoachStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.session = null;
        this.legacyJdbc = jdbc;
        this.mapper = mapper;
        this.getRecord = null;
        this.listRecords = null;
        this.listOwnerRecordIds = null;
        this.insertRecord = null;
        this.insertOwnerRecordId = null;
        this.deleteRecord = null;
        this.deleteOwnerRecordId = null;
        this.deleteOwnerRecordIds = null;
    }

    public Map<String, Object> save(String ownerId, String type, String id, Map<String, Object> payload) {
        validateKey(ownerId, type, id);
        Map<String, Object> value = payload == null ? Map.of() : payload;
        try {
            String json = mapper.writeValueAsString(value);
            if (legacyJdbc != null) return saveLegacy(ownerId, type, id, json);
            Row previous = session.execute(getRecord.bind(ownerId, type, id)).one();
            Instant createdAt = previous == null ? Instant.now() : instantOrNow(previous.getInstant("created_at"));
            Instant updatedAt = Instant.now();
            session.execute(insertRecord.bind(ownerId, type, id, json, createdAt, updatedAt));
            session.execute(insertOwnerRecordId.bind(ownerId, type, id));
            return get(ownerId, type, id).orElseGet(() -> parseMap(json));
        } catch (Exception ex) {
            if (ex instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("Could not persist creator data.", ex);
        }
    }

    public Optional<Map<String, Object>> get(String ownerId, String type, String id) {
        if (legacyJdbc != null) {
            List<Map<String, Object>> rows = legacyJdbc.query(
                    "SELECT payload FROM creator_coach_record WHERE owner_id=? AND record_type=? AND record_id=?",
                    (rs, rowNum) -> decode(rs, "payload"), ownerId, type, id);
            return rows.stream().findFirst();
        }
        Row row = session.execute(getRecord.bind(ownerId, type, id)).one();
        return row == null ? Optional.empty() : Optional.of(parseMap(row.getString("payload")));
    }

    public List<Map<String, Object>> list(String ownerId, String type) {
        if (legacyJdbc != null) {
            return legacyJdbc.query("""
                    SELECT record_id, payload, created_at, updated_at
                    FROM creator_coach_record
                    WHERE owner_id=? AND record_type=?
                    ORDER BY updated_at DESC
                    """, (rs, rowNum) -> {
                Map<String, Object> map = decode(rs, "payload");
                map.putIfAbsent("id", rs.getString("record_id"));
                map.put("_createdAt", timestamp(rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toInstant()));
                map.put("_updatedAt", timestamp(rs.getTimestamp("updated_at") == null ? null : rs.getTimestamp("updated_at").toInstant()));
                return map;
            }, ownerId, type);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Row row : session.execute(listRecords.bind(ownerId, type))) {
            Map<String, Object> map = parseMap(row.getString("payload"));
            map.putIfAbsent("id", row.getString("record_id"));
            map.put("_createdAt", timestamp(row.getInstant("created_at")));
            map.put("_updatedAt", timestamp(row.getInstant("updated_at")));
            result.add(map);
        }
        result.sort(Comparator.comparing(
                row -> String.valueOf(row.getOrDefault("_updatedAt", "")),
                Comparator.reverseOrder()));
        return result;
    }

    public boolean delete(String ownerId, String type, String id) {
        if (legacyJdbc != null) {
            return legacyJdbc.update("DELETE FROM creator_coach_record WHERE owner_id=? AND record_type=? AND record_id=?",
                    ownerId, type, id) > 0;
        }
        boolean exists = session.execute(getRecord.bind(ownerId, type, id)).one() != null;
        if (!exists) return false;
        session.execute(deleteRecord.bind(ownerId, type, id));
        session.execute(deleteOwnerRecordId.bind(ownerId, type, id));
        return true;
    }

    public int count(String ownerId, String type) {
        if (legacyJdbc != null) {
            Integer value = legacyJdbc.queryForObject(
                    "SELECT COUNT(*) FROM creator_coach_record WHERE owner_id=? AND record_type=?",
                    Integer.class, ownerId, type);
            return value == null ? 0 : value;
        }
        int count = 0;
        for (Row ignored : session.execute(listRecords.bind(ownerId, type))) count++;
        return count;
    }

    public void deleteAllForOwner(String ownerId) {
        if (ownerId == null || ownerId.isBlank()) {
            throw new IllegalArgumentException("Authenticated creator identity is required.");
        }
        if (legacyJdbc != null) {
            legacyJdbc.update("DELETE FROM creator_coach_record WHERE owner_id=?", ownerId);
            return;
        }
        List<Row> ids = session.execute(listOwnerRecordIds.bind(ownerId)).all();
        for (Row row : ids) {
            session.execute(deleteRecord.bind(ownerId, row.getString("record_type"), row.getString("record_id")));
        }
        session.execute(deleteOwnerRecordIds.bind(ownerId));
    }

    public Map<String, Object> parseMap(String json) {
        try {
            if (json == null || json.isBlank()) return new LinkedHashMap<>();
            return mapper.readValue(json, MAP_TYPE);
        } catch (Exception ex) {
            throw new IllegalStateException("Stored creator data could not be read.", ex);
        }
    }

    private Map<String, Object> saveLegacy(String ownerId, String type, String id, String json) {
        legacyJdbc.update("""
                MERGE INTO creator_coach_record (owner_id, record_type, record_id, payload, created_at, updated_at)
                KEY (owner_id, record_type, record_id)
                VALUES (?, ?, ?, ?, COALESCE(
                  (SELECT created_at FROM creator_coach_record WHERE owner_id=? AND record_type=? AND record_id=?),
                  CURRENT_TIMESTAMP), CURRENT_TIMESTAMP)
                """, ownerId, type, id, json, ownerId, type, id);
        return get(ownerId, type, id).orElse(Map.of());
    }

    private void validateKey(String ownerId, String type, String id) {
        if (ownerId == null || ownerId.isBlank()) {
            throw new IllegalArgumentException("Authenticated creator identity is required.");
        }
        if (type == null || type.isBlank() || id == null || id.isBlank()) {
            throw new IllegalArgumentException("Record type and ID are required.");
        }
    }

    private Map<String, Object> decode(ResultSet rs, String column) throws SQLException {
        return parseMap(rs.getString(column));
    }

    private Instant instantOrNow(Instant value) {
        return value == null ? Instant.now() : value;
    }

    private String timestamp(Instant value) {
        return value == null ? null : value.toString();
    }
}
