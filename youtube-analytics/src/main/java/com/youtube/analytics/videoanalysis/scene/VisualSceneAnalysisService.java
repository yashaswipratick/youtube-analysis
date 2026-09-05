package com.youtube.analytics.videoanalysis.scene;

import com.youtube.analytics.videoanalysis.frame.FrameExtractor;
import com.youtube.analytics.videoanalysis.model.SceneSegment;
import com.youtube.analytics.videoanalysis.service.AnalysisRequestExchangeService;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Service
public class VisualSceneAnalysisService {
    private final SceneDetector sceneDetector;
    private final FrameExtractor frameExtractor;
    private final AnalysisRequestExchangeService requestExchangeService;

    public VisualSceneAnalysisService(SceneDetector sceneDetector, FrameExtractor frameExtractor,
                                      AnalysisRequestExchangeService requestExchangeService) {
        this.sceneDetector = sceneDetector;
        this.frameExtractor = frameExtractor;
        this.requestExchangeService = requestExchangeService;
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
                List<Long> representativeTimestamps = representativeTimestamps(boundary);
                for (int i = 0; i < representativeTimestamps.size(); i++) {
                    FrameExtractor.ExtractedFrame frame = frames.get(frameIndex++);
                    requestExchangeService.saveVisualRequest(sourceFile, frame.timestampMs(), frame.imageFile());
                }
                scenes.add(new SceneSegment(boundary.startMs(), boundary.endMs(),
                        "Pending ChatGPT visual analysis", 0.0));
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
        return java.util.stream.Stream.of(first, middle, last).distinct().toList();
    }
}
