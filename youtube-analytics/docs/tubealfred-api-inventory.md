# Local YouTube Data API compatibility inventory

This is a **self-hosted implementation** of the five TubeAlfred-style API contracts. The service code only calls Google's official YouTube Data API v3 (and Google's public YouTube autocomplete endpoint for suggestions). It never sends requests to TubeAlfred and has no TubeAlfred API-key/base-URL configuration.

Canonical API root: `/api/youtube-analytics/v1`. YouTube Data API routes are `/api/youtube-analytics/v1/youtube/...`, analytics routes are `/api/youtube-analytics/v1/analytics/...`, and OAuth helpers are `/api/youtube-analytics/v1/oauth/...`. The legacy `/api/tubealfred` and `/api/youtubealfred` aliases are not registered. Table routes below show the `/v1/youtube/...` suffix relative to the controller base `/api/youtube-analytics`.

## Video endpoints — 9

| Local method and route | Local implementation |
|---|---|
| GET `/v1/youtube/video/{video_id}` | `videos.list(part=snippet,contentDetails,statistics,status,liveStreamingDetails)` |
| GET `/v1/youtube/video/{video_id}/enhanced` | Extended `videos.list` parts (status, player, topics, live and recording details) |
| GET `/v1/youtube/video/{video_id}/transcript/fast` | `captions.list` + `captions.download`; only works for caption tracks the OAuth user is authorized to edit |
| GET `/v1/youtube/video/{video_id}/comments` | `commentThreads.list`, including `count`, `sort` |
| POST `/v1/youtube/video/{video_id}/comments/page` | `commentThreads.list` with body `continuation_token` and optional `count` |
| GET `/v1/youtube/video/{video_id}/comments/{comment_id}/replies` | `comments.list(parentId=...)` |
| POST `/v1/youtube/video/{video_id}/comments/{comment_id}/replies/page` | `comments.list` with body pagination token |
| GET `/v1/youtube/video/{video_id}/related` | Best-effort search based on the video's title/description/tags. Google no longer exposes direct relatedToVideoId search. |
| POST `/v1/youtube/video/{video_id}/related/page` | Same search approximation with body `continuation_token` |

## Channel endpoints — 12

| Local method and route | Local implementation |
|---|---|
| GET `/v1/youtube/channel/{channel_id}` | `channels.list`, accepts ID, @handle, username or channel URL |
| GET `/v1/youtube/channel/{channel_id}/about` | `channels.list` about/branding/statistics parts |
| GET `/v1/youtube/channel/{channel_id}/videos` | `channels.list` to get uploads playlist, then `playlistItems.list` + `videos.list` |
| POST `/v1/youtube/channel/{channel_id}/videos/page` | Same uploads flow with body `continuation_token` |
| GET `/v1/youtube/channel/{channel_id}/streams` | Latest channel uploads filtered using live-stream metadata; not a dedicated feed |
| POST `/v1/youtube/channel/{channel_id}/streams/page` | Same best-effort stream filter with body pagination token |
| GET `/v1/youtube/channel/{channel_id}/shorts` | Uploads filtered to videos up to three minutes as a best-effort Shorts approximation |
| POST `/v1/youtube/channel/{channel_id}/shorts/page` | Same Shorts approximation with body pagination token |
| GET `/v1/youtube/channel/{channel_id}/playlists` | `playlists.list` |
| POST `/v1/youtube/channel/{channel_id}/playlists/page` | `playlists.list` with body `continuation_token` |
| GET `/v1/youtube/channel/{channel_id}/community` | Explicit unsupported response: official API has no public Community-post listing |
| POST `/v1/youtube/channel/{channel_id}/community/page` | Explicit unsupported response: official API has no public Community-post listing |

## Discovery endpoints — 7

| Local method and route | Local implementation |
|---|---|
| GET `/v1/youtube/search` | `search.list` with `query`, `continuation_token`, `channel_id`, `upload_date`, `duration`, `sort`, `type`, `features`, `live`, `shorts` |
| POST `/v1/youtube/search/page` | Same mapping from the typed search body |
| GET `/v1/youtube/search/suggestions` | Google's public YouTube autocomplete endpoint; no API key |
| GET `/v1/youtube/search/hashtag` | `search.list` for hashtag query |
| POST `/v1/youtube/search/hashtag/page` | Same hashtag search with body `continuation_token` |
| GET `/v1/youtube/trending` | `videos.list(chart=mostPopular,regionCode=...)`; defaults to IN |
| GET `/v1/youtube/trending/shorts` | Most-popular regional videos filtered to three minutes or less (approximation) |

## Playlist endpoints — 3

| Local method and route | Local implementation |
|---|---|
| GET `/v1/youtube/playlist/{playlist_id}/metadata` | `playlists.list` |
| GET `/v1/youtube/playlist/{playlist_id}` | `playlistItems.list`, enrich with video details |
| POST `/v1/youtube/playlist/{playlist_id}/page` | Same mapping with body `continuation_token` |

## Utility and batch endpoints — 3

| Local method and route | Local implementation |
|---|---|
| GET `/v1/youtube/utility/resolve` | Local URL parser for watch, youtu.be, Shorts, live, embed, playlist, channel ID, username and @handle URLs; no network request required |
| POST `/v1/youtube/videos:batch` | Validates 1–50 IDs/URLs and calls `videos.list` |
| POST `/v1/youtube/channels:batch` | Validates 1–50 IDs/handles/usernames/URLs and calls `channels.list` |

## Request / response behavior

- Pagination accepts TubeAlfred-compatible `continuation_token` values; the implementation maps those tokens to Google's `pageToken`. Responses preserve Google's original `nextPageToken` and add the alias `continuation_token`.
- Pagination body schemas: `ContinuationTokenRequest(continuation_token)`, `CommentsPageRequest(continuation_token,count)`, `SearchPageRequest(query,continuation_token,channel_id,upload_date,duration,sort,type,features,live,shorts)`, `HashtagPageRequest(hashtag,continuation_token)`, and `BatchIdsRequest(ids)`.
- Responses use `{status:"success", data: ...}`; errors use `{status:"error", code, message, data:null, details}`. Google's JSON item/resource fields are preserved. Approximation notes are attached to endpoints where Google doesn't expose an exact equivalent.
- OAuth scopes: `youtube.readonly` for read endpoints plus `youtube.force-ssl` for the OAuth-protected captions API. User must re-authorize to grant the added scope.
- No code or configuration calls `api.tubealfred.com` or requires `TUBEALFRED_API_KEY`.

## Official documentation

- [YouTube Data API v3](https://developers.google.com/youtube/v3)
- [Videos list](https://developers.google.com/youtube/v3/docs/videos/list)
- [Search list](https://developers.google.com/youtube/v3/docs/search/list)
- [Captions list](https://developers.google.com/youtube/v3/docs/captions/list)
- [Comment threads list](https://developers.google.com/youtube/v3/docs/commentThreads/list)
- [Playlist items list](https://developers.google.com/youtube/v3/docs/playlistItems/list)
