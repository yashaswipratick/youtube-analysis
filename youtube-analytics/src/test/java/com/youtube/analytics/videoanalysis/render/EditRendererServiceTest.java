package com.youtube.analytics.videoanalysis.render;

import com.youtube.analytics.videoanalysis.ingestion.LocalMediaInputProperties;
import com.youtube.analytics.videoanalysis.ingestion.MediaApprovalService;
import com.youtube.analytics.videoanalysis.model.CandidateRole;
import com.youtube.analytics.videoanalysis.model.ClipCandidate;
import com.youtube.analytics.videoanalysis.model.EditPlan;
import com.youtube.analytics.videoanalysis.service.FfprobeMediaMetadataService;
import com.youtube.analytics.videoanalysis.config.VisualEffectProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EditRendererServiceTest {

    @TempDir
    Path tempDirectory;

    private EditRendererService renderer(MediaApprovalService approval, LocalMediaInputProperties properties) {
        return new EditRendererService(approval, properties, mock(FfprobeMediaMetadataService.class),
                new AudioMixService(new com.youtube.analytics.videoanalysis.config.AudioMixProperties(null, null, 0.12, true)),
                new CaptionService(), new VisualEffectProperties(false, 0));
    }

    @Test
    void rejectsEmptyPlanBeforeTouchingMedia() {
        MediaApprovalService approval = mock(MediaApprovalService.class);
        EditRendererService renderer = renderer(approval,
                new LocalMediaInputProperties(tempDirectory.toString(), false, tempDirectory.toString()));

        assertThrows(IllegalArgumentException.class,
                () -> renderer.render(new EditPlan("project", "story", List.of(), 0, List.of())));
    }

    @Test
    void sanitizesProjectIdForOutputName() throws Exception {
        Files.createDirectories(tempDirectory);
        LocalMediaInputProperties properties = new LocalMediaInputProperties(tempDirectory.toString(), false, tempDirectory.toString());
        MediaApprovalService approval = mock(MediaApprovalService.class);
        Path missing = tempDirectory.resolve("clip.mp4");
        when(approval.getPath("clip.mp4")).thenReturn(missing);
        EditRendererService renderer = renderer(approval, properties);
        ClipCandidate clip = new ClipCandidate("clip.mp4", 0, 1000, CandidateRole.B_ROLL, 0.8, "", "", List.of());

        assertThrows(IllegalArgumentException.class,
                () -> renderer.render(new EditPlan("../unsafe project", "story", List.of(
                        new EditPlan.EditSequenceItem(1, clip, 0, 1000, "test")), 1000, List.of())));
        assertTrue(Files.exists(tempDirectory));
    }
}
