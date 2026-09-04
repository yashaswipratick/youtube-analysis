package com.youtube.analytics.videoanalysis.service;

import com.youtube.analytics.videoanalysis.analyzer.AudioAnalyzer;
import com.youtube.analytics.videoanalysis.analyzer.SpeechAnalyzer;
import com.youtube.analytics.videoanalysis.analyzer.VideoAnalyzer;
import com.youtube.analytics.videoanalysis.ingestion.MediaApprovalService;
import com.youtube.analytics.videoanalysis.model.AudioProfile;
import com.youtube.analytics.videoanalysis.model.RawVideoClipAnalysis;
import com.youtube.analytics.videoanalysis.model.SpeechSegment;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;

@Service
public class RawVideoFileAnalyzer {

    private final MediaApprovalService approvalService;
    private final VideoAnalyzer videoAnalyzer;
    private final AudioAnalyzer audioAnalyzer;
    private final SpeechAnalyzer speechAnalyzer;

    public RawVideoFileAnalyzer(MediaApprovalService approvalService,
                                VideoAnalyzer videoAnalyzer,
                                AudioAnalyzer audioAnalyzer,
                                SpeechAnalyzer speechAnalyzer) {
        this.approvalService = approvalService;
        this.videoAnalyzer = videoAnalyzer;
        this.audioAnalyzer = audioAnalyzer;
        this.speechAnalyzer = speechAnalyzer;
    }

    public RawVideoClipAnalysis analyze(String relativePath) {
        Path sourceFile = approvalService.getApprovedPath(relativePath);
        RawVideoClipAnalysis visualAnalysis = videoAnalyzer.analyze(sourceFile);
        AudioProfile audio = audioAnalyzer.analyze(sourceFile);
        List<SpeechSegment> speech = speechAnalyzer.transcribe(sourceFile);
        return new RawVideoClipAnalysis(
                sourceFile.getFileName().toString(),
                visualAnalysis.durationMs(),
                visualAnalysis.scenes(),
                speech,
                audio,
                visualAnalysis.visualQualityScore());
    }
}
