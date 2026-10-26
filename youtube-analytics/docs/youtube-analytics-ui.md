# YouTube Analytics UI and workspace API

## Running the UI

The standalone frontend source is kept in `front-end/` and is not packaged into Spring Boot. The backend listens on port 8080 by default and serves only API/OAuth responses. Run the backend:

```sh
mvn spring-boot:run
```

Run the frontend separately from the project root:

```sh
python3 -m http.server 3000 --bind 127.0.0.1 --directory front-end
```

Open `http://localhost:3000/`. The UI calls the backend base URL from `front-end/config.js` (`apiBaseUrl`, default `http://localhost:8080`) with credentials for its OAuth session. The direct backend OAuth URL is `http://localhost:8080/oauth2/authorization/google`; after sign-in it ends at the backend OAuth success JSON response. A login initiated from the UI returns to the UI after authentication.

The two format-specific pages can be opened in separate tabs/windows:

- `/shorts.html` — a Shorts-only sidebar and navigation (Overview, Shorts library, Shorts analytics, Performance).
- `/long-form.html` — a long-form-only sidebar and navigation (Overview, Video library, Video analytics, Performance).

Each page filters the library and comparisons to the selected format. Duration is used as an approximation (180 seconds or less for Shorts), not a definitive Shorts-tab flag. Both pages load `front-end/config.js`, so changing `apiBaseUrl` changes the backend destination for all frontend pages.

The separate beginner-friendly creator coaching experience is available at `http://localhost:3000/creator-coach.html` while the frontend server is running. It uses its own `creator-coach.html`, `creator-coach.css`, and `creator-coach.js` files, with dedicated navigation for My videos, My progress, Next video planner, My style, and Help. Links to it are available from the full workspace and both format-specific dashboards. The existing analytics pages are kept intact. This screen uses the `/api/youtube-analytics/v1` creator-coach API and displays only plain-language report fields; it does not expose OAuth tokens or raw AI responses.

The dashboard uses plain HTML, CSS and JavaScript. It does not need a Node.js server or a third-party charting runtime. The API port uses `SERVER_PORT`; when changed, update `apiBaseUrl` and the Google authorized redirect URI. Configure UI return URL with `UI_BASE_URL` and allowed frontend origins with the comma-separated `CORS_ALLOWED_ORIGINS` setting.

## OpenAI credential and request diagnostics

On startup, the backend validates the configured OpenAI credential by calling the authenticated `GET /v1/models` endpoint; this is a lightweight authentication check and does not run model inference. Startup checking can be disabled with `OPENAI_VALIDATE_ON_STARTUP=false`. The same diagnostic is available at `GET /api/youtube-analytics/v1/analytics/ai/key-status` after signing in to the backend.

The response reports whether the key file is configured, whether OpenAI accepted the key, and whether the configured model appears in the model list. HTTP errors are categorized in logs, including HTTP 401 (invalid/revoked key), 403 (permissions/project access), 404 (endpoint/model), and 429 (rate limit/quota).

Every AI Responses request logs operation, model, endpoint, duration, response ID/status, and failure details. Full request and response payloads are enabled locally by default via `OPENAI_LOG_PAYLOADS=true`; payload logs can contain full transcripts and analytics context. Set `OPENAI_LOG_PAYLOADS=false` for shared or production logs. Credential-like values are redacted from logged bodies, and the Authorization header/API key is never logged.

## Workspace endpoint contract

Base path: `/api/youtube-analytics/v1/analytics/workspace`

