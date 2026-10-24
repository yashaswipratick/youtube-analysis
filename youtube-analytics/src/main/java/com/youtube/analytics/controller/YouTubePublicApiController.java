package com.youtube.analytics.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.youtube.analytics.model.tubealfred.BatchIdsRequest;
import com.youtube.analytics.model.tubealfred.CommentsPageRequest;
import com.youtube.analytics.model.tubealfred.ContinuationTokenRequest;
import com.youtube.analytics.model.tubealfred.HashtagPageRequest;
import com.youtube.analytics.model.tubealfred.SearchPageRequest;
import com.youtube.analytics.service.YouTubePublicApiService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/youtube-analytics")
@Tag(name = "Local YouTube Data API", description = "Self-hosted compatibility API implemented with the authenticated Google YouTube Data API v3; no TubeAlfred paid service is used.")
public class YouTubePublicApiController {
    private final YouTubePublicApiService youTubePublicApiService;

    @Operation(summary = "Video details", description = "Fetch standard public video metadata, statistics and other available fields. Google API mapping: GET /v1/youtube/video/{video_id}. The local response contains official Google API resource fields; documented limitations are returned explicitly.")
    @GetMapping("/v1/youtube/video/{video_id}")
    public ResponseEntity<JsonNode> videoDetails(
            @PathVariable("video_id") String videoId,
            @Parameter(description = "Comma-separated response fields to include.") @RequestParam(name = "fields", required = false) String fields,
            HttpServletRequest servletRequest) {
        return youTubePublicApiService.handle(servletRequest, null);
    }

    @Operation(summary = "Enhanced video details", description = "Fetch enhanced video metadata, including additional live/category/chapter/related-video fields when available. Google API mapping: GET /v1/youtube/video/{video_id}/enhanced. The local response contains official Google API resource fields; documented limitations are returned explicitly.")
    @GetMapping("/v1/youtube/video/{video_id}/enhanced")
    public ResponseEntity<JsonNode> enhancedVideoDetails(
            @PathVariable("video_id") String videoId,
            @Parameter(description = "Comma-separated response fields to include.") @RequestParam(name = "fields", required = false) String fields,
            HttpServletRequest servletRequest) {
        return youTubePublicApiService.handle(servletRequest, null);
    }

    @Operation(summary = "Video transcript fast", description = "Fetch available captions, language metadata and transcript segments for a video. Google API mapping: GET /v1/youtube/video/{video_id}/transcript/fast. The local response contains official Google API resource fields; documented limitations are returned explicitly.")
    @GetMapping("/v1/youtube/video/{video_id}/transcript/fast")
    public ResponseEntity<JsonNode> videoTranscriptFast(
            @PathVariable("video_id") String videoId,
            @Parameter(description = "Preferred caption language code, for example en, es or en-US.") @RequestParam(name = "language", required = false) String language,
            @Parameter(description = "Caption track preference.", schema = @Schema(allowableValues = {"manual", "auto", "any"})) @RequestParam(name = "kind", required = false) String kind,
            HttpServletRequest servletRequest) {
        return youTubePublicApiService.handle(servletRequest, null);
    }

    @Operation(summary = "Video comments", description = "Fetch the first page of public comments for a video. Google API mapping: GET /v1/youtube/video/{video_id}/comments. The local response contains official Google API resource fields; documented limitations are returned explicitly.")
    @GetMapping("/v1/youtube/video/{video_id}/comments")
    public ResponseEntity<JsonNode> videoComments(
            @PathVariable("video_id") String videoId,
            @Parameter(description = "Number of comments to fetch per page.") @RequestParam(name = "count", required = false) Integer count,
            @Parameter(description = "Comment sort order.", schema = @Schema(allowableValues = {"top", "newest"})) @RequestParam(name = "sort", required = false) String sort,
            HttpServletRequest servletRequest) {
        return youTubePublicApiService.handle(servletRequest, null);
    }

