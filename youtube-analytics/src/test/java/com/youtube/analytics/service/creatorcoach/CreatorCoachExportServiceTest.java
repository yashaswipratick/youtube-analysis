package com.youtube.analytics.service.creatorcoach;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CreatorCoachExportServiceTest {

    private final CreatorCoachExportService exports = new CreatorCoachExportService(new ObjectMapper());

    @Test
    void generatesSinglePagePdfAndCopyableTextWithFixFirstAndChecklist() throws Exception {
        Map<String, Object> report = Map.of(
                "title", "A quiet road to Coorg",
                "plain_summary", "Keep the cinematic opening and give viewers a reason to follow the journey.",
                "fix_first", Map.of("action", "Ask one natural question early", "why", "It adds curiosity without changing your voice.",
                        "steps", List.of("Keep the poetic opening", "Ask the question on camera")),
                "segments", List.of(Map.of("label", "Opening", "start_sec", 0, "end_sec", 20, "what_happens", "Misty mountain narration")),
                "title_check", Map.of("explanation", "The title promise appears after the opening.", "percent_on_title_promise", 65.0),
                "keep_doing", List.of(Map.of("strength", "Sensory narration", "quote", "Pahaadon par chhayi dhundh")),
                "findings", List.of(Map.of("title", "Opening curiosity", "observation", "A question may help viewers know why to continue.",
                        "best_guess", "This might be because the question gives the journey a clear direction.")),
                "recommendations", List.of(Map.of("action", "Try the question in your next upload", "reason", "One change at a time.",
                        "test_steps", List.of("Compare a similar next upload"), "success_signal", "First-30-seconds percentage")),
                "next_video_checklist", List.of("Preserve the cinematic opening", "Add one curiosity question", "Deliver on the title"),
                "cannot_tell_you", List.of("Music and B-roll quality cannot be judged from transcript text."));

        Map<String, Object> completeReport = new java.util.LinkedHashMap<>(report);
        completeReport.put("metrics", Map.of(
                "views", 137,
                "avg_watch_seconds", 106,
                "average_watch_percent", 32.5,
                "impressions", 2500,
                "thumbnail_click_percent", 3.8,
                "new_subscribers", 4,
                "shares", 7,
                "traffic_share", Map.of("available", true, "sources", List.of(
                        Map.of("source_type", "YT_SEARCH", "percent_of_views", 42.5),
                        Map.of("source_type", "RELATED_VIDEO", "percent_of_views", 35.0)))));
        byte[] pdf = exports.pdf(completeReport);
        assertThat(pdf).startsWith(new byte[]{'%', 'P', 'D', 'F'});
        try (PDDocument document = Loader.loadPDF(pdf)) {
            assertThat(document.getNumberOfPages()).isEqualTo(1);
        }

        String text = new String(exports.plainText(completeReport), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(text).contains("FIX THIS FIRST", "Ask one natural question early",
                "Next-video checklist", "Preserve the cinematic opening", "What this report cannot tell you",
                "Your numbers", "137", "1 min 46 s", "How people found your video", "YouTube search");
    }

    @Test
    void exportsFivePartOutlineAsReadableText() {
        Map<String, Object> plan = Map.of("title", "Hidden places near Bangalore", "outline", Map.of(
                "opening", "A cinematic question", "personal_reason", "A quiet weekend break",
                "journey_discoveries", "The reservoir and trail", "reflection", "A little sukoon",
                "ending", "Answer the opening question"));
        String text = new String(exports.plainTextPlan(plan), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(text).contains("Five-part travel-video outline", "1. Opening / curiosity",
                "2. Why this journey matters to you", "3. Journey and discoveries",
                "4. Personal reflection", "5. Ending / answer to the opening");
    }
}
