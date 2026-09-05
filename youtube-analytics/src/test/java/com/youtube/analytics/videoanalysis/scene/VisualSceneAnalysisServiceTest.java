package com.youtube.analytics.videoanalysis.scene;

import com.youtube.analytics.videoanalysis.frame.FrameExtractor;
import com.youtube.analytics.config.AnalysisVisualProperties;
import com.youtube.analytics.videoanalysis.analyzer.BasicVisualSemanticAnalyzer;
import com.youtube.analytics.videoanalysis.analyzer.OpenAiVisualSemanticAnalyzer;
import com.youtube.analytics.videoanalysis.model.VisualObservation;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class VisualSceneAnalysisServiceTest {

    @Test
    void runsDeterministicAnalysisWithoutCallingAiWhenDisabled() throws Exception {
        SceneDetector detector = (sourceFile, durationMs) -> List.of(new SceneDetector.SceneBoundary(0, 4000));
        FrameExtractor extractor = (sourceFile, timestamps) -> {
            assertThat(timestamps).containsExactly(1000L, 2000L, 3000L);
            return timestamps.stream()
                    .map(timestamp -> {
                        try {
                            Path file = Files.createTempFile("frame-", ".jpg");
                            return new FrameExtractor.ExtractedFrame(timestamp, file);
                        } catch (Exception ex) {
                            throw new IllegalStateException(ex);
                        }
                    })
                    .toList();
        };
        Path source = Path.of("trip.mp4");
        BasicVisualSemanticAnalyzer deterministic = mock(BasicVisualSemanticAnalyzer.class);
        when(deterministic.analyze(any())).thenReturn(new VisualObservation("local frame", List.of(), "unknown", 0.75));
        OpenAiVisualSemanticAnalyzer ai = mock(OpenAiVisualSemanticAnalyzer.class);

        VisualSceneAnalysisService service = new VisualSceneAnalysisService(detector, extractor, deterministic, ai,
                new AnalysisVisualProperties(false));
        var scenes = service.analyze(source, 4000);

        assertThat(scenes).hasSize(1);
        assertThat(scenes.get(0).visualSummary()).isEqualTo("local frame; local frame; local frame");
        assertThat(scenes.get(0).visualScore()).isEqualTo(0.75);
        assertThat(scenes.get(0).deterministicVisual()).isNotNull();
        assertThat(scenes.get(0).aiVisual()).isNull();
        assertThat(scenes.get(0).aiStatus()).isEqualTo(com.youtube.analytics.videoanalysis.model.VisualAnalysisStatus.NOT_REQUESTED);
        verify(deterministic, times(3)).analyze(any());
        verifyNoInteractions(ai);
    }

    @Test
    void combinesDeterministicAndAiObservationsAndKeepsDeterministicResultWhenAiFails() throws Exception {
        SceneDetector detector = (sourceFile, durationMs) -> List.of(new SceneDetector.SceneBoundary(0, 4000));
        FrameExtractor extractor = (sourceFile, timestamps) -> timestamps.stream().map(timestamp -> {
            try {
                Path file = Files.createTempFile("frame-", ".jpg");
                return new FrameExtractor.ExtractedFrame(timestamp, file);
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }).toList();
        BasicVisualSemanticAnalyzer deterministic = mock(BasicVisualSemanticAnalyzer.class);
        when(deterministic.analyze(any())).thenReturn(new VisualObservation("local frame", List.of(), "unknown", 0.75));
        OpenAiVisualSemanticAnalyzer ai = mock(OpenAiVisualSemanticAnalyzer.class);
        when(ai.analyze(any())).thenReturn(new VisualObservation("mountain road", List.of("road", "mountains"), "outdoor", 0.9));

        VisualSceneAnalysisService service = new VisualSceneAnalysisService(detector, extractor, deterministic, ai,
                new AnalysisVisualProperties(true));
        var scenes = service.analyze(Path.of("trip.mp4"), 4000);

        assertThat(scenes.get(0).visualSummary()).contains("local frame", "AI: mountain road");
        assertThat(scenes.get(0).visualScore()).isEqualTo(0.825);
        assertThat(scenes.get(0).deterministicVisual().qualityScore()).isEqualTo(0.75);
        assertThat(scenes.get(0).aiVisual().objects()).containsExactly("road", "mountains");
        assertThat(scenes.get(0).aiStatus()).isEqualTo(com.youtube.analytics.videoanalysis.model.VisualAnalysisStatus.SUCCESS);

        reset(ai);
        when(ai.analyze(any())).thenThrow(new IllegalStateException("AI unavailable"));
        scenes = service.analyze(Path.of("trip.mp4"), 4000);
        assertThat(scenes.get(0).deterministicVisual().summary()).isEqualTo("local frame; local frame; local frame");
        assertThat(scenes.get(0).aiVisual()).isNull();
        assertThat(scenes.get(0).visualScore()).isEqualTo(0.75);
        assertThat(scenes.get(0).aiStatus()).isEqualTo(com.youtube.analytics.videoanalysis.model.VisualAnalysisStatus.FAILED);
    }
}