    @Operation(summary = "Video comments page", description = "Fetch a subsequent page of video comments using a continuation token. Google API mapping: POST /v1/youtube/video/{video_id}/comments/page. The local response contains official Google API resource fields; documented limitations are returned explicitly.")
    @PostMapping("/v1/youtube/video/{video_id}/comments/page")
    public ResponseEntity<JsonNode> videoCommentsPage(
            @PathVariable("video_id") String videoId,
            @Parameter(description = "Optional idempotency key for retry-safe POST operations.", in = ParameterIn.HEADER) @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CommentsPageRequest body,
            HttpServletRequest servletRequest) {
        return youTubePublicApiService.handle(servletRequest, body);
    }

    @Operation(summary = "Comment replies", description = "Fetch the first page of replies for a parent comment. Google API mapping: GET /v1/youtube/video/{video_id}/comments/{comment_id}/replies. The local response contains official Google API resource fields; documented limitations are returned explicitly.")
    @GetMapping("/v1/youtube/video/{video_id}/comments/{comment_id}/replies")
    public ResponseEntity<JsonNode> videoCommentReplies(
            @PathVariable("video_id") String videoId,
            @PathVariable("comment_id") String commentId,
            @Parameter(description = "Number of comments to fetch per page.") @RequestParam(name = "count", required = false) Integer count,
            @Parameter(description = "Comment sort order.", schema = @Schema(allowableValues = {"top", "newest"})) @RequestParam(name = "sort", required = false) String sort,
            HttpServletRequest servletRequest) {
        return youTubePublicApiService.handle(servletRequest, null);
    }

    @Operation(summary = "Comment replies page", description = "Fetch a subsequent page of replies for a parent comment. Google API mapping: POST /v1/youtube/video/{video_id}/comments/{comment_id}/replies/page. The local response contains official Google API resource fields; documented limitations are returned explicitly.")
    @PostMapping("/v1/youtube/video/{video_id}/comments/{comment_id}/replies/page")
    public ResponseEntity<JsonNode> videoCommentRepliesPage(
            @PathVariable("video_id") String videoId,
            @PathVariable("comment_id") String commentId,
            @Parameter(description = "Optional idempotency key for retry-safe POST operations.", in = ParameterIn.HEADER) @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CommentsPageRequest body,
            HttpServletRequest servletRequest) {
        return youTubePublicApiService.handle(servletRequest, body);
    }

    @Operation(summary = "Related videos", description = "Fetch the first or continuation page of videos related to the specified video. Google API mapping: GET /v1/youtube/video/{video_id}/related. The local response contains official Google API resource fields; documented limitations are returned explicitly.")
    @GetMapping("/v1/youtube/video/{video_id}/related")
    public ResponseEntity<JsonNode> relatedVideos(
            @PathVariable("video_id") String videoId,
            @Parameter(description = "Opaque pagination token; leave blank for the first page.") @RequestParam(name = "continuation_token", required = false) String continuationToken,
            HttpServletRequest servletRequest) {
        return youTubePublicApiService.handle(servletRequest, null);
    }

    @Operation(summary = "Related videos page", description = "Fetch a subsequent related-videos page. Google API mapping: POST /v1/youtube/video/{video_id}/related/page. The local response contains official Google API resource fields; documented limitations are returned explicitly.")
    @PostMapping("/v1/youtube/video/{video_id}/related/page")
    public ResponseEntity<JsonNode> relatedVideosPage(
            @PathVariable("video_id") String videoId,
            @Parameter(description = "Optional idempotency key for retry-safe POST operations.", in = ParameterIn.HEADER) @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ContinuationTokenRequest body,
            HttpServletRequest servletRequest) {
        return youTubePublicApiService.handle(servletRequest, body);
    }

    @Operation(summary = "Channel details", description = "Fetch public channel metadata. Accepts a channel ID, @handle, username or full channel URL. Google API mapping: GET /v1/youtube/channel/{channel_id}. The local response contains official Google API resource fields; documented limitations are returned explicitly.")
    @GetMapping("/v1/youtube/channel/{channel_id}")
    public ResponseEntity<JsonNode> channelDetails(
            @PathVariable("channel_id") String channelId,
            @Parameter(description = "Comma-separated response fields to include.") @RequestParam(name = "fields", required = false) String fields,
            HttpServletRequest servletRequest) {
        return youTubePublicApiService.handle(servletRequest, null);
    }

