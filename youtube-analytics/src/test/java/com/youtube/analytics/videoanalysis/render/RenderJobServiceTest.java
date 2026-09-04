package com.youtube.analytics.videoanalysis.render;

import com.youtube.analytics.videoanalysis.config.RenderJobProperties;
import com.youtube.analytics.videoanalysis.model.EditPlan;
import com.youtube.analytics.videoanalysis.model.RenderJob;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RenderJobServiceTest {
    @Test
    void queuesAndCompletesRenderJob() throws Exception {
        EditRendererService renderer = mock(EditRendererService.class);
        when(renderer.render(any())).thenReturn(Path.of("renders/project-edit.mp4"));
        RenderJobService service = new RenderJobService(renderer, new RenderJobProperties(1));
        RenderJob queued = service.submit(new EditPlan("project", "story", List.of(
                new EditPlan.EditSequenceItem(1, null, 0, 1000, "test")), 1000, List.of()));
        for (int i = 0; i < 50; i++) {
            if (service.get(queued.jobId()).status() == RenderJob.Status.COMPLETED) break;
            Thread.sleep(10);
        }
        RenderJob completed = service.get(queued.jobId());
        assertThat(completed.status()).isEqualTo(RenderJob.Status.COMPLETED);
        assertThat(completed.outputPath()).contains("project-edit.mp4");
    }

    @Test
    void rejectsUnknownJob() {
        RenderJobService service = new RenderJobService(mock(EditRendererService.class), new RenderJobProperties(1));
        assertThatThrownBy(() -> service.get("missing")).isInstanceOf(IllegalArgumentException.class);
    }
}
