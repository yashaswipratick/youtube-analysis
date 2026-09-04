package com.youtube.analytics.videoanalysis.frame;

import java.nio.file.Path;
import java.util.List;

public interface FrameExtractor {
    List<ExtractedFrame> extract(Path sourceFile, List<Long> timestampsMs);

    record ExtractedFrame(long timestampMs, Path imageFile) {
    }
}
