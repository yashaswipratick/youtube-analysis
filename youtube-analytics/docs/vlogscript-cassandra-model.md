# VlogScript Studio — Cassandra trip/episode model

## Goal

Persist the content shown in **Write your own script** as a creator-owned library:

- A trip represents one specific visit, not a globally unique destination.
- One destination can have multiple trip IDs, e.g. two separate Coorg visits.
- Each trip has zero or more ordered episodes.
- Each episode stores its section list as JSON so the existing editor can keep section IDs, script text, format, timing, and notes without a column/schema change for every editor field.
- The browser keeps a local copy for responsiveness and offline use. Once the user signs in with Google, the library synchronizes to Cassandra for that authenticated creator.

## Cassandra tables

The idempotent DDL is in `src/main/resources/cassandra-schema.cql`; `CassandraSchemaInitializer` creates the new tables with the existing `youtube_analytics` keyspace.

### `youtube_analytics.vlog_trip_by_owner`

Primary key: `((owner_id), trip_id)`

| Column | Purpose |
|---|---|
| `owner_id` | Google OAuth subject; set only by the backend, never trusted from a browser payload |
| `trip_id` | Unique ID for one visit; the same destination name can appear in different rows |
| `payload` | JSON trip metadata such as destination name, label, start date and trip type |
| `created_at`, `updated_at` | Cassandra write timestamps |

### `youtube_analytics.vlog_episode_by_trip`

Primary key: `((owner_id, trip_id), episode_order, episode_id)`, clustering in ascending order.

| Column | Purpose |
|---|---|
| `owner_id`, `trip_id` | Trip partition and ownership scope |
| `episode_order` | User-visible order of episodes in this trip |
| `episode_id` | Stable ID for an episode within its trip |
| `title` | Human-readable episode title |
| `payload` | JSON episode and its `sections` array (script, notes, timing, format, etc.) |
| `created_at`, `updated_at` | Cassandra write timestamps |

This is a query-first Cassandra design: the UI loads trips by creator and episodes by a known trip partition. It does not require scanning tables by destination name or adding secondary indexes for the main access pattern.

## API

All endpoints are under `/api/youtube-analytics/v1/vlog-trips` and require an authenticated Google OAuth session.

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/vlog-trips` | List trips and their ordered episodes for the signed-in creator |
| `GET` | `/vlog-trips/{tripId}` | Read one trip |
| `PUT` | `/vlog-trips` | Merge/upsert a list of trips; omitted trips are not deleted |
| `PUT` | `/vlog-trips/{tripId}` | Upsert one trip and synchronize its complete episode list |
| `DELETE` | `/vlog-trips/{tripId}` | Delete one trip and all of its episodes |
| `POST` | `/vlog-trips/storytelling-rewrite` | Preview an OpenAI storytelling rewrite for supplied voice-over sections; changes persist only after the Studio user applies the preview and the library syncs |

The API derives `owner_id` from Google OAuth and ignores any browser-supplied owner identity. Sending a trip's complete episode array replaces that trip's episode list, so removing an episode in the Studio is persisted. Deleting a trip is an explicit delete request; it is not inferred from a missing trip in a bulk sync payload.

## Studio synchronization

`front-end/vlogscript-studio/index.html` continues to keep its local library under `localStorage`. At startup it checks `/oauth/me`, pulls the creator's Cassandra library when authenticated, merges by trip ID and client update time, and sends new or newer local trip records to the API. Changes are locally saved immediately and sync after a short debounce. If the backend is down or the user is not signed in, local editing remains available.

A client update timestamp is included to avoid silently replacing newer browser edits with older remote data. Trip deletions are placed in a local deletion queue until Cassandra confirms them.

For local development, run the backend on port 8080, Cassandra on its configured contact point, and serve `front-end` separately on port 3000. The front-end API host is selected from the current `localhost` / `127.0.0.1` hostname in `front-end/config.js` so session cookies use the matching host.

## Example

| trip_id | destination | episodes |
|---|---|---|
| `trip-coorg-october` | Coorg | Episode 1, Episode 2 |
| `trip-coorg-next-visit` | Coorg | Episode 1 |
| `trip-kanva-weekend` | Kanva Reservoir / Ramanagara | Episode 1 |

The two Coorg visits remain separate because their `trip_id` values differ. Each can have its own scripts, sections, dates and visit notes.
