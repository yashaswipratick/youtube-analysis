package com.youtube.analytics.model.tubealfred;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.UniqueElements;

import java.util.List;

public record BatchIdsRequest(
        @JsonProperty("ids")
        @NotEmpty
        @Size(min = 1, max = 50)
        @UniqueElements
        @Schema(description = "Unique list with 1 to 50 video IDs/URLs or channel IDs/handles/usernames, depending on endpoint.", example = "[\"dQw4w9WgXcQ\"]", requiredMode = Schema.RequiredMode.REQUIRED)
        List<@NotBlank String> ids) {
}
