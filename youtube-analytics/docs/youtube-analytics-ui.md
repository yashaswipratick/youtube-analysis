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

The dashboard uses plain HTML, CSS and JavaScript. It does not need a Node.js server or a third-party charting runtime. The API port uses `SERVER_PORT`; when changed, update `apiBaseUrl` and the Google authorized redirect URI. Configure UI return URL with `UI_BASE_URL` and allowed frontend origins with the comma-separated `CORS_ALLOWED_ORIGINS` setting.

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

The frontend calls the existing Analytics and YouTube Data API services through this workspace layer. It reuses the existing deterministic retention, discovery and recommendation services rather than duplicating that business logic in JavaScript.

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
- **Audience Studio-only surfaces:** “when your viewers are on YouTube”, other channels/videos audience research, and other unavailable Studio-only metrics are described as unavailable instead of synthesized.

## Business logic locations

- `service/businesslogic/AnalyticsWorkspaceBusinessLogic.java` contains deterministic, unit-testable transformations for upload consistency, within-set comparisons and keyword-opportunity labeling.
- `service/businesslogic/AnalyticsWorkspaceService.java` orchestrates existing APIs/services and applies authorized-channel versus public-only access rules.
- `controller/AnalyticsWorkspaceController.java` exposes the workspace-specific endpoints above.
