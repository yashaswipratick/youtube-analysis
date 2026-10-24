# YouTube Studio Analytics coverage

All endpoints below are registered in `YouTubeAnalyticsController` under the single API prefix:

`/api/youtube-analytics/v1/analytics`

They call Google's official YouTube Analytics API v2 and YouTube Reporting API v1. They do not call TubeAlfred or another paid API.

## Endpoint coverage

| Endpoint | Metrics/report | Source |
| --- | --- | --- |
| `GET /reports/overview` | Views, watch time minutes, subscriber gains/losses/net change, average view duration, average percentage viewed, likes, comments and shares; revenue fields when Google authorizes/returns them | Analytics v2 |
| `GET /reports/videos/top?limit=15` | Top videos by views with watch time, average duration/percentage viewed, subscribers gained and shares | Analytics v2 + Data API metadata |
| `GET /reports/videos/compare?videoIds=ID1&videoIds=ID2` | Side-by-side metrics for up to 50 selected videos | Analytics v2 |
| `GET /video/{videoId}` | Single-video analytics; accepts requested analytics metrics | Analytics v2 |
| `GET /video/{videoId}/daily` | Day-by-day metrics for one video | Analytics v2 |
| `GET /video/{videoId}/retention` | 100-point audience watch ratio and relative retention performance, plus average view duration/percentage viewed | Analytics v2 |
| `GET /video/{videoId}/retention/analysis` | Interpretation of retention curve and potential drop-off / top-moment signals | Existing analysis service over retention report |
| `GET /video/{videoId}/traffic-sources` | Per-video traffic source types and their views | Analytics v2 |
| `GET /reports/traffic-sources` | Channel-wide traffic source types; optional `videoId` filter | Analytics v2 |
| `GET /reports/formats` | Shorts, video-on-demand and live format comparison where the report is available | Analytics v2 `creatorContentType` |
| `GET /reports/audience` | Unique viewers where supported; views/watch time by subscribed status; age/gender percentage | Analytics v2 |
| `GET /channel/geography` | Channel views and other requested user-activity metrics by country | Analytics v2 |
| `GET /video/{videoId}/geography` | Video geography breakdown | Analytics v2 |
| `GET /reports/subtitles` | Views/watch-time aggregated by subtitle language when the asynchronous report is available | Reporting API `channel_subtitles_a3` |
| `GET /video/{videoId}/reach` | Thumbnail impressions and impressions click-through rate, when Reporting API CSVs are available | Reporting API `channel_reach_basic_a1` |
| `GET /reports/revenue` | Estimated revenue/ad revenue, gross revenue, CPM, playback-based CPM, ad impressions and monetized playbacks where the authorized channel/report supports the requested metrics | Analytics v2 |
| `GET /reports/search-terms` | Search terms that resulted in YouTube Search traffic to the channel's videos | Analytics v2 traffic-source detail |
| `GET /reports/videos/benchmarks?limit=15` | Latest 1–15 uploads with views, average duration, percentage viewed, subscribers gained, shares, first-28-day traffic-source breakdown, and reach metrics when reports are ready | Analytics v2 + Reporting API |
| `GET /reports/capabilities` | Machine-readable capability and limitation list | Local metadata |

Date-scoped endpoints use `lastNDays` only (1–3650) and default to the last 28 YouTube reporting days. Example: `GET /api/youtube-analytics/v1/analytics/video/sMMJS_Hp8KI?lastNDays=7`. `startDate` and `endDate` are not accepted as request parameters. For video-specific reports, the controller resolves the video's `publish_date` through the local video-details service and clamps the requested start to its publication reporting day, avoiding queries before the video existed. Reporting days use America/Los_Angeles. The API can return fewer recent dates than requested while the newest YouTube data is processing.

## Known official-API limitations

Some YouTube Studio cards do not have a corresponding public API metric/report. The application marks these explicitly instead of inventing data:

- **Returning viewers:** not listed as a supported metric in the official YouTube Analytics API metric catalogue.
- **When your viewers are on YouTube:** the Studio audience activity heatmap is not exposed by the official Analytics API.
- **Other channels/videos your audience watches:** these Studio research insights are not returned by the official Analytics reporting endpoints.
- **Research demand you have not covered:** the API reports queries that generated Search traffic to your videos; it does not expose all YouTube searches with no matching video.
- **Exact first 48 hours:** Analytics report dates are day-granular and follow YouTube's reporting-day definition (Pacific time). `first48Hours` is explicitly marked as an approximation using the publication date and following date; it is not an exact rolling 48-hour query.
- **Fresh views vs. processed analytics:** `GET /video/{videoId}` now fetches current public `viewCount`, `likeCount` and `commentCount` via YouTube Data API v3 when the requested window covers the video's entire lifetime. These current counters are used in `metrics`; the original Analytics API report is preserved in `analyticsMetrics`, with `currentStatistics` and `dataFreshness` explaining provenance. For shorter date-filtered windows, current lifetime counters are not substituted.
- **Engaged views freshness:** `engagedViews` remains an Analytics API v2 metric and can lag current Studio by 48–72 hours. The YouTube Data API `videos.list` response does not expose a current engagedViews counter, so the application must not fabricate or substitute a value.
- **Impressions and CTR:** use asynchronous Reporting API CSVs. They can be unavailable until YouTube generates reports, and historic report windows may not exist for every request. A null value means unavailable, not zero.
- **Subtitle languages:** the Reporting API job and CSVs are asynchronous; a report can be empty or delayed.
- **Audience demographics/unique viewers:** data is aggregate, privacy-thresholded and can be empty for small audiences or unsupported date combinations.
- **Revenue, RPM and ad metrics:** revenue needs a monetized/eligible channel and the monetary read scope. Metrics may not be supported for every report combination. Where RPM is calculated by the app, it means estimated revenue divided by views times 1,000; it is not an official standalone YouTube Analytics API metric. Revenue estimates can be adjusted after month end.
- **Likes/comments/subscribers gained:** the public Analytics API limits some metrics by report type. In video-filtered reports, subscribers gained/lost correspond to the video's watch page rather than all channel subscription activity.

## Authorization and setup

The configured scopes include:

- `https://www.googleapis.com/auth/yt-analytics.readonly`
- `https://www.googleapis.com/auth/yt-analytics-monetary.readonly`
- Existing YouTube Data API scopes, including `youtube.readonly` and `youtube.force-ssl`

After changing OAuth scopes, stop the app, re-authorize at `http://localhost:8080/oauth2/authorization/google`, and accept the requested permissions. Be sure the Google Cloud project has YouTube Analytics API / YouTube Reporting API access enabled as appropriate.

Official references:

- https://developers.google.com/youtube/analytics/metrics
- https://developers.google.com/youtube/analytics/dimensions
- https://developers.google.com/youtube/analytics/channel_reports
- https://developers.google.com/youtube/reporting/v1/reports/full_report_list
