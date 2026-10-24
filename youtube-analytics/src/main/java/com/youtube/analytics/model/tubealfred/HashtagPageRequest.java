package com.youtube.analytics.model.tubealfred;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record HashtagPageRequest(
        @JsonProperty("hashtag")
        @NotBlank
        @Schema(description = "Hashtag with or without the leading #.", example = "#music", requiredMode = Schema.RequiredMode.REQUIRED)
        String hashtag,
        @JsonProperty("continuation_token")
        @NotBlank
        @Schema(description = "Opaque pagination token returned by the previous response.", example = "Token from previous response", requiredMode = Schema.RequiredMode.REQUIRED)
        String continuationToken) {
}
