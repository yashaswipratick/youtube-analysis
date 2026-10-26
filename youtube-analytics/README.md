# YouTube Analytics API

Spring Boot REST API for Google OAuth and YouTube Studio Analytics, including channel/video reports, retention, traffic sources, geography, discovery analysis, recommendations, and AI-assisted analysis of supplied analytics evidence.

## Run

Requires Java 17 and Maven. Set `youtube.oauth.credentials-file` (or `YOUTUBE_OAUTH_CREDENTIALS_FILE`) to a local JSON file containing the Google OAuth `client-id` and `client-secret`, then run:

```sh
mvn spring-boot:run
```

Application APIs are rooted at `/api/youtube-analytics/v1`. Analytics endpoints are under `/analytics`, local YouTube Data API compatibility endpoints under `/youtube`, and OAuth helper endpoints under `/oauth`. OpenAPI documentation is available at `/swagger-ui/index.html` while the application is running.

### Cassandra persistence

The application uses the local Cassandra cluster for creator-owned records and encrypted OAuth tokens. On startup, it idempotently creates these tables in the `youtube_analytics` keyspace:

- `creator_coach_record_by_id` — profiles, transcripts, reports, plans, checklist/action state and other creator records.
- `creator_coach_record_ids_by_owner` — owner-scoped index used to remove a creator's records.
- `oauth2_authorized_client` — access/refresh tokens encrypted with AES-GCM.
- `cassandra_migration_marker` — one-time migration marker and migrated-row counts.

**Cassandra requires keyspace names to use alphanumeric characters or underscores.** The application/repository name remains `youtube-analytics`, but the actual Cassandra keyspace is `youtube_analytics`; Cassandra rejects a hyphenated keyspace name even when quoted.

Local defaults use `127.0.0.1:9042` and datacenter `datacenter1`. Override them with `CASSANDRA_CONTACT_POINT`, `CASSANDRA_PORT`, and `CASSANDRA_LOCAL_DATACENTER`. The DDL is also checked into `src/main/resources/cassandra-schema.cql`.

On first startup, `CASSANDRA_MIGRATE_LEGACY_H2=true` (default) performs a **read-only** import from the previous local H2 file at `CREATOR_DB_PATH` (default `./data/youtube-analytics/creator-coach-db`). Creator records and already-encrypted OAuth tokens are copied to Cassandra; the original H2 file is not deleted. The `cassandra_migration_marker` table prevents automatic re-import. Set `CASSANDRA_MIGRATE_LEGACY_H2=false` to skip this migration. For stable token decryption across restarts/deployments, set `YOUTUBE_TOKEN_ENCRYPTION_KEY` and keep it private and unchanged.


### YouTube Studio analytics reports

Analytics report endpoints are under `/api/youtube-analytics/v1/analytics`:

| Endpoint | Purpose |
| --- | --- |
| `GET /reports/overview` | Views, watch time, subscriber gains/losses/net change, average view duration/percentage, likes, comments, shares, and revenue when authorized/available |
| `GET /reports/videos/top` | Top videos by views with watch time, retention averages, subscribers gained and shares |
| `GET /reports/videos/compare?videoIds=ID1&videoIds=ID2` | Compare up to 50 selected videos side by side |
| `GET /reports/audience` | Unique viewers, watch time/views for subscribed vs. unsubscribed viewers, age and gender breakdown |
| `GET /reports/formats` | Shorts, video-on-demand and livestream breakdown where available |
| `GET /reports/traffic-sources` | Browse, Suggested, YouTube Search, External, Shorts feed and other traffic source categories |
| `GET /reports/search-terms` | Search terms that generated views for channel videos |
| `GET /reports/revenue` | Estimated revenue, RPM-related CPM metrics and revenue by video where available |
| `GET /reports/videos/benchmarks?limit=15` | First 48-hour (date-granular approximation) and first 28-day metrics for the latest 1–15 uploads, including views, average view duration/percentage, subscribers gained, shares, traffic source, impressions and CTR when Reporting API CSVs are ready |
| `GET /reports/subtitles` | Subtitle language report from the asynchronous YouTube Reporting API |
| `GET /video/{videoId}/reach` | Impressions and impressions click-through rate for one video |
| `GET /reports/capabilities` | Machine-readable list of supported reports and Studio-only metrics not exposed by Google's APIs |

All date-scoped analytics endpoints use `lastNDays` only (1–3650), for example `GET /api/youtube-analytics/v1/analytics/video/sMMJS_Hp8KI?lastNDays=7`. If omitted, the API defaults to the last 28 YouTube reporting days. The public API no longer accepts `startDate` or `endDate` request parameters. For video-specific reports, the API internally uses local video-details metadata (`data.publish_date`) and clamps the analytics start date to the video's publication reporting day. Reporting days use America/Los_Angeles. The bulk `POST /videos` and `POST /recommendations` request bodies also use `lastNDays`.

**Video metric freshness:** the single-video endpoint also fetches current public counters from YouTube Data API v3. When `lastNDays` covers the video's entire lifetime, `metrics.views`, `metrics.likes`, and `metrics.comments` use those current counts; the original processed Analytics API values are retained in `analyticsMetrics`. Current counters are also returned in `currentStatistics`, and `dataFreshness` explains the source. For a shorter date-filtered window, lifetime counters are not substituted. `engagedViews`, watch time, retention and subscriber metrics still come from YouTube Analytics API v2 and may lag by 48–72 hours; the public Data API does not expose a live engaged-views counter.