    @Operation(summary = "Channel about", description = "Fetch channel about information such as country, join date, total views and links when available. Google API mapping: GET /v1/youtube/channel/{channel_id}/about. The local response contains official Google API resource fields; documented limitations are returned explicitly.")
    @GetMapping("/v1/youtube/channel/{channel_id}/about")
    public ResponseEntity<JsonNode> channelAbout(
            @PathVariable("channel_id") String channelId,
            @Parameter(description = "Comma-separated response fields to include.") @RequestParam(name = "fields", required = false) String fields,
            HttpServletRequest servletRequest) {
        return youTubePublicApiService.handle(servletRequest, null);
    }

    @Operation(summary = "Channel videos", description = "Fetch the first or continuation page of videos uploaded by a channel. Google API mapping: GET /v1/youtube/channel/{channel_id}/videos. The local response contains official Google API resource fields; documented limitations are returned explicitly.")
    @GetMapping("/v1/youtube/channel/{channel_id}/videos")
    public ResponseEntity<JsonNode> channelVideos(
            @PathVariable("channel_id") String channelId,
            @Parameter(description = "Opaque pagination token; leave blank for the first page.") @RequestParam(name = "continuation_token", required = false) String continuationToken,
            HttpServletRequest servletRequest) {
        return youTubePublicApiService.handle(servletRequest, null);
    }

    @Operation(summary = "Channel videos page", description = "Fetch a subsequent page of channel uploads. Google API mapping: POST /v1/youtube/channel/{channel_id}/videos/page. The local response contains official Google API resource fields; documented limitations are returned explicitly.")
    @PostMapping("/v1/youtube/channel/{channel_id}/videos/page")
    public ResponseEntity<JsonNode> channelVideosPage(
            @PathVariable("channel_id") String channelId,
            @Parameter(description = "Optional idempotency key for retry-safe POST operations.", in = ParameterIn.HEADER) @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ContinuationTokenRequest body,
            HttpServletRequest servletRequest) {
        return youTubePublicApiService.handle(servletRequest, body);
    }

    @Operation(summary = "Channel live streams", description = "Fetch a channel's live streams. Google API mapping: GET /v1/youtube/channel/{channel_id}/streams. The local response contains official Google API resource fields; documented limitations are returned explicitly.")
    @GetMapping("/v1/youtube/channel/{channel_id}/streams")
    public ResponseEntity<JsonNode> channelStreams(
            @PathVariable("channel_id") String channelId,
            @Parameter(description = "Opaque pagination token; leave blank for the first page.") @RequestParam(name = "continuation_token", required = false) String continuationToken,
            HttpServletRequest servletRequest) {
        return youTubePublicApiService.handle(servletRequest, null);
    }

    @Operation(summary = "Channel live streams page", description = "Fetch a subsequent page of channel live streams. Google API mapping: POST /v1/youtube/channel/{channel_id}/streams/page. The local response contains official Google API resource fields; documented limitations are returned explicitly.")
    @PostMapping("/v1/youtube/channel/{channel_id}/streams/page")
    public ResponseEntity<JsonNode> channelStreamsPage(
            @PathVariable("channel_id") String channelId,
            @Parameter(description = "Optional idempotency key for retry-safe POST operations.", in = ParameterIn.HEADER) @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ContinuationTokenRequest body,
            HttpServletRequest servletRequest) {
        return youTubePublicApiService.handle(servletRequest, body);
    }

    @Operation(summary = "Channel Shorts", description = "Fetch a channel's Shorts. Google API mapping: GET /v1/youtube/channel/{channel_id}/shorts. The local response contains official Google API resource fields; documented limitations are returned explicitly.")
    @GetMapping("/v1/youtube/channel/{channel_id}/shorts")
    public ResponseEntity<JsonNode> channelShorts(
            @PathVariable("channel_id") String channelId,
            @Parameter(description = "Opaque pagination token; leave blank for the first page.") @RequestParam(name = "continuation_token", required = false) String continuationToken,
            HttpServletRequest servletRequest) {
        return youTubePublicApiService.handle(servletRequest, null);
    }

