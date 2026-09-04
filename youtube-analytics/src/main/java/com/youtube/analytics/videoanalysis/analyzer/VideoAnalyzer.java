package com.youtube.analytics.videoanalysis.analyzer;

import com.youtube.analytics.videoanalysis.model.RawVideoClipAnalysis;

import java.nio.file.Path;

public interface VideoAnalyzer {
    RawVideoClipAnalysis analyze(Path sourceFile);
}