The OAuth scopes include `yt-analytics.readonly` and `yt-analytics-monetary.readonly`. After adding the monetary scope, re-authorize the Google account via `/oauth2/authorization/google` so consent is refreshed. Revenue still requires an eligible monetized channel. The YouTube Analytics API's data is not real-time and commonly lags by 48–72 hours; the Reporting API generates daily CSV files asynchronously. The 48-hour benchmark is therefore not an exact rolling 48-hour measurement. See `docs/youtube-studio-analytics-coverage.md` for the coverage matrix and limitations.

OpenAI analysis is optional and uses `OPENAI_API_KEY_FILE`, `OPENAI_BASE_URL`, and `OPENAI_MODEL`.

## Separate analytics UI and backend

The backend is API-only. It does not package or serve `front-end/`. Run it from the project root:

```sh
mvn spring-boot:run
```

The backend listens on `http://localhost:8080` by default. Its root returns backend-service JSON, the API routes remain under `/api/youtube-analytics/v1`, and the direct Google OAuth URL is `http://localhost:8080/oauth2/authorization/google`. A direct OAuth login finishes on the backend's `/api/youtube-analytics/v1/oauth/success` JSON response.

Run the independent UI in a second terminal:

```sh
python3 -m http.server 3000 --bind 127.0.0.1 --directory front-end
```

Open `http://localhost:3000/`. The UI's backend base URL is configured in `front-end/config.js` (`apiBaseUrl`, default `http://localhost:8080`). The UI sign-in button starts OAuth through the backend's `/api/youtube-analytics/v1/oauth/start` route; that flow returns to the UI after sign-in, while directly opening the OAuth URL above displays the backend success response. The backend root (`/`) returns JSON service metadata, never the UI.

To change the backend port, start it with `SERVER_PORT=8090 mvn spring-boot:run` and update `apiBaseUrl` in `front-end/config.js` to `http://localhost:8090`. Also set `CORS_ALLOWED_ORIGINS` if the frontend origin changes, and `UI_BASE_URL` if the UI URL changes. In Google Cloud Console, update the OAuth client's authorized redirect URI to `http://localhost:8090/login/oauth2/code/google` when changing the backend port; Google redirects to the Spring Boot callback port, never the UI port. CORS origins are configured with `CORS_ALLOWED_ORIGINS` (comma-separated).

### Format-specific dashboards

The frontend has two independent dashboard pages, each with its own sidebar and format-restricted library, video deep-dive, and comparison screens:

- `http://localhost:3000/shorts.html` — Shorts dashboard.
- `http://localhost:3000/long-form.html` — long-form video dashboard.

Both pages use the same configurable backend origin from `front-end/config.js` and existing workspace API. Format filtering is based on duration (180 seconds or less is a Shorts approximation), not an authoritative Shorts-tab classification. Each dashboard analyzes videos selected from its matching recent-upload sample and keeps its comparison rows within that format.

The workspace layer is under `/api/youtube-analytics/v1/analytics/workspace` and provides channel/video analysis, Shorts/long-form comparison, upload consistency, keyword opportunity candidates, public comparable videos, and rolling period reports. See [docs/youtube-analytics-ui.md](docs/youtube-analytics-ui.md) for endpoint contracts, metric limitations, and definitions.

Keyword opportunity scores are heuristic estimates, not measured CTR or search volume. CTR is reported only as a measured video-level reach metric when YouTube Reporting API data is available. Competitor retention, watch time, audience metrics, revenue, and CTR are not exposed by public endpoints and are not invented.

## Local YouTube Data API compatibility endpoints

A self-hosted compatibility API is available under `/api/youtube-analytics/v1/youtube/`. This is the single canonical route family; legacy `/api/tubealfred` and `/api/youtubealfred` aliases are not registered. **All data requests are made directly to Google's official YouTube Data API v3 using the signed-in user's Google OAuth access token.** This project does not call TubeAlfred, does not require a TubeAlfred API key, and does not use a paid third-party API.

The local API implements video details/enhanced details, OAuth-accessible captions, comments and replies, related-video search approximation, channel details/about/uploads/streams/Shorts/playlists, YouTube search and filters, public autocomplete suggestions, hashtag search, most-popular videos, playlists, URL resolution and batch lookups. See `docs/tubealfred-api-inventory.md` for the route-by-route mapping and limitations.

### OAuth and Google API setup

- Enable **YouTube Data API v3** for the Google Cloud project associated with the OAuth client.
- Google sign-in is initiated at `/oauth2/authorization/google` (Spring Security's OAuth protocol endpoint); the current user's OAuth token is used for YouTube requests. The app's OAuth status/success helper APIs are under `/api/youtube-analytics/v1/oauth`.
- The configuration includes `youtube.readonly` plus `youtube.force-ssl`. Google uses the latter for OAuth-protected caption listing/download; sign out and authorize again if the newly added scope has not been consented to.
- Public read operations consume Google's project quota. There is no TubeAlfred subscription or third-party API key. Google Cloud quota and access restrictions still apply.
- Google does not provide an official public endpoint for channel Community posts, arbitrary public transcripts, or a direct related-video/Shorts-tab feed. For transcript access, this implementation uses Google's captions API and can only fetch tracks the signed-in account is authorized to edit. Related videos and Shorts are best-effort approximations, clearly annotated in their responses. Community-post routes return an explicit unsupported response instead of scraping undocumented endpoints.

Swagger: `/swagger-ui/index.html`  
OpenAPI JSON: `/v3/api-docs`
