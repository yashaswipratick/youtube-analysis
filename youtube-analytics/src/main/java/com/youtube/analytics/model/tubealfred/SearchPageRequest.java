package com.youtube.analytics.model.tubealfred;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record SearchPageRequest(
        @JsonProperty("query")
        @NotBlank
        @Schema(description = "YouTube search query.", example = "how to learn faster", requiredMode = Schema.RequiredMode.REQUIRED)
        String query,
        @JsonProperty("continuation_token")
        @NotBlank
        @Schema(description = "Opaque pagination token returned by the previous response.", example = "Token from previous response", requiredMode = Schema.RequiredMode.REQUIRED)
        String continuationToken,
        @JsonProperty("channel_id")
        @Schema(description = "Restrict search to a channel ID, handle or username.", example = "@handle")
        String channelId,
        @JsonProperty("upload_date")
        @Schema(description = "Upload date filter.", allowableValues = {"all", "today", "week", "month", "year"}, example = "month")
        String uploadDate,
        @JsonProperty("duration")
        @Schema(description = "Duration filter.", allowableValues = {"all", "under_three_mins", "three_to_twenty_mins", "over_twenty_mins"}, example = "three_to_twenty_mins")
        String duration,
        @JsonProperty("sort")
        @Schema(description = "Search ranking preference.", allowableValues = {"relevance", "popularity"}, example = "relevance")
        String sort,
        @JsonProperty("type")
        @Schema(description = "Restrict result type.", allowableValues = {"all", "video", "shorts", "channel", "playlist", "movie"}, example = "video")
        String type,
        @JsonProperty("features")
        @Schema(description = "Comma-separated feature filters.", example = "hd,subtitles")
        String features,
        @JsonProperty("live")
        @Schema(description = "Shortcut for features=live.", example = "true")
        Boolean live,
        @JsonProperty("shorts")
        @Schema(description = "Shortcut for type=shorts.", example = "true")
        Boolean shorts) {
}
