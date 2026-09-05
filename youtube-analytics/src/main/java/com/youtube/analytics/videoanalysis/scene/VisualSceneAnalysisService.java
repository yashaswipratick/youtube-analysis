package com.youtube.analytics.videoanalysis.scene;

import com.youtube.analytics.config.AnalysisVisualProperties;
import com.youtube.analytics.videoanalysis.analyzer.BasicVisualSemanticAnalyzer;
import com.youtube.analytics.videoanalysis.analyzer.OpenAiVisualSemanticAnalyzer;
import com.youtube.analytics.videoanalysis.frame.FrameExtractor;
import com.youtube.analytics.videoanalysis.model.SceneSegment;
import com.youtube.analytics.videoanalysis.model.VisualAnalysisStatus;
import com.youtube.analytics.videoanalysis.model.VisualObservation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Orchestrates deterministic local visual analysis and optional OpenAI enhancement.
 * The feature flag is intentionally evaluated only at this orchestration boundary.
 */
@Service
public class VisualSceneAnalysisService {
    private static final Logger log = LoggerFactory.getLogger(VisualSceneAnalysisService.class);

    private final SceneDetector sceneDetector;
    private final FrameExtractor frameExtractor;
    private final BasicVisualSemanticAnalyzer deterministicAnalyzer;
    private final OpenAiVisualSemanticAnalyzer aiAnalyzer;
    private final AnalysisVisualProperties visualProperties;

    public VisualSceneAnalysisService(SceneDetector sceneDetector,
                                      FrameExtractor frameExtractor,
                                      BasicVisualSemanticAnalyzer deterministicAnalyzer,
                                      OpenAiVisualSemanticAnalyzer aiAnalyzer,
                                      AnalysisVisualProperties visualProperties) {
        this.sceneDetector = sceneDetector;
        this.frameExtractor = frameExtractor;
        this.deterministicAnalyzer = deterministicAnalyzer;
        this.aiAnalyzer = aiAnalyzer;
        this.visualProperties = visualProperties;
    }

    public List<SceneSegment> analyze(Path sourceFile, long durationMs) {
        List<SceneDetector.SceneBoundary> boundaries = sceneDetector.detect(sourceFile, durationMs);
        if (boundaries.isEmpty() && durationMs > 0) {
            boundaries = List.of(new SceneDetector.SceneBoundary(0, durationMs));
        }

        List<Long> timestamps = boundaries.stream()
                .flatMap(boundary -> representativeTimestamps(boundary).stream())
                .toList();
        List<FrameExtractor.ExtractedFrame> frames = frameExtractor.extract(sourceFile, timestamps);
        List<SceneSegment> scenes = new ArrayList<>();

        try {
            int frameIndex = 0;
            for (SceneDetector.SceneBoundary boundary : boundaries) {
                List<VisualObservation> deterministic = new ArrayList<>();
                List<VisualObservation> ai = new ArrayList<>();
                for (long ignored : representativeTimestamps(boundary)) {
                    FrameExtractor.ExtractedFrame frame = frames.get(frameIndex++);
                    deterministic.add(analyzeDeterministically(frame.imageFile()));
                    if (visualProperties.includeAi()) {
                        analyzeWithAi(frame.imageFile()).ifPresent(ai::add);
                    }
                }
                VisualObservation deterministicObservation = combine(deterministic);
                VisualObservation aiObservation = ai.isEmpty() ? null : combine(ai);
                VisualAnalysisStatus aiStatus = !visualProperties.includeAi()
                        ? VisualAnalysisStatus.NOT_REQUESTED
                        : aiObservation != null ? VisualAnalysisStatus.SUCCESS : VisualAnalysisStatus.FAILED;
                scenes.add(new SceneSegment(boundary.startMs(), boundary.endMs(),
                        preferredSummary(deterministicObservation, aiObservation),
                        preferredScore(deterministicObservation, aiObservation),
                        deterministicObservation, aiObservation, aiStatus));
            }
            return scenes;
        } finally {
            for (FrameExtractor.ExtractedFrame frame : frames) {
                try { Files.deleteIfExists(frame.imageFile()); } catch (Exception ignored) { }
            }
        }
    }

    private VisualObservation analyzeDeterministically(Path imageFile) {
        return deterministicAnalyzer.analyze(imageFile);
    }

    private java.util.Optional<VisualObservation> analyzeWithAi(Path imageFile) {
        try {
            return java.util.Optional.of(aiAnalyzer.analyze(imageFile));
        } catch (Exception ex) {
            log.warn("AI visual enhancement failed for frame {}: {}", imageFile.getFileName(), ex.getMessage());
            return java.util.Optional.empty();
        }
    }

    private VisualObservation combine(List<VisualObservation> observations) {
        if (observations.isEmpty()) return null;
        String summary = observations.stream()
                .map(VisualObservation::summary)
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.joining("; "));
        List<String> objects = observations.stream()
                .flatMap(observation -> observation.objects().stream())
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new))
                .stream().toList();
        String environment = observations.stream()
                .map(VisualObservation::environment)
                .filter(value -> value != null && !value.isBlank() && !"unknown".equalsIgnoreCase(value))
                .findFirst()
                .orElse("unknown");
        double quality = observations.stream().mapToDouble(VisualObservation::qualityScore).average().orElse(0.0);
        return new VisualObservation(summary, objects, environment, quality);
    }

    private String preferredSummary(VisualObservation deterministic, VisualObservation ai) {
        if (ai == null) return deterministic == null ? "No visual observation" : deterministic.summary();
        if (deterministic == null || deterministic.summary().isBlank()) return ai.summary();
        return deterministic.summary() + " | AI: " + ai.summary();
    }

    private double preferredScore(VisualObservation deterministic, VisualObservation ai) {
        if (ai == null) return deterministic == null ? 0.0 : deterministic.qualityScore();
        if (deterministic == null) return ai.qualityScore();
        return (deterministic.qualityScore() + ai.qualityScore()) / 2.0;
    }

    private List<Long> representativeTimestamps(SceneDetector.SceneBoundary boundary) {
        long duration = boundary.endMs() - boundary.startMs();
        if (duration <= 0) return List.of(boundary.startMs());
        long first = boundary.startMs() + duration / 4;
        long middle = boundary.startMs() + duration / 2;
        long last = boundary.startMs() + (duration * 3) / 4;
        return java.util.stream.Stream.of(first, middle, last).distinct().toList();
    }
}
