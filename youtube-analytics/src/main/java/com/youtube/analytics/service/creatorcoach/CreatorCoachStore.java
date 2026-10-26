package com.youtube.analytics.service.creatorcoach;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Small JSON-document persistence layer for creator-owned records.
 * Each row is scoped to the authenticated Google subject; callers never supply owner_id.
 */
@Repository
public class CreatorCoachStore {
    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public CreatorCoachStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public Map<String, Object> save(String ownerId, String type, String id, Map<String, Object> payload) {
        if (ownerId == null || ownerId.isBlank()) throw new IllegalArgumentException("Authenticated creator identity is required.");
        if (type == null || type.isBlank() || id == null || id.isBlank()) throw new IllegalArgumentException("Record type and ID are required.");
        try {
            String json = mapper.writeValueAsString(payload == null ? Map.of() : payload);
            jdbc.update("""
                    MERGE INTO creator_coach_record (owner_id, record_type, record_id, payload, created_at, updated_at)
                    KEY (owner_id, record_type, record_id)
                    VALUES (?, ?, ?, ?, COALESCE(
                      (SELECT created_at FROM creator_coach_record WHERE owner_id=? AND record_type=? AND record_id=?),
                      CURRENT_TIMESTAMP), CURRENT_TIMESTAMP)
                    """, ownerId, type, id, json, ownerId, type, id);
            return get(ownerId, type, id).orElse(Map.of());
        } catch (Exception ex) {
            if (ex instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("Could not persist creator data.", ex);
        }
    }

    public Optional<Map<String, Object>> get(String ownerId, String type, String id) {
        List<Map<String, Object>> rows = jdbc.query(
                "SELECT payload FROM creator_coach_record WHERE owner_id=? AND record_type=? AND record_id=?",
                (rs, rowNum) -> decode(rs, "payload"), ownerId, type, id);
        return rows.stream().findFirst();
    }

    public List<Map<String, Object>> list(String ownerId, String type) {
        return jdbc.query("""
                SELECT record_id, payload, created_at, updated_at
                FROM creator_coach_record
                WHERE owner_id=? AND record_type=?
                ORDER BY updated_at DESC
                """, (rs, rowNum) -> {
            Map<String, Object> map = decode(rs, "payload");
            map.putIfAbsent("id", rs.getString("record_id"));
            map.put("_createdAt", rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toInstant().toString());
            map.put("_updatedAt", rs.getTimestamp("updated_at") == null ? null : rs.getTimestamp("updated_at").toInstant().toString());
            return map;
        }, ownerId, type);
    }

    public boolean delete(String ownerId, String type, String id) {
        return jdbc.update("DELETE FROM creator_coach_record WHERE owner_id=? AND record_type=? AND record_id=?",
                ownerId, type, id) > 0;
    }

    public int count(String ownerId, String type) {
        Integer value = jdbc.queryForObject("SELECT COUNT(*) FROM creator_coach_record WHERE owner_id=? AND record_type=?",
                Integer.class, ownerId, type);
        return value == null ? 0 : value;
    }

    public void deleteAllForOwner(String ownerId) {
        jdbc.update("DELETE FROM creator_coach_record WHERE owner_id=?", ownerId);
    }

    public Map<String, Object> parseMap(String json) {
        try {
            if (json == null || json.isBlank()) return new LinkedHashMap<>();
            return mapper.readValue(json, MAP_TYPE);
        } catch (Exception ex) {
            throw new IllegalStateException("Stored creator data could not be read.", ex);
        }
    }

    private Map<String, Object> decode(ResultSet rs, String column) throws SQLException {
        return parseMap(rs.getString(column));
    }
}