    @Operation(summary = "Channel Shorts page", description = "Fetch a subsequent page of channel Shorts. Google API mapping: POST /v1/youtube/channel/{channel_id}/shorts/page. The local response contains official Google API resource fields; documented limitations are returned explicitly.")
    @PostMapping("/v1/youtube/channel/{channel_id}/shorts/page")
    public ResponseEntity<JsonNode> channelShortsPage(
            @PathVariable("channel_id") String channelId,
            @Parameter(description = "Optional idempotency key for retry-safe POST operations.", in = ParameterIn.HEADER) @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ContinuationTokenRequest body,
            HttpServletRequest servletRequest) {
        return youTubePublicApiService.handle(servletRequest, body);
    }

    @Operation(summary = "Channel playlists", description = "Fetch playlists published by a channel. Google API mapping: GET /v1/youtube/channel/{channel_id}/playlists. The local response contains official Google API resource fields; documented limitations are returned explicitly.")
    @GetMapping("/v1/youtube/channel/{channel_id}/playlists")
    public ResponseEntity<JsonNode> channelPlaylists(
            @PathVariable("channel_id") String channelId,
            @Parameter(description = "Opaque pagination token; leave blank for the first page.") @RequestParam(name = "continuation_token", required = false) String continuationToken,
            HttpServletRequest servletRequest) {
        return youTubePublicApiService.handle(servletRequest, null);
    }

    @Operation(summary = "Channel playlists page", description = "Fetch a subsequent page of playlists published by a channel. Google API mapping: POST /v1/youtube/channel/{channel_id}/playlists/page. The local response contains official Google API resource fields; documented limitations are returned explicitly.")
    @PostMapping("/v1/youtube/channel/{channel_id}/playlists/page")
    public ResponseEntity<JsonNode> channelPlaylistsPage(
            @PathVariable("channel_id") String channelId,
            @Parameter(description = "Optional idempotency key for retry-safe POST operations.", in = ParameterIn.HEADER) @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ContinuationTokenRequest body,
            HttpServletRequest servletRequest) {
        return youTubePublicApiService.handle(servletRequest, body);
    }

    @Operation(summary = "Channel community posts", description = "Fetch public community posts for a channel when available. Google API mapping: GET /v1/youtube/channel/{channel_id}/community. The local response contains official Google API resource fields; documented limitations are returned explicitly.")
    @GetMapping("/v1/youtube/channel/{channel_id}/community")
    public ResponseEntity<JsonNode> channelCommunity(
            @PathVariable("channel_id") String channelId,
            @Parameter(description = "Opaque pagination token; leave blank for the first page.") @RequestParam(name = "continuation_token", required = false) String continuationToken,
            HttpServletRequest servletRequest) {
        return youTubePublicApiService.handle(servletRequest, null);
    }

    @Operation(summary = "Channel community posts page", description = "Fetch a subsequent page of channel community posts. Google API mapping: POST /v1/youtube/channel/{channel_id}/community/page. The local response contains official Google API resource fields; documented limitations are returned explicitly.")
    @PostMapping("/v1/youtube/channel/{channel_id}/community/page")
    public ResponseEntity<JsonNode> channelCommunityPage(
            @PathVariable("channel_id") String channelId,
            @Parameter(description = "Optional idempotency key for retry-safe POST operations.", in = ParameterIn.HEADER) @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ContinuationTokenRequest body,
            HttpServletRequest servletRequest) {
        return youTubePublicApiService.handle(servletRequest, body);
    }

    @Operation(summary = "Search", description = "Search YouTube with optional channel, date, duration, sort, content-type, feature, live and Shorts filters. Google API mapping: GET /v1/youtube/search. The local response contains official Google API resource fields; documented limitations are returned explicitly.")
    @GetMapping("/v1/youtube/search")
    public ResponseEntity<JsonNode> youtubeSearch(
            @Parameter(description = "Search query string.") @RequestParam(name = "query", required = true) String query,
            @Parameter(description = "Opaque pagination token; leave blank for the first page.") @RequestParam(name = "continuation_token", required = false) String continuationToken,
            @Parameter(description = "Restrict results to a channel ID, handle or username.") @RequestParam(name = "channel_id", required = false) String channelId,
            @Parameter(description = "Upload date filter.", schema = @Schema(allowableValues = {"all", "today", "week", "month", "year"})) @RequestParam(name = "upload_date", required = false) String uploadDate,
            @Parameter(description = "Video duration filter.", schema = @Schema(allowableValues = {"all", "under_three_mins", "three_to_twenty_mins", "over_twenty_mins"})) @RequestParam(name = "duration", required = false) String duration,
            @Parameter(description = "Search ranking preference.", schema = @Schema(allowableValues = {"relevance", "popularity"})) @RequestParam(name = "sort", required = false) String sort,
            @Parameter(description = "Restrict result type.", schema = @Schema(allowableValues = {"all", "video", "shorts", "channel", "playlist", "movie"})) @RequestParam(name = "type", required = false) String type,
            @Parameter(description = "Comma-separated feature filters: hd, subtitles, creative_commons, 3d, live, purchased, 4k, 360, location, hdr, vr180.") @RequestParam(name = "features", required = false) String features,
            @Parameter(description = "Shortcut for features=live.") @RequestParam(name = "live", required = false) Boolean live,
            @Parameter(description = "Shortcut for type=shorts.") @RequestParam(name = "shorts", required = false) Boolean shorts,
            HttpServletRequest servletRequest) {
        return youTubePublicApiService.handle(servletRequest, null);
    }

