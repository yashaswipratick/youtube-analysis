package com.youtube.analytics.videoanalysis.service;

import com.youtube.analytics.videoanalysis.analyzer.AudioAnalyzer;
import com.youtube.analytics.videoanalysis.analyzer.SpeechAnalyzer;
import com.youtube.analytics.videoanalysis.analyzer.VideoAnalyzer;
import com.youtube.analytics.videoanalysis.ingestion.MediaApprovalService;
import com.youtube.analytics.videoanalysis.ingestion.MediaDiscoveryService;
import com.youtube.analytics.videoanalysis.ingestion.MediaFileType;
import com.youtube.analytics.videoanalysis.model.AudioProfile;
import com.youtube.analytics.videoanalysis.model.RawVideoClipAnalysis;
import com.youtube.analytics.videoanalysis.model.SpeechSegment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;

@Service
public class RawVideoFileAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(RawVideoFileAnalyzer.class);

    private final MediaApprovalService approvalService;
    private final VideoAnalyzer videoAnalyzer;
    private final AudioAnalyzer audioAnalyzer;
    private final SpeechAnalyzer speechAnalyzer;
    private final AnalysisCacheService analysisCacheService;
    private final AnalysisHandoffManifestService analysisHandoffManifestService;
    private final MediaDiscoveryService mediaDiscoveryService;

    @Autowired
    public RawVideoFileAnalyzer(MediaApprovalService approvalService,
                                VideoAnalyzer videoAnalyzer,
                                AudioAnalyzer audioAnalyzer,
                                SpeechAnalyzer speechAnalyzer,
                                AnalysisCacheService analysisCacheService,
                                AnalysisHandoffManifestService analysisHandoffManifestService,
                                MediaDiscoveryService mediaDiscoveryService) {
        this.approvalService = approvalService;
        this.videoAnalyzer = videoAnalyzer;
        this.audioAnalyzer = audioAnalyzer;
        this.speechAnalyzer = speechAnalyzer;
        this.analysisCacheService = analysisCacheService;
        this.analysisHandoffManifestService = analysisHandoffManifestService;
        this.mediaDiscoveryService = mediaDiscoveryService;
    }

    public List<RawVideoClipAnalysis> analyzeAll() {
        return mediaDiscoveryService.discover().stream()
                .filter(file -> file.type() == MediaFileType.VIDEO)
                .map(file -> analyze(file.relativePath()))
                .toList();
    }


    public RawVideoClipAnalysis analyze(String relativePath) {
        long startedAt = System.currentTimeMillis();
        log.info("[ANALYSIS] Starting: {}", relativePath);
        Path sourceFile = approvalService.getPath(relativePath);
        RawVideoClipAnalysis cached = analysisCacheService.load(sourceFile);
        if (cached != null) {
            analysisHandoffManifestService.save(sourceFile);
            log.info("[ANALYSIS] Cache hit: {}. No media extraction required.", relativePath);
            return cached;
        }
        log.info("[ANALYSIS] Cache miss: {}", relativePath);

        log.info("[VIDEO] Starting visual analysis: {}", relativePath);
        RawVideoClipAnalysis visualAnalysis = videoAnalyzer.analyze(sourceFile);
        log.info("[VIDEO] Visual analysis finished: {}", relativePath);

        log.info("[AUDIO] Starting audio analysis: {}", relativePath);
        AudioProfile audio = audioAnalyzer.analyze(sourceFile);
        log.info("[AUDIO] Audio analysis finished: {}", relativePath);

        log.info("[SPEECH] Starting speech extraction: {}", relativePath);
        List<SpeechSegment> speech = speechAnalyzer.transcribe(sourceFile);
        log.info("[SPEECH] Speech extraction finished: {}", relativePath);
        RawVideoClipAnalysis preparedAnalysis = new RawVideoClipAnalysis(
                sourceFile.getFileName().toString(),
                visualAnalysis.durationMs(),
                visualAnalysis.scenes(),
                speech,
                audio,
                visualAnalysis.visualQualityScore());
        // Step 1: persist the deterministic cache placeholder. Bridge/ChatGPT will later replace this
        // same hash file with the AI-enriched result after reading the persisted analysis JSON directly.
        analysisCacheService.savePending(sourceFile, preparedAnalysis);
        analysisHandoffManifestService.save(sourceFile);
        log.info("[ANALYSIS] Pending result cache created: {}", relativePath);
        log.info("[ANALYSIS] Handoff manifest created in analysis-manifest/: {}", relativePath);
        log.info("[ANALYSIS] AI request exchange ready as a persisted file in analysis/: {}", relativePath);
        log.info("[ANALYSIS] Completed local preparation: {} ({} ms)", relativePath, System.currentTimeMillis() - startedAt);
        return preparedAnalysis;
    }
}
