package com.youtube.analytics.service.vlogscript;

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Cassandra persistence for a creator's trip-based vlog script library.
 * A trip ID represents one specific visit; destination names are not unique.
 */
@Repository
@DependsOn("cassandraSchemaInitializer")
public class VlogScriptStore {
    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final CqlSession session;
    private final ObjectMapper mapper;
    private final PreparedStatement listTrips;
    private final PreparedStatement getTrip;
    private final PreparedStatement insertTrip;
    private final PreparedStatement deleteTrip;
    private final PreparedStatement listEpisodes;
    private final PreparedStatement insertEpisode;
    private final PreparedStatement deleteEpisode;
    private final PreparedStatement deleteTripEpisodes;

    public VlogScriptStore(CqlSession session, ObjectMapper mapper) {
        this.session = session;
        this.mapper = mapper;
        this.listTrips = session.prepare("""
                SELECT trip_id, payload, created_at, updated_at
                FROM "youtube_analytics".vlog_trip_by_owner
                WHERE owner_id=?
                """);
        this.getTrip = session.prepare("""
                SELECT payload, created_at, updated_at
                FROM "youtube_analytics".vlog_trip_by_owner
                WHERE owner_id=? AND trip_id=?
                """);
        this.insertTrip = session.prepare("""
                INSERT INTO "youtube_analytics".vlog_trip_by_owner
                    (owner_id, trip_id, payload, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?)
                """);
        this.deleteTrip = session.prepare("""
                DELETE FROM "youtube_analytics".vlog_trip_by_owner
                WHERE owner_id=? AND trip_id=?
                """);
        this.listEpisodes = session.prepare("""
                SELECT episode_order, episode_id, title, payload
                FROM "youtube_analytics".vlog_episode_by_trip
                WHERE owner_id=? AND trip_id=?
                """);
        this.insertEpisode = session.prepare("""
                INSERT INTO "youtube_analytics".vlog_episode_by_trip
                    (owner_id, trip_id, episode_order, episode_id, title, payload, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """);
        this.deleteEpisode = session.prepare("""
                DELETE FROM "youtube_analytics".vlog_episode_by_trip
                WHERE owner_id=? AND trip_id=? AND episode_order=? AND episode_id=?
                """);
        this.deleteTripEpisodes = session.prepare("""
                DELETE FROM "youtube_analytics".vlog_episode_by_trip
                WHERE owner_id=? AND trip_id=?
                """);
    }

    public List<Map<String, Object>> listTrips(String ownerId) {
        requireOwner(ownerId);
        List<Map<String, Object>> trips = new ArrayList<>();
        for (Row row : session.execute(listTrips.bind(ownerId))) {
            String tripId = row.getString("trip_id");
            Map<String, Object> trip = parseMap(row.getString("payload"));
            trip.put("id", tripId);
            trip.put("episodes", listEpisodesForTrip(ownerId, tripId));
            trip.put("createdAt", timestamp(row.getInstant("created_at")));
            trip.put("updatedAt", timestamp(row.getInstant("updated_at")));
            trips.add(trip);
        }
        trips.sort(Comparator.comparing(
                trip -> Objects.toString(trip.get("updatedAt"), ""),
                Comparator.reverseOrder()));
        return trips;
    }

    public Optional<Map<String, Object>> getTrip(String ownerId, String tripId) {
        requireOwner(ownerId);
        requireTripId(tripId);
        Row row = session.execute(getTrip.bind(ownerId, tripId)).one();
        if (row == null) return Optional.empty();
        Map<String, Object> trip = parseMap(row.getString("payload"));
        trip.put("id", tripId);
        trip.put("episodes", listEpisodesForTrip(ownerId, tripId));
        trip.put("createdAt", timestamp(row.getInstant("created_at")));
        trip.put("updatedAt", timestamp(row.getInstant("updated_at")));
        return Optional.of(trip);
    }

    /**
     * Upserts one trip and synchronizes its episode rows. Missing episodes in
     * the supplied trip are deleted, so episode deletions in the Studio persist.
     */
    public Map<String, Object> saveTrip(String ownerId, Map<String, Object> incomingTrip) {
        requireOwner(ownerId);
        if (incomingTrip == null) throw new IllegalArgumentException("Trip data is required.");

        String tripId = Objects.toString(incomingTrip.get("id"), "").trim();
        String destinationName = Objects.toString(incomingTrip.get("name"), "").trim();
        requireTripId(tripId);
        if (destinationName.isBlank()) {
            throw new IllegalArgumentException("Trip destination name is required.");
        }

        List<Map<String, Object>> episodes = toMapList(incomingTrip.get("episodes"), "episodes");
        Set<String> episodeIds = new LinkedHashSet<>();
        List<Map<String, Object>> normalizedEpisodes = new ArrayList<>();
        for (int index = 0; index < episodes.size(); index++) {
            Map<String, Object> episode = new LinkedHashMap<>(episodes.get(index));
            String episodeId = Objects.toString(episode.get("id"), "").trim();
            if (episodeId.isBlank()) episodeId = "episode-" + UUID.randomUUID();
            if (!episodeIds.add(episodeId)) {
                throw new IllegalArgumentException("Episode IDs must be unique within a trip.");
            }
            episode.put("id", episodeId);
            String title = Objects.toString(episode.get("title"), "").trim();
            episode.put("title", title.isBlank() ? "Episode " + (index + 1) : title);
            normalizedEpisodes.add(episode);
        }

        Instant now = Instant.now();
        Row previous = session.execute(getTrip.bind(ownerId, tripId)).one();
        Instant createdAt = previous == null ? now : instantOrNow(previous.getInstant("created_at"));

        Map<String, Object> tripPayload = new LinkedHashMap<>(incomingTrip);
        tripPayload.remove("episodes");
        tripPayload.remove("createdAt");
        tripPayload.remove("updatedAt");
        tripPayload.remove("_createdAt");
        tripPayload.remove("_updatedAt");
        tripPayload.put("id", tripId);
        tripPayload.put("name", destinationName);
        String tripJson = writeJson(tripPayload);
        session.execute(insertTrip.bind(ownerId, tripId, tripJson, createdAt, now));

        synchronizeEpisodes(ownerId, tripId, normalizedEpisodes, now);
        return getTrip(ownerId, tripId).orElseThrow(
                () -> new IllegalStateException("Saved trip could not be loaded from Cassandra."));
    }

