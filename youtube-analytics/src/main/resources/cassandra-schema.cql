-- Local development defaults. Cassandra keyspace names cannot contain hyphens, so use underscores.
CREATE KEYSPACE IF NOT EXISTS youtube_analytics
WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1}
AND durable_writes = true;

-- Primary creator records. JSON payloads preserve the existing API's flexible record shape.
CREATE TABLE IF NOT EXISTS youtube_analytics.creator_coach_record_by_id (
    owner_id text,
    record_type text,
    record_id text,
    payload text,
    created_at timestamp,
    updated_at timestamp,
    PRIMARY KEY ((owner_id, record_type), record_id)
);

-- Query-optimized owner index for safe full-account deletion.
CREATE TABLE IF NOT EXISTS youtube_analytics.creator_coach_record_ids_by_owner (
    owner_id text,
    record_type text,
    record_id text,
    PRIMARY KEY ((owner_id), record_type, record_id)
);

-- Trip metadata is partitioned by the authenticated creator. Each trip_id represents
-- one specific visit, so the same destination can be visited repeatedly.
CREATE TABLE IF NOT EXISTS youtube_analytics.vlog_trip_by_owner (
    owner_id text,
    trip_id text,
    payload text,
    created_at timestamp,
    updated_at timestamp,
    PRIMARY KEY ((owner_id), trip_id)
);

-- Episode order and script payloads are read by trip. Sections remain JSON so the
-- Studio can preserve its full editable script structure without schema migrations.
CREATE TABLE IF NOT EXISTS youtube_analytics.vlog_episode_by_trip (
    owner_id text,
    trip_id text,
    episode_order int,
    episode_id text,
    title text,
    payload text,
    created_at timestamp,
    updated_at timestamp,
    PRIMARY KEY ((owner_id, trip_id), episode_order, episode_id)
) WITH CLUSTERING ORDER BY (episode_order ASC, episode_id ASC);

-- The sole durable store for OAuth authorized clients.
CREATE TABLE IF NOT EXISTS youtube_analytics.oauth2_authorized_client (
    client_registration_id text,
    principal_name text,
    access_token_value text,
    access_token_issued_at timestamp,
    access_token_expires_at timestamp,
    access_token_scopes text,
    refresh_token_value text,
    refresh_token_issued_at timestamp,
    PRIMARY KEY ((client_registration_id, principal_name))
);

-- Upgrade the earlier OAuth table schema without copying or retaining token values.
ALTER TABLE youtube_analytics.oauth2_authorized_client ADD IF NOT EXISTS access_token_value text;
ALTER TABLE youtube_analytics.oauth2_authorized_client ADD IF NOT EXISTS refresh_token_value text;