| Method / path | Purpose |
| --- | --- |
| `GET /channel/mine?lastNDays=28` | Authorized channel overview, public metadata, recent upload sample, and owner-only analytics reports |
| `GET /channel/{channelId}?lastNDays=28` | Compare a requested channel with the signed-in channel and apply the correct access level |
| `GET /video/{videoId}?lastNDays=28` | Owned-video analytics, retention analysis, discovery diagnosis, decision, recommendations, daily metrics, reach and traffic source reports |
| `GET /channel/{channelId}/compare?contentType=LONG_FORM&count=10&lastNDays=28` | Relative comparison of recent long-form or Shorts-approximation uploads |
| `GET /keywords?seed=coorg%20travel&lastNDays=28` | Public autocomplete candidates and measured channel search terms where owner access is available |
| `GET /benchmarks?query=coorg%20travel%20vlog` | Sample of public videos for topic or destination context |
| `GET /reports?channelId=mine` | Rolling 7-, 30- and 365-reporting-day channel reports, when owner analytics are authorized |
| `POST /transcript-compare` | Compare an owned video with a public competitor video, fetch available transcripts, show public/owned performance context, and produce an AI transcript-gap analysis |
| `POST /creator-video-analysis` | Analyse one owned video against its own private Analytics, available audience-retention curve, and up to three earlier uploads of the same approximate format; returns a strict structured story-coach report |

The frontend calls the existing Analytics and YouTube Data API services through this workspace layer. It reuses the existing deterministic retention, discovery and recommendation services rather than duplicating that business logic in JavaScript.

### My Story Coach (self-video analysis)

The **Video vs competitor** page now opens in **My video + history** mode by default. This is the recommended starting point for a new creator: select one owned upload, then optionally supply a thumbnail description, notes about on-camera versus voice-over scenes, and a corrected transcript. The coach returns a beginner-friendly takeaway, exactly one `fix_first` priority, a timestamp-supported segment map where possible, promise-versus-delivery observations, supported strengths/findings, analytics-linked hypotheses, up to three earlier same-format uploads when available, up to two additional experiments, a next-video checklist, and explicit transcript/visual limitations. Use **Compare with competitor** to retain the previous transcript-comparison workflow.

The creator mode is served by `POST /creator-video-analysis`. It checks video ownership before using private Analytics, uses the existing owned-video analytics and retention services, and compares history only with earlier uploads of the same approximate duration-based format. The recent upload catalog is capped at 50 items, and history fields such as hook style or visual notes are not inferred if the creator did not provide them. It labels early video analytics and small samples as directional, never treats average view duration as a dropout moment, never fabricates timestamps or retention points, and does not claim transcript evidence can judge camera work, music, B-roll, color or edit rhythm. Recommendations are hypotheses to test rather than causal conclusions. Reports can be downloaded as standalone HTML from the result view and printed to PDF.

## Access control and metric definitions

- **Authorized channel:** channel identity is resolved with the signed-in account. Full private reports are returned only when the requested channel ID matches that identity.
- **Other channels:** only public channel/video metadata and public lifetime counters (where visible) are included. Public counters are not date-filtered. Watch time, audience-retention curves, revenue, traffic sources, impressions and impressions CTR are never inferred for competitors.
- **Shorts classification:** the available API does not provide a definitive Shorts-tab flag or orientation. The workspace labels videos of 180 seconds or less as a *Shorts approximation*. Short horizontal videos can therefore be classified as likely Shorts; this is an explicit limitation.
- **Upload consistency:** weekly counts are derived from publication dates in the recent upload sample. The current public sample fetches only the first 50 latest uploads, so a very active channel can be undercounted. The response includes a coverage note.
- **Reports:** weekly, monthly and yearly screens currently use rolling 7-, 30- and 365-reporting-day windows, not calendar-aligned periods. Analytics reporting uses the existing `America/Los_Angeles` date range resolver and may lag 48–72 hours.
- **Video CTR:** the reach CTR field is a measured *video-level impressions click-through rate* from the YouTube Reporting API when that asynchronous report is available. A missing report is not a zero CTR.
- **Keyword opportunity score:** autocomplete suggestions are candidates, not measured query demand. The `opportunityScoreEstimate` is a heuristic query-fit score based on query specificity and related measured search-term evidence. Its type is explicitly `HEURISTIC_QUERY_FIT_ESTIMATE_NOT_CTR`. The official Analytics API does not return impressions CTR for each search keyword, so keyword CTR is intentionally null.
- **Competitor comparison:** public view/like/comment counters give context but do not establish why a video succeeded. Improvement ideas are hypotheses to test, not causal conclusions. Compare owned videos using the same format and reporting window.
- **Transcript comparison:** `POST /transcript-compare` accepts `myVideoId`, `competitorVideoId`, optional `myTranscript` / `competitorTranscript` text (up to 60,000 characters each), `analyze`, and `lastNDays`. It returns normalized metadata, available owner/public metrics, transcript source/status, and a structured analysis of hook, narrative, competitor strengths, missing spoken points, and experiments. The official YouTube captions download requires permission to edit the video, so public competitor captions are attempted using the optional local `yt-dlp` executable. Install `yt-dlp` separately (for example, `brew install yt-dlp` on macOS) or configure `YTDLP_COMMAND`. If captions cannot be fetched, paste them into the UI; inaccessible captions do not imply a transcript is empty. Transcript omissions are not evidence that a point is absent visually, and the analysis does not claim transcript differences caused more views.
- **Transcript comparison evidence quality:** the AI receives timestamped transcript cues as `[mm:ss]` markers when the source provides cue times; plain pasted transcripts remain untimed unless the pasted text already includes timestamps. The structured response includes evidence-backed findings, confidence, ranked recommendations (maximum five), transcript completeness notes, metric-linked hypotheses only when supported by supplied aggregates, and explicit limitations for visual/audio elements. The prompt does not claim a timestamped retention curve is available, because the current comparison context contains aggregate reporting-window/reach metrics rather than time-aligned retention points. Auto-caption language mixing and likely transcription errors are treated cautiously. Output fields are validated against a strict JSON schema and surfaced in the comparison UI.
- **Audience Studio-only surfaces:** “when your viewers are on YouTube”, other channels/videos audience research, and other unavailable Studio-only metrics are described as unavailable instead of synthesized.

