package com.youtube.analytics.videoanalysis.analyzer;

import com.youtube.analytics.videoanalysis.model.AudioProfile;

import java.nio.file.Path;

public interface AudioAnalyzer {
    AudioProfile analyze(Path sourceFile);
}
