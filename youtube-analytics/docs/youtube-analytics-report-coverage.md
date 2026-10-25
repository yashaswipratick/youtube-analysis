# YouTube Analytics report coverage

## Configuration and API sources

The application binds report metric lists through `YouTubeAnalyticsProperties` at
`youtube.analytics.metrics.*`. Reporting API field lists bind separately through
`YouTubeReportingProperties` at `youtube.reporting.metrics.*`.

- `core`, `cards`, `premium`, `retention`, `demographics`, `traffic`,
  `trend`, `revenue`, `retention-events`, `livestream`, `format`,
  `playlist`, and `geography` are Analytics API v2 metric groups.
- `optional` is a compatibility-facing list of optional reporting fields; its thumbnail
  and end-screen values are **not** valid `reports.query?metrics=...` names.
- `youtube.reporting.metrics.reach` and `youtube.reporting.metrics.end-screens`
  are Reporting API v1 column names. Reporting data is delivered as asynchronous CSV files.

The archive defaults to `./data/youtube-analytics/reports`; set
`YOUTUBE_ANALYTICS_ARCHIVE_DIR` to customize the destination. The default
`YOUTUBE_ANALYTICS_REPORTING_LAG_DAYS` is `3`, and the archive folder is ignored by Git.
Archive retention is disabled by default (`YOUTUBE_ANALYTICS_ARCHIVE_RETENTION_DAYS=0`). A
positive value removes per-video snapshot directories older than that many days when that video
is enriched; it never deletes the snapshot created by the current request. Cleanup is on demand
per video, not a background global sweep.

## Single-video response sections

`GET /api/youtube-analytics/v1/analytics/video/{videoId}?lastNDays=28` keeps the
legacy fields `metrics`, `analyticsMetrics`, `currentStatistics`, and
`dataFreshness`. It adds:

Supplementary enrichment can be limited with repeated query parameters, for example
`includeSections=core&includeSections=trend`. Supported values are `core`, `cards`, `premium`,
`retention`, `demographics`, `traffic`, `trend`, `dimensions`, `liveStream`, `liveOrOnDemand`,
`youtubeProduct`, `channelBenchmark`, `revenue`, `reach`, `reachCombined`, `endScreens`, and `playlist`.
Omitting `includeSections` preserves full legacy enrichment. Supplying one or more values executes
only those supplementary groups (in addition to reporting-window, derived-insight, and archive
metadata). Unknown values and an empty supplied list are rejected, not silently ignored. This only
reduces supplementary calls; it does not alter the base Analytics API query.

| Section | Source / query shape | Compatibility notes |
| --- | --- | --- |
| `reportingWindow` | Effective start/end dates and lag | Uses the configured lag in YouTube reporting timezone (America/Los_Angeles). |
| `core` | Basic video metrics, filtered by video | Includes first 48 hours, first 7 days, first 28 days, and lifetime windows. |
| `cards` | Card impressions/clicks/click-through rates | Queried separately from core metrics. |
| `premium` | Red/Premium views and watch time | May be unavailable for videos with no Premium activity. |
| `retention` | `elapsedVideoTimeRatio` plus retention metrics | Supports `audienceType`, subscribed status, and YouTube product filters. |
| `demographics` | `ageGroup,gender` with `viewerPercentage` | Percentages are returned as reported by YouTube. |
| `traffic` | `insightTrafficSourceType` and details | Detail requests use a source-type filter, sort, and maxResults <= 25. |
| `trend` | Daily `day` and monthly `month` rollups | Monthly requests use first-of-month bounds and completed months only. Includes previous-28-day comparison when history allows. |
| `dimensions` | Country, city, subscribed status, device, OS, sharing service, playback location, embedded playback detail and format | Each query uses a report-compatible metric list. City and detail reports are capped and sorted as required. |
| `liveStream` | Concurrent-viewer metrics by livestream position | Only populated for qualifying live-streamed videos. |
| `liveOrOnDemand` | Daily data filtered to LIVE / ON_DEMAND | Empty sections are normal when a video has no data for that mode. |
| `youtubeProduct` | Daily data filtered to supported YouTube products | Empty sections are normal for unavailable products or periods. |
| `channelBenchmark` | Top-video cohort (up to 200 videos) by views | Provides median metrics, IQR outlier fences, target rank if returned, and target-vs-median comparison. This is not a census of all uploads. |
| `revenue` | Estimated revenue (per metric), ad-type performance, playback-based performance | Separated into compatible requests; requires monetary scope and YPP eligibility where applicable. |
| `reach` | Reporting type `channel_reach_basic_a1` | Basic thumbnail impressions and CTR; asynchronous report availability and report-file lag apply. |
| `reachCombined` | Reporting type `channel_reach_combined_a1` | Reach metrics by traffic source, traffic-source detail, OS, and device; asynchronous report availability and report-file lag apply. |
| `endScreens` | Reporting type `channel_end_screens_a2` | Asynchronous report availability and report-file lag apply. |
| `playlist` | Not queried from a video ID alone | Playlist metrics require a playlist identifier. Per-video add/remove counts remain in core. |
| `derivedInsights` | Application-calculated values | Includes engagement rates, subscribers per 1,000 views, net subscribers, average watch time per view and a 28-day view change where data is available. |
| `archive` | Local JSON/CSV files and `snapshot.json` | Contains a snapshot ID and replay endpoint hint. |

