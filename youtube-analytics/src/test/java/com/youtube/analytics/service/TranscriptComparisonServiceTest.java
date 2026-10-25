package com.youtube.analytics.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TranscriptComparisonServiceTest {

    @Test
    void acceptsRawVideoIdsAndCommonYoutubeUrlFormats() {
        assertThat(TranscriptComparisonService.normalizeVideoId("dQw4w9WgXcQ")).isEqualTo("dQw4w9WgXcQ");
        assertThat(TranscriptComparisonService.normalizeVideoId("https://youtu.be/dQw4w9WgXcQ?t=3")).isEqualTo("dQw4w9WgXcQ");
        assertThat(TranscriptComparisonService.normalizeVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ&feature=share")).isEqualTo("dQw4w9WgXcQ");
        assertThat(TranscriptComparisonService.normalizeVideoId("https://www.youtube.com/shorts/dQw4w9WgXcQ")).isEqualTo("dQw4w9WgXcQ");
        assertThat(TranscriptComparisonService.normalizeVideoId("youtube.com/embed/dQw4w9WgXcQ")).isEqualTo("dQw4w9WgXcQ");
        assertThat(TranscriptComparisonService.normalizeVideoId("https://www.youtube.com/live/dQw4w9WgXcQ")).isEqualTo("dQw4w9WgXcQ");
    }

    @Test
    void rejectsNonYoutubeUrlsAndMalformedIds() {
        assertThatThrownBy(() -> TranscriptComparisonService.normalizeVideoId("https://example.com/watch?v=dQw4w9WgXcQ"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("YouTube");
        assertThatThrownBy(() -> TranscriptComparisonService.normalizeVideoId("too-short"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TranscriptComparisonService.normalizeVideoId(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
