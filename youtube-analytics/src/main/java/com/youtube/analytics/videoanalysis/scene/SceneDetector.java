package com.youtube.analytics.videoanalysis.scene;

import java.nio.file.Path;
import java.util.List;

public interface SceneDetector {
    List<SceneBoundary> detect(Path sourceFile, long durationMs);

    record SceneBoundary(long startMs, long endMs) {
        public SceneBoundary {
            if (startMs < 0 || endMs < startMs) throw new IllegalArgumentException("Invalid scene boundary");
        }
    }
}