### Core windows and date limitations

The Analytics API accepts calendar dates rather than upload-relative timestamps. Therefore the
first-48-hours section is a two-calendar-day approximation, not an exact rolling 48-hour
measurement. First-7-day and first-28-day sections are marked `PENDING_PROCESSING` until
their full windows are older than the configured reporting lag.

The public Data API counters for lifetime views, likes and comments remain separate from
processed Analytics API metrics. The current counters are applied to `metrics` only when the
requested range covers the publication day and its end date is not earlier than the publication
day. `analyticsMetrics` preserves the date-filtered Analytics API values.

## Playlist report endpoint

`GET /api/youtube-analytics/v1/analytics/reports/playlists/{playlistId}?lastNDays=28`
returns playlist overview metrics, daily time series, and traffic-source breakdown. It uses
the official YouTube Analytics API v2 playlist report combinations with the required
`playlist==PLAYLIST_ID` filter. Playlist IDs are validated before making Google API calls.
The playlist must be available to the authenticated channel; aggregated video metrics can be
empty for a playlist containing only videos from other channels. The report is separate from
the single-video response because playlist context cannot be inferred from a video ID alone.

Channel revenue and overview revenue are now split into three separate report groups:
estimated revenue, ad-performance metrics grouped by `adType`, and playback-based performance.
Unique viewers in the audience endpoint are queried with the required `day` dimension.
Daily unique-viewer counts are not added together to invent a multi-day total.

## Upload frequency endpoint

`GET /api/youtube-analytics/v1/analytics/reports/uploads-frequency?lastNDays=90`
uses the authenticated channel's uploads playlist to count published videos per week and per
calendar month, including empty periods. Weeks begin on Monday. Publish timestamps are read
from YouTube Data API v3 `playlistItems.contentDetails.videoPublishedAt` and cached in memory
for 30 minutes to avoid paginating the entire uploads playlist on every request. This report
measures upload cadence only; it does not imply performance or engagement. Because the cache is
process-local, separate application instances maintain their own refresh windows. Average
weekly uploads are normalized to uploads per seven calendar days across the exact inclusive
requested date range. Average monthly uploads use the mean Gregorian month length; the displayed
week/month buckets can still be partial at their edges.

## Bulk endpoint scope

`POST /api/youtube-analytics/v1/analytics/videos` and
`GET /api/youtube-analytics/v1/analytics/videos/all` return the base Analytics API results and do
not automatically run supplementary per-video report enrichment. This is intentional: enabling
full enrichment for every upload would issue many more API calls per request and can trigger quota
or latency problems on larger channels. Use the single-video route with `includeSections` to fetch
only the supplementary groups needed for a video.

