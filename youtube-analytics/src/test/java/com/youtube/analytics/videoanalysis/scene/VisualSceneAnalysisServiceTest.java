package com.youtube.analytics.videoanalysis.scene;

import com.youtube.analytics.videoanalysis.analyzer.VisualSemanticAnalyzer;
import com.youtube.analytics.videoanalysis.frame.FrameExtractor;
import com.youtube.analytics.videoanalysis.model.VisualObservation;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class VisualSceneAnalysisServiceTest {

    @Test
    void analyzesThreeRepresentativeFramesPerSceneAndAggregatesEvidence() {
        SceneDetector detector = (sourceFile, durationMs) -> List.of(new SceneDetector.SceneBoundary(0, 4000));
        AtomicInteger extractedCount = new AtomicInteger();
        FrameExtractor extractor = (sourceFile, timestamps) -> {
            assertThat(timestamps).containsExactly(1000L, 2000L, 3000L);
            extractedCount.set(timestamps.size());
            return timestamps.stream()
                    .map(timestamp -> new FrameExtractor.ExtractedFrame(timestamp, Path.of("frame-" + timestamp + ".jpg")))
                    .toList();
        };
        AtomicInteger analyzedCount = new AtomicInteger();
        VisualSemanticAnalyzer analyzer = imageFile -> {
            analyzedCount.incrementAndGet();
            long timestamp = Long.parseLong(imageFile.getFileName().toString().replace("frame-", "").replace(".jpg", ""));
            return switch ((int) timestamp) {
                case 1000 -> new VisualObservation("road", List.of("car"), "mountain", 0.8);
                case 2000 -> new VisualObservation("mountain view", List.of("mountains"), "mountain", 0.9);
                default -> new VisualObservation("road", List.of("car", "trees"), "outdoor", 0.7);
            };
        };

        VisualSceneAnalysisService service = new VisualSceneAnalysisService(detector, extractor, analyzer);
        var scenes = service.analyze(Path.of("trip.mp4"), 4000);

        assertThat(extractedCount).hasValue(3);
        assertThat(analyzedCount).hasValue(3);
        assertThat(scenes).hasSize(1);
        assertThat(scenes.get(0).visualSummary())
                .contains("road")
                .contains("mountain view")
                .contains("environment: mountain, outdoor")
                .contains("visible objects: car, mountains, trees");
        assertThat(scenes.get(0).visualScore()).isCloseTo(0.8, org.assertj.core.data.Offset.offset(0.000001));
    }
}
