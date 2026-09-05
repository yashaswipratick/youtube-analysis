package com.youtube.analytics.videoanalysis.scene;

import com.youtube.analytics.videoanalysis.frame.FrameExtractor;
import com.youtube.analytics.videoanalysis.service.AnalysisRequestExchangeService;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class VisualSceneAnalysisServiceTest {

    @Test
    void persistsThreeRepresentativeFramesPerSceneWithoutAiAnalysis() {
        SceneDetector detector = (sourceFile, durationMs) -> List.of(new SceneDetector.SceneBoundary(0, 4000));
        AtomicInteger extractedCount = new AtomicInteger();
        FrameExtractor extractor = (sourceFile, timestamps) -> {
            assertThat(timestamps).containsExactly(1000L, 2000L, 3000L);
            extractedCount.set(timestamps.size());
            return timestamps.stream()
                    .map(timestamp -> new FrameExtractor.ExtractedFrame(timestamp, Path.of("frame-" + timestamp + ".jpg")))
                    .toList();
        };
        AnalysisRequestExchangeService exchange = mock(AnalysisRequestExchangeService.class);
        Path source = Path.of("trip.mp4");

        VisualSceneAnalysisService service = new VisualSceneAnalysisService(detector, extractor, exchange);
        var scenes = service.analyze(source, 4000);

        assertThat(extractedCount).hasValue(3);
        assertThat(scenes).hasSize(1);
        assertThat(scenes.get(0).visualSummary()).isEqualTo("Pending ChatGPT visual analysis");
        assertThat(scenes.get(0).visualScore()).isZero();
        verify(exchange, times(3)).saveVisualRequest(eq(source), anyLong(), any());
    }
}
