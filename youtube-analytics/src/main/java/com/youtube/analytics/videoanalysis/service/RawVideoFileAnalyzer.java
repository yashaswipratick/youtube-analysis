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
    private final AnalysisCacheService analysisCacheService;

    public RawVideoFileAnalyzer(MediaApprovalService approvalService,
                                VideoAnalyzer videoAnalyzer,
                                AudioAnalyzer audioAnalyzer,
                                SpeechAnalyzer speechAnalyzer,
                                AnalysisCacheService analysisCacheService) {
        this.approvalService = approvalService;
        this.videoAnalyzer = videoAnalyzer;
        this.audioAnalyzer = audioAnalyzer;
        this.speechAnalyzer = speechAnalyzer;
        this.analysisCacheService = analysisCacheService;
    }

    public RawVideoClipAnalysis analyze(String relativePath) {
        Path sourceFile = approvalService.getPath(relativePath);
        RawVideoClipAnalysis cached = analysisCacheService.load(sourceFile);
        if (cached != null) {
            return cached;
        }
        RawVideoClipAnalysis visualAnalysis = videoAnalyzer.analyze(sourceFile);
        AudioProfile audio = audioAnalyzer.analyze(sourceFile);
        List<SpeechSegment> speech = speechAnalyzer.transcribe(sourceFile);
        RawVideoClipAnalysis analysis = new RawVideoClipAnalysis(
                sourceFile.getFileName().toString(),
                visualAnalysis.durationMs(),
                visualAnalysis.scenes(),
                speech,
                audio,
                visualAnalysis.visualQualityScore());
        analysisCacheService.save(sourceFile, analysis);
        return analysis;
    }
}
