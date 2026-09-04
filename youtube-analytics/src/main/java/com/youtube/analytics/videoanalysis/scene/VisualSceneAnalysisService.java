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
        List<Long> timestamps = boundaries.stream()
                .flatMap(boundary -> representativeTimestamps(boundary).stream())
                .toList();
        List<FrameExtractor.ExtractedFrame> frames = frameExtractor.extract(sourceFile, timestamps);
        List<SceneSegment> scenes = new ArrayList<>();
        try {
            int frameIndex = 0;
            for (SceneDetector.SceneBoundary boundary : boundaries) {
                List<VisualObservation> observations = new ArrayList<>();
                for (int i = 0; i < representativeTimestamps(boundary).size(); i++) {
                    observations.add(visualSemanticAnalyzer.analyze(frames.get(frameIndex++).imageFile()));
                }
                scenes.add(toSceneSegment(boundary, observations));
            }
            return scenes;
        } finally {
            for (FrameExtractor.ExtractedFrame frame : frames) {
                try { Files.deleteIfExists(frame.imageFile()); } catch (Exception ignored) { }
            }
        }
    }

    private List<Long> representativeTimestamps(SceneDetector.SceneBoundary boundary) {
        long duration = boundary.endMs() - boundary.startMs();
        if (duration <= 0) return List.of(boundary.startMs());
        long first = boundary.startMs() + duration / 4;
        long middle = boundary.startMs() + duration / 2;
        long last = boundary.startMs() + (duration * 3) / 4;
        return List.of(first, middle, last);
    }

    private SceneSegment toSceneSegment(SceneDetector.SceneBoundary boundary, List<VisualObservation> observations) {
        String summary = observations.stream()
                .map(VisualObservation::summary)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .reduce((first, second) -> first + "; " + second)
                .orElse("No visual observation available");
        String environment = observations.stream()
                .map(VisualObservation::environment)
                .filter(value -> value != null && !value.isBlank() && !"unknown".equals(value))
                .distinct()
                .reduce((first, second) -> first.equals(second) ? first : first + ", " + second)
                .orElse("");
        List<String> objects = observations.stream()
                .flatMap(observation -> observation.objects().stream())
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
        if (!environment.isBlank()) summary += ", environment: " + environment;
        if (!objects.isEmpty()) summary += ", visible objects: " + String.join(", ", objects);
        double quality = observations.stream().mapToDouble(VisualObservation::qualityScore).average().orElse(0);
        return new SceneSegment(boundary.startMs(), boundary.endMs(), summary, quality);
    }
}
