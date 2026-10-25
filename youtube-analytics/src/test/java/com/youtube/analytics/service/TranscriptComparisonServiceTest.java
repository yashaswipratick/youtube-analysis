package com.youtube.analytics.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    void preservesAvailableCaptionTimestampsInTheTranscriptSentToAnalysis() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode details = mapper.readTree("""
                {
                  "transcript_only_text": "The road was silent Then the mountains appeared",
                  "transcript": [
                    {"text": "The road was silent", "start_time_text": "00:03"},
                    {"text": "Then the mountains appeared", "start_ms": "125000"}
                  ]
                }
                """);

        assertThat(TranscriptComparisonService.timestampedTranscript(details))
                .isEqualTo("[00:03] The road was silent [02:05] Then the mountains appeared");
    }

    @Test
    void retainsCueTimestampsWhenCleaningPublicCaptionFiles() {
        String subtitle = """
                WEBVTT

                00:00:01.000 --> 00:00:02.500 align:start
                The road calls.

                00:00:03,000 --> 00:00:04,000
                Now the fog comes in.
                """;

        assertThat(TranscriptComparisonService.cleanSubtitleFile(subtitle))
                .isEqualTo("[00:01] The road calls. [00:03] Now the fog comes in.");
    }

    @Test
    void fallsBackToPlainTranscriptWhenCaptionSegmentsHaveNoTimestamps() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode details = mapper.readTree("""
                {
                  "transcript_only_text": "A quiet road leads into the hills.",
                  "transcript": [{"text": "A quiet road leads into the hills."}]
                }
                """);

        assertThat(TranscriptComparisonService.timestampedTranscript(details))
                .isEqualTo("A quiet road leads into the hills.");
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