    @Operation(summary = "Search page", description = "Fetch a subsequent search-results page with the same optional filters. Google API mapping: POST /v1/youtube/search/page. The local response contains official Google API resource fields; documented limitations are returned explicitly.")
    @PostMapping("/v1/youtube/search/page")
    public ResponseEntity<JsonNode> youtubeSearchPage(
            @Parameter(description = "Optional idempotency key for retry-safe POST operations.", in = ParameterIn.HEADER) @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody SearchPageRequest body,
            HttpServletRequest servletRequest) {
        return youTubePublicApiService.handle(servletRequest, body);
    }

    @Operation(summary = "Search suggestions", description = "Fetch search autocomplete suggestions. Google API mapping: GET /v1/youtube/search/suggestions. The local response contains official Google API resource fields; documented limitations are returned explicitly.")
    @GetMapping("/v1/youtube/search/suggestions")
    public ResponseEntity<JsonNode> searchSuggestions(
            @Parameter(description = "Suggestion query; at least two characters.") @RequestParam(name = "q", required = true) String q,
            @Parameter(description = "Optional previous query context.") @RequestParam(name = "prev", required = false) String prev,
            HttpServletRequest servletRequest) {
        return youTubePublicApiService.handle(servletRequest, null);
    }

    @Operation(summary = "Hashtag search", description = "Search for public YouTube content by hashtag. Google API mapping: GET /v1/youtube/search/hashtag. The local response contains official Google API resource fields; documented limitations are returned explicitly.")
    @GetMapping("/v1/youtube/search/hashtag")
    public ResponseEntity<JsonNode> hashtagSearch(
            @Parameter(description = "Hashtag with or without a leading #.") @RequestParam(name = "hashtag", required = true) String hashtag,
            @Parameter(description = "Opaque pagination token; leave blank for the first page.") @RequestParam(name = "continuation_token", required = false) String continuationToken,
            HttpServletRequest servletRequest) {
        return youTubePublicApiService.handle(servletRequest, null);
    }

    @Operation(summary = "Hashtag search page", description = "Fetch a subsequent page of hashtag search results. Google API mapping: POST /v1/youtube/search/hashtag/page. The local response contains official Google API resource fields; documented limitations are returned explicitly.")
    @PostMapping("/v1/youtube/search/hashtag/page")
    public ResponseEntity<JsonNode> hashtagSearchPage(
            @Parameter(description = "Optional idempotency key for retry-safe POST operations.", in = ParameterIn.HEADER) @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody HashtagPageRequest body,
            HttpServletRequest servletRequest) {
        return youTubePublicApiService.handle(servletRequest, body);
    }

    @Operation(summary = "Trending videos", description = "Fetch currently trending YouTube videos. Google API mapping: GET /v1/youtube/trending. The local response contains official Google API resource fields; documented limitations are returned explicitly.")
    @GetMapping("/v1/youtube/trending")
    public ResponseEntity<JsonNode> trendingVideos(
            HttpServletRequest servletRequest) {
        return youTubePublicApiService.handle(servletRequest, null);
    }

    @Operation(summary = "Trending Shorts", description = "Fetch currently trending YouTube Shorts. Google API mapping: GET /v1/youtube/trending/shorts. The local response contains official Google API resource fields; documented limitations are returned explicitly.")
    @GetMapping("/v1/youtube/trending/shorts")
    public ResponseEntity<JsonNode> trendingShorts(
            HttpServletRequest servletRequest) {
        return youTubePublicApiService.handle(servletRequest, null);
    }

