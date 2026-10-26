package com.youtube.analytics.service.creatorcoach;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.Row;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Cassandra JSON-document persistence for creator-owned records.
 * Every record is scoped to the authenticated Google subject; callers never
 * supply owner_id through public request payloads.
 */
@Repository
@DependsOn("cassandraSchemaInitializer")
public class CreatorCoachStore {
    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final CqlSession session;
    private final ObjectMapper mapper;

    private final PreparedStatement getRecord;
    private final PreparedStatement listRecords;
    private final PreparedStatement listOwnerRecordIds;
    private final PreparedStatement insertRecord;
    private final PreparedStatement insertOwnerRecordId;
    private final PreparedStatement deleteRecord;
    private final PreparedStatement deleteOwnerRecordId;
    private final PreparedStatement deleteOwnerRecordIds;

    public CreatorCoachStore(CqlSession session, ObjectMapper mapper) {
        this.session = session;
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

    public Map<String, Object> save(String ownerId, String type, String id, Map<String, Object> payload) {
        validateKey(ownerId, type, id);
        Map<String, Object> value = payload == null ? Map.of() : payload;
        try {
            String json = mapper.writeValueAsString(value);
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
        Row row = session.execute(getRecord.bind(ownerId, type, id)).one();
        return row == null ? Optional.empty() : Optional.of(parseMap(row.getString("payload")));
    }

    public List<Map<String, Object>> list(String ownerId, String type) {
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
        boolean exists = session.execute(getRecord.bind(ownerId, type, id)).one() != null;
        if (!exists) return false;
        session.execute(deleteRecord.bind(ownerId, type, id));
        session.execute(deleteOwnerRecordId.bind(ownerId, type, id));
        return true;
    }

    public int count(String ownerId, String type) {
        int count = 0;
        for (Row ignored : session.execute(listRecords.bind(ownerId, type))) count++;
        return count;
    }

    public void deleteAllForOwner(String ownerId) {
        if (ownerId == null || ownerId.isBlank()) {
            throw new IllegalArgumentException("Authenticated creator identity is required.");
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

    private void validateKey(String ownerId, String type, String id) {
        if (ownerId == null || ownerId.isBlank()) {
            throw new IllegalArgumentException("Authenticated creator identity is required.");
        }
        if (type == null || type.isBlank() || id == null || id.isBlank()) {
            throw new IllegalArgumentException("Record type and ID are required.");
        }
    }

    private Instant instantOrNow(Instant value) {
        return value == null ? Instant.now() : value;
    }

    private String timestamp(Instant value) {
        return value == null ? null : value.toString();
    }
}
