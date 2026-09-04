package com.youtube.analytics.videoanalysis.render;

import com.youtube.analytics.videoanalysis.model.CandidateRole;
import com.youtube.analytics.videoanalysis.model.ClipCandidate;
import com.youtube.analytics.videoanalysis.model.EditPlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CaptionServiceTest {

    @TempDir
    Path tempDirectory;

    @Test
    void writesSpeechAsTimelineSrtCaptions() throws Exception {
        ClipCandidate first = new ClipCandidate("a.mp4", 0, 2_500, CandidateRole.HOOK, 0.9, "Welcome to the trip", "", List.of());
        ClipCandidate second = new ClipCandidate("b.mp4", 0, 1_500, CandidateRole.JOURNEY, 0.8, "Let us go", "", List.of());
        EditPlan plan = new EditPlan("trip", "story", List.of(
                new EditPlan.EditSequenceItem(1, first, 0, 2_500, "hook"),
                new EditPlan.EditSequenceItem(2, second, 2_500, 4_000, "journey")), 4_000, List.of());
        Path output = new CaptionService().writeSrt(plan, tempDirectory);
        String content = Files.readString(output);
        assertThat(content).contains("00:00:00,000 --> 00:00:02,500", "Welcome to the trip")
                .contains("00:00:02,500 --> 00:00:04,000", "Let us go");
    }
}