## Business logic locations

- `service/businesslogic/AnalyticsWorkspaceBusinessLogic.java` contains deterministic, unit-testable transformations for upload consistency, within-set comparisons and keyword-opportunity labeling.
- `service/businesslogic/AnalyticsWorkspaceService.java` orchestrates existing APIs/services and applies authorized-channel versus public-only access rules.
- `controller/AnalyticsWorkspaceController.java` exposes the workspace-specific endpoints above.
- `controller/CreatorCoachController.java` exposes the creator-coach endpoints listed below.
- `service/creatorcoach/CreatorCoachService.java` orchestrates persisted profiles, manual inputs, transcript editing, deterministic analytics preprocessing, async jobs, report post-processing, progress, plans, reminders, and glossary entries.
- `service/creatorcoach/CreatorCoachStore.java` stores creator-owned JSON records in the Cassandra keyspace `youtube_analytics`. Records are always scoped to the authenticated Google subject; the client never supplies an owner ID.
- `service/creatorcoach/CreatorCoachQualityRules.java` contains deterministic curve-moment detection, evidence-quote checks, and confidence caps.
- `resources/prompts/coach-v1.txt` is the static, versioned system prompt. Store prompt version/model/token counts/raw AI output in an internal report record, separate from UI-visible report JSON.

## Creator coaching backend API

The routes below share the prefix `/api/youtube-analytics/v1`. Private endpoints require the connected Google account. The owner scope is derived server-side from the OAuth identity.

