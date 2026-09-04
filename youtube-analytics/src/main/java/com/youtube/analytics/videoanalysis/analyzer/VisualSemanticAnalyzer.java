package com.youtube.analytics.videoanalysis.analyzer;

import com.youtube.analytics.videoanalysis.model.VisualObservation;

import java.nio.file.Path;

public interface VisualSemanticAnalyzer {
    VisualObservation analyze(Path imageFile);
}
