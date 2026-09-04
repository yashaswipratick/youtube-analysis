package com.youtube.analytics.videoanalysis.scene;

import com.youtube.analytics.videoanalysis.analyzer.VisualSemanticAnalyzer;
import com.youtube.analytics.videoanalysis.frame.FrameExtractor;
import com.youtube.analytics.videoanalysis.model.SceneSegment;
import com.youtube.analytics.videoanalysis.model.VisualObservation;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Service
public class VisualSceneAnalysisService {
    private final SceneDetector sceneDetector;
    private final FrameExtractor frameExtractor;
    private final VisualSemanticAnalyzer visualSemanticAnalyzer;

    public VisualSceneAnalysisService(SceneDetector sceneDetector, FrameExtractor frameExtractor,
                                      VisualSemanticAnalyzer visualSemanticAnalyzer) {
        this.sceneDetector = sceneDetector;
        this.frameExtractor = frameExtractor;
        this.visualSemanticAnalyzer = visualSemanticAnalyzer;
    }

    public List<SceneSegment> analyze(Path sourceFile, long durationMs) {
        List<SceneDetector.SceneBoundary> boundaries = sceneDetector.detect(sourceFile, durationMs);
        if (boundaries.isEmpty() && durationMs > 0) boundaries = List.of(new SceneDetector.SceneBoundary(0, durationMs));
        List<Long> timestamps = boundaries.stream().map(b -> b.startMs() + Math.max(0, (b.endMs() - b.startMs()) / 2)).toList();
        List<FrameExtractor.ExtractedFrame> frames = frameExtractor.extract(sourceFile, timestamps);
        List<SceneSegment> scenes = new ArrayList<>();
        try {
            for (int i = 0; i < boundaries.size(); i++) {
                SceneDetector.SceneBoundary boundary = boundaries.get(i);
                VisualObservation observation = visualSemanticAnalyzer.analyze(frames.get(i).imageFile());
                String summary = observation.summary() + (observation.environment() == null || observation.environment().isBlank()
                        || "unknown".equals(observation.environment()) ? "" : ", environment: " + observation.environment());
                scenes.add(new SceneSegment(boundary.startMs(), boundary.endMs(), summary, observation.qualityScore()));
            }
            return scenes;
        } finally {
            for (FrameExtractor.ExtractedFrame frame : frames) {
                try { Files.deleteIfExists(frame.imageFile()); } catch (Exception ignored) { }
            }
        }
    }
}