    @Operation(summary = "Playlist metadata", description = "Fetch metadata for a YouTube playlist. Google API mapping: GET /v1/youtube/playlist/{playlist_id}/metadata. The local response contains official Google API resource fields; documented limitations are returned explicitly.")
    @GetMapping("/v1/youtube/playlist/{playlist_id}/metadata")
    public ResponseEntity<JsonNode> playlistMetadata(
            @PathVariable("playlist_id") String playlistId,
            HttpServletRequest servletRequest) {
        return youTubePublicApiService.handle(servletRequest, null);
    }

    @Operation(summary = "Playlist contents", description = "Fetch the first or continuation page of playlist contents. Google API mapping: GET /v1/youtube/playlist/{playlist_id}. The local response contains official Google API resource fields; documented limitations are returned explicitly.")
    @GetMapping("/v1/youtube/playlist/{playlist_id}")
    public ResponseEntity<JsonNode> playlistContents(
            @PathVariable("playlist_id") String playlistId,
            @Parameter(description = "Opaque pagination token; leave blank for the first page.") @RequestParam(name = "continuation_token", required = false) String continuationToken,
            HttpServletRequest servletRequest) {
        return youTubePublicApiService.handle(servletRequest, null);
    }

    @Operation(summary = "Playlist page", description = "Fetch a subsequent page of playlist contents. Google API mapping: POST /v1/youtube/playlist/{playlist_id}/page. The local response contains official Google API resource fields; documented limitations are returned explicitly.")
    @PostMapping("/v1/youtube/playlist/{playlist_id}/page")
    public ResponseEntity<JsonNode> playlistPage(
            @PathVariable("playlist_id") String playlistId,
            @Parameter(description = "Optional idempotency key for retry-safe POST operations.", in = ParameterIn.HEADER) @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ContinuationTokenRequest body,
            HttpServletRequest servletRequest) {
        return youTubePublicApiService.handle(servletRequest, body);
    }

    @Operation(summary = "Resolve URL", description = "Resolve a YouTube URL to its canonical type and identifiers. Google API mapping: GET /v1/youtube/utility/resolve. The local response contains official Google API resource fields; documented limitations are returned explicitly.")
    @GetMapping("/v1/youtube/utility/resolve")
    public ResponseEntity<JsonNode> resolveYouTubeUrl(
            @Parameter(description = "Full YouTube URL to resolve.") @RequestParam(name = "url", required = true) String url,
            HttpServletRequest servletRequest) {
        return youTubePublicApiService.handle(servletRequest, null);
    }

    @Operation(summary = "Batch video details", description = "Fetch details for 1–50 video IDs or video URLs. Google API mapping: POST /v1/youtube/videos:batch. The local response contains official Google API resource fields; documented limitations are returned explicitly.")
    @PostMapping("/v1/youtube/videos:batch")
    public ResponseEntity<JsonNode> batchVideoDetails(
            @Parameter(description = "Comma-separated response fields to include.") @RequestParam(name = "fields", required = false) String fields,
            @Parameter(description = "Optional idempotency key for retry-safe POST operations.", in = ParameterIn.HEADER) @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody BatchIdsRequest body,
            HttpServletRequest servletRequest) {
        return youTubePublicApiService.handle(servletRequest, body);
    }

    @Operation(summary = "Batch channel details", description = "Fetch details for 1–50 channel IDs, handles, usernames or channel URLs. Google API mapping: POST /v1/youtube/channels:batch. The local response contains official Google API resource fields; documented limitations are returned explicitly.")
    @PostMapping("/v1/youtube/channels:batch")
    public ResponseEntity<JsonNode> batchChannelDetails(
            @Parameter(description = "Comma-separated response fields to include.") @RequestParam(name = "fields", required = false) String fields,
            @Parameter(description = "Optional idempotency key for retry-safe POST operations.", in = ParameterIn.HEADER) @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody BatchIdsRequest body,
            HttpServletRequest servletRequest) {
        return youTubePublicApiService.handle(servletRequest, body);
    }

}