    public List<Map<String, Object>> saveTrips(String ownerId, List<Map<String, Object>> trips) {
        requireOwner(ownerId);
        if (trips == null) throw new IllegalArgumentException("A trips array is required.");
        for (Map<String, Object> trip : trips) saveTrip(ownerId, trip);
        return listTrips(ownerId);
    }

    public boolean deleteTrip(String ownerId, String tripId) {
        requireOwner(ownerId);
        requireTripId(tripId);
        if (session.execute(getTrip.bind(ownerId, tripId)).one() == null) return false;
        session.execute(deleteTripEpisodes.bind(ownerId, tripId));
        session.execute(deleteTrip.bind(ownerId, tripId));
        return true;
    }

    public void deleteAllForOwner(String ownerId) {
        requireOwner(ownerId);
        List<Row> rows = session.execute(listTrips.bind(ownerId)).all();
        for (Row row : rows) {
            String tripId = row.getString("trip_id");
            session.execute(deleteTripEpisodes.bind(ownerId, tripId));
            session.execute(deleteTrip.bind(ownerId, tripId));
        }
    }

    private List<Map<String, Object>> listEpisodesForTrip(String ownerId, String tripId) {
        List<Map<String, Object>> episodes = new ArrayList<>();
        for (Row row : session.execute(listEpisodes.bind(ownerId, tripId))) {
            Map<String, Object> episode = parseMap(row.getString("payload"));
            episode.put("id", row.getString("episode_id"));
            episode.put("title", Objects.toString(row.getString("title"), "Episode"));
            episodes.add(episode);
        }
        return episodes;
    }

    private void synchronizeEpisodes(String ownerId, String tripId,
                                     List<Map<String, Object>> desiredEpisodes, Instant now) {
        Map<String, Integer> existingOrders = new LinkedHashMap<>();
        for (Row row : session.execute(listEpisodes.bind(ownerId, tripId))) {
            existingOrders.put(row.getString("episode_id"), row.getInt("episode_order"));
        }

        Set<String> desiredIds = new LinkedHashSet<>();
        for (int index = 0; index < desiredEpisodes.size(); index++) {
            Map<String, Object> episode = desiredEpisodes.get(index);
            String episodeId = Objects.toString(episode.get("id"));
            desiredIds.add(episodeId);
            Integer previousOrder = existingOrders.get(episodeId);
            if (previousOrder != null && previousOrder != index) {
                session.execute(deleteEpisode.bind(ownerId, tripId, previousOrder, episodeId));
            }
        }
        for (Map.Entry<String, Integer> existing : existingOrders.entrySet()) {
            if (!desiredIds.contains(existing.getKey())) {
                session.execute(deleteEpisode.bind(ownerId, tripId, existing.getValue(), existing.getKey()));
            }
        }

        for (int index = 0; index < desiredEpisodes.size(); index++) {
            Map<String, Object> episode = desiredEpisodes.get(index);
            String episodeId = Objects.toString(episode.get("id"));
            String title = Objects.toString(episode.get("title"), "Episode " + (index + 1));
            session.execute(insertEpisode.bind(ownerId, tripId, index, episodeId, title,
                    writeJson(episode), now, now));
        }
    }

    private List<Map<String, Object>> toMapList(Object value, String fieldName) {
        if (value == null) return new ArrayList<>();
        if (!(value instanceof List<?> values)) {
            throw new IllegalArgumentException("'" + fieldName + "' must be an array.");
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : values) {
            if (!(item instanceof Map<?, ?> source)) {
                throw new IllegalArgumentException("Every item in '" + fieldName + "' must be an object.");
            }
            Map<String, Object> row = new LinkedHashMap<>();
            source.forEach((key, itemValue) -> row.put(String.valueOf(key), itemValue));
            result.add(row);
        }
        return result;
    }

    private Map<String, Object> parseMap(String json) {
        try {
            if (json == null || json.isBlank()) return new LinkedHashMap<>();
            return mapper.readValue(json, MAP_TYPE);
        } catch (Exception ex) {
            throw new IllegalStateException("Stored vlog script data could not be read.", ex);
        }
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("Vlog script data could not be serialized.", ex);
        }
    }

    private Instant instantOrNow(Instant value) {
        return value == null ? Instant.now() : value;
    }

    private String timestamp(Instant value) {
        return value == null ? null : value.toString();
    }

    private void requireOwner(String ownerId) {
        if (ownerId == null || ownerId.isBlank()) {
            throw new IllegalArgumentException("Authenticated creator identity is required.");
        }
    }

    private void requireTripId(String tripId) {
        if (tripId == null || tripId.isBlank()) {
            throw new IllegalArgumentException("Trip ID is required.");
        }
    }
}