| Method / path | Purpose |
| --- | --- |
| `POST /auth/youtube/connect`, `GET /auth/status` | Begin or inspect YouTube connection; the status response says when reconnection is needed |
| `GET/PUT /profile` | Read or save My Style: creator stage, style, languages, goals, must-keep elements, and output language |
| `GET /videos` | Return up to 50 recent owned uploads with `TOO_NEW`, `NEEDS_YOUR_NUMBERS`, or `REPORT_READY` status |
| `GET /videos/{id}/inputs` | Return known/missing inputs, owner Analytics, profile, saved transcript, and help text for Studio-only fields |
| `PUT /videos/{id}/manual-metrics` | Save manual thumbnail impressions/click percentage, first-30-seconds/intro values, thumbnail description and optional notes |
| `GET/PUT /videos/{id}/transcript` | Retrieve available captions or save creator-edited transcript text/segments, capped at 60,000 characters |
| `POST /videos/{id}/analyses` | Queue one analysis and return `jobId`; returns status without creating a report when the video is under three days old or required inputs are missing |
| `GET /analyses/{jobId}` | Poll `queued`, `reading`, `checking`, `done`, or `failed` status |
| `GET /videos/{id}/report`, `GET /reports/{id}` | Return the latest or selected UI-ready report; raw OpenAI responses and the minute-by-minute curve are kept in internal storage |
| `PUT /reports/{id}/fix-first`, `/checklist`, `/answers` | Persist completion state, next-video reminder flag, checklist ticks and creator answers |
| `GET /progress` | Trend/habit history, outstanding reminders and directional before/after hints; reports “needs 3 videos” until enough history exists |
| `GET/POST/PUT /plans`, `GET /plans/{id}`, `POST /plans/{id}/duplicate` | Save, retrieve, edit and duplicate the five-part video outline |
| `POST /plans/{id}/title-check` | Compare title words with the outline and warn when key promises do not appear in the outline |
| `GET /reports/{id}/export?format=pdf|text`, `GET /plans/{id}/export?format=text` | Download a one-page plain-language PDF report, copyable text checklist/report or five-part plan |
| `GET /glossary` | Return plain definitions with examples from saved video data when available |
| `DELETE /account/data` | Delete stored creator records and the persisted authorized-client entry for the account |

### Storage and configuration

All creator-coach records and OAuth authorized clients are stored in the local Cassandra keyspace `youtube_analytics`. Local defaults use Cassandra at `127.0.0.1:9042` with datacenter `datacenter1`. Override with `CASSANDRA_CONTACT_POINT`, `CASSANDRA_PORT`, and `CASSANDRA_LOCAL_DATACENTER`.

The `oauth2_authorized_client` table is the only durable OAuth-token store. Token rows are cleared at application startup and graceful shutdown, so Google authorization is required for each application run. Token values are never returned by creator endpoints.

`CREATOR_COACH_DAILY_ANALYSIS_LIMIT` defaults to 10 starts per creator per India-local day. Set `CREATOR_COACH_INPUT_COST_PER_MILLION_USD` and `CREATOR_COACH_OUTPUT_COST_PER_MILLION_USD` to enable approximate cost logging from the actual token counts returned by the model. If the rates are left at zero, the report logs model/token usage but labels cost as not configured rather than inventing a charge.

### Deterministic quality rules

- A report is not created if the publication date is unavailable or the video is under three days old. The current catalog is limited to 50 recent uploads.
- The small-sample flag is computed from owner Analytics views in the selected reporting window, not the public lifetime view counter. Missing views remain unknown, and small samples use neutral wording.
- Audience drops/spikes and traffic-source shares are calculated in Java before the OpenAI call. The raw curve is stored internally and not included in public report JSON.
- Segment boundaries from the model are clipped, sorted, overlap-adjusted and gap-filled in Java. Runtime shares and percent on the title promise are recomputed by the backend.
- A quote survives only if whitespace-normalised transcript text contains it, it is at most 20 words, and its timestamp is within five seconds of its source cue. Invalid evidence is nulled with an explanatory note.
- If there is no usable audience curve, “very sure” is capped at “fairly”. The backend makes one rewrite request when jargon or a protected-style conflict is detected; a final fallback sanitizes remaining jargon while preserving exact transcript quotes.
- Matching in-flight requests are deduplicated by video, transcript, manual values, precomputed Analytics hash, profile and prompt version. Reports store the final input hash; internal records retain the raw response and usage fields.
- Model calls use bounded retries with exponential backoff for HTTP 429/5xx responses. The output is constrained by a strict JSON schema and one schema-validation retry. The controller returns a job status rather than a long-running OpenAI response.

The internal Cassandra record model deliberately keeps raw AI output and raw Analytics snapshots in an internal record type that is not exposed by the report endpoint. The UI receives only computed/report-safe fields. The creator can delete persisted creator data using `DELETE /account/data`.

Known implementation limits: the current upload catalog is capped at 50 recent owned uploads; the title check is a deterministic word-overlap warning, not a performance prediction; the glossary uses available saved-report examples and returns no example when one cannot be grounded; the plan export is text, while report export supports PDF/text. The PDF is constrained to one page and may use a portable-character fallback when a Unicode font is unavailable on the server.