`GET /api/youtube-analytics/v1/analytics/reports/videos/top` accepts `sortBy=views` (default),
`sortBy=estimatedMinutesWatched`, `sortBy=subscribersGained`, or `sortBy=subscribersLost`.
The sort values are explicitly allow-listed; unsupported sort fields return a validation error
rather than being sent to Google as a guessed query.

## Metric units

- `estimatedMinutesWatched` and `estimatedRedMinutesWatched`: minutes.
- `averageViewDuration` and `playlistAverageViewDuration`: seconds.
- `averageViewPercentage`: percentage points as returned; do not multiply by 100.
- `elapsedVideoTimeRatio`: fraction of video elapsed, normally in the 0–1 interval.
- `audienceWatchRatio`: returned by YouTube as a ratio. It can exceed 1 when segments are replayed and must not be clamped to 1.
- Thumbnail click-through rate and end-screen click rate values are preserved in the Reporting API's provided units; conversion should be based on the report schema/value definition, not assumed.

## Authentication and deployment safety

Analytics routes are protected by the Spring Security OAuth login session. Only login,
error, Swagger/OpenAPI, and the OAuth success route are publicly permitted. Do not re-add
`/api/youtube-analytics/v1/analytics/**` to the `permitAll` matcher before exposing this
service beyond a trusted local environment; those endpoints return private channel analytics
and replay snapshots.

## Error and data-availability behavior

- An empty `rows` array produces `EMPTY`; it is not treated as an exception.
- HTTP 400 becomes `INVALID_COMBINATION` for the affected report only.
- HTTP 403 becomes `FORBIDDEN_OR_MONETIZATION_REQUIRED` for the affected report only.
- HTTP 429 and all HTTP 5xx statuses receive bounded exponential retries; when exhausted, the affected section is `TEMPORARILY_UNAVAILABLE`.
- Other HTTP errors become `API_ERROR`.
- Reporting API reports are asynchronous. Missing current files return `PENDING_OR_EMPTY`; the CSV schema is used to check whether optional fields are available.
- A raw response/body is stored for each attempted supplementary API call. On the single-video path, the original base Analytics API response is also archived as `base-analytics.json`; replay loads the manifest and raw bodies from disk without calling Google APIs. Raw responses can include channel-specific analytics, so protect the archive directory and configure retention deliberately.

Replay a saved snapshot using:

`GET /api/youtube-analytics/v1/analytics/video/{videoId}/reports/replay/{snapshotId}`

## Unsupported Studio-only metrics

The public YouTube Analytics/Reporting APIs do not expose every metric and panel shown in YouTube Studio. This implementation does not synthesize the following from unrelated counters: the audience-activity heatmap (“when your viewers are on YouTube”), other channels/videos your audience watches, uncovered search-demand estimates, or the Shorts “viewed versus swiped away” panel. They remain unsupported until Google documents an authorized API/report combination for them. Returning/new-viewer metrics are only exposed where the specific documented report/query combination supports them; the application does not infer returning viewers from daily unique viewers.

## API compatibility and intentionally separate capabilities

The report queries follow the report tables in the official documentation:

- Analytics API channel reports: https://developers.google.com/youtube/analytics/channel_reports
- Analytics API metrics: https://developers.google.com/youtube/analytics/metrics
- Analytics API dimensions: https://developers.google.com/youtube/analytics/dimensions
- Reporting API metrics: https://developers.google.com/youtube/reporting/v1/reports/metrics
- Reporting API channel report types: https://developers.google.com/youtube/reporting/v1/reports/channel_reports

The following are not represented as fabricated Analytics API v2 metrics:

- `video_thumbnail_impressions`, `video_thumbnail_impressions_ctr`, and end-screen fields are Reporting API v1 file columns.
- Shorts “viewed versus swiped away” is not exposed as a supported metric by these report combinations.
- Playlist-in-context metrics need a playlist ID.
- Median benchmarks and per-1,000-view conversion values are derived by application logic and do not come from a single YouTube metric. The current channel benchmark is limited to the top 200 videos returned by the query, not every upload.
- The upload-frequency endpoint uses publish timestamps and a 30-minute in-memory cache to control Data API pagination. It does not yet share a distributed cache across application instances.
