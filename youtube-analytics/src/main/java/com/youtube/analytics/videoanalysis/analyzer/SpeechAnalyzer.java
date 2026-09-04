package com.youtube.analytics.videoanalysis.analyzer;

import com.youtube.analytics.videoanalysis.model.SpeechSegment;

import java.nio.file.Path;
import java.util.List;

public interface SpeechAnalyzer {
    List<SpeechSegment> transcribe(Path sourceFile);
}
