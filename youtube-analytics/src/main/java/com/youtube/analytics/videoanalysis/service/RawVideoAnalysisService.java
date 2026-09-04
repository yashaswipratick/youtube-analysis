package com.youtube.analytics.videoanalysis.service;

import com.youtube.analytics.videoanalysis.ingestion.LocalMediaFile;
import com.youtube.analytics.videoanalysis.ingestion.LocalMediaInputProperties;
import com.youtube.analytics.videoanalysis.ingestion.MediaApprovalService;
import com.youtube.analytics.videoanalysis.ingestion.MediaDiscoveryService;
import com.youtube.analytics.videoanalysis.ingestion.MediaFileType;
import com.youtube.analytics.videoanalysis.model.ClipCandidate;
import com.youtube.analytics.videoanalysis.model.EditPlan;
import com.youtube.analytics.videoanalysis.model.RawVideoAnalysisRequest;
import com.youtube.analytics.videoanalysis.model.RawVideoClipAnalysis;
import com.youtube.analytics.videoanalysis.sequencing.ClipCandidateScoringService;
import com.youtube.analytics.videoanalysis.sequencing.GlobalCandidateOptimizer;
import com.youtube.analytics.videoanalysis.sequencing.DurationAwareCandidateSelector;
import com.youtube.analytics.videoanalysis.sequencing.PacingOptimizer;
import com.youtube.analytics.videoanalysis.sequencing.SpeechAwareClipOptimizer;
import com.youtube.analytics.videoanalysis.sequencing.YouTubeEditorialOptimizer;
import com.youtube.analytics.videoanalysis.sequencing.NarrativeRepairOptimizer;
import com.youtube.analytics.videoanalysis.sequencing.SequenceOptimizer;
import com.youtube.analytics.videoanalysis.timeline.TimelineOptimizer;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Service
public class RawVideoAnalysisService {

    private final MediaApprovalService approvalService;
    private final MediaDiscoveryService discoveryService;
    private final LocalMediaInputProperties inputProperties;
    private final boolean approvalRequired;
    private final ClipCandidateScoringService scoringService;
    private final SequenceOptimizer sequenceOptimizer;
    private final GlobalCandidateOptimizer globalCandidateOptimizer;
    private final SpeechAwareClipOptimizer speechAwareClipOptimizer;
    private final YouTubeEditorialOptimizer youtubeEditorialOptimizer;
    private final DurationAwareCandidateSelector durationAwareCandidateSelector;
    private final PacingOptimizer pacingOptimizer;
    private final NarrativeRepairOptimizer narrativeRepairOptimizer;
    private final TimelineOptimizer timelineOptimizer;
    private final RawVideoFileAnalyzer rawVideoFileAnalyzer;
    private final EditingProgressReporter progressReporter;

    public RawVideoAnalysisService(MediaApprovalService approvalService,
                                   MediaDiscoveryService discoveryService,
                                   LocalMediaInputProperties inputProperties,
                                   ClipCandidateScoringService scoringService,
                                   SequenceOptimizer sequenceOptimizer,
                                   GlobalCandidateOptimizer globalCandidateOptimizer,
                                   SpeechAwareClipOptimizer speechAwareClipOptimizer,
                                   YouTubeEditorialOptimizer youtubeEditorialOptimizer,
                                   DurationAwareCandidateSelector durationAwareCandidateSelector,
                                   PacingOptimizer pacingOptimizer,
                                   NarrativeRepairOptimizer narrativeRepairOptimizer,
                                   TimelineOptimizer timelineOptimizer,
                                   RawVideoFileAnalyzer rawVideoFileAnalyzer,
                                   EditingProgressReporter progressReporter) {
        this.approvalService = approvalService;
        this.discoveryService = discoveryService;
        this.inputProperties = inputProperties;
        this.approvalRequired = inputProperties.approvalRequired();
        this.scoringService = scoringService;
        this.sequenceOptimizer = sequenceOptimizer;
        this.globalCandidateOptimizer = globalCandidateOptimizer;
        this.speechAwareClipOptimizer = speechAwareClipOptimizer;
        this.youtubeEditorialOptimizer = youtubeEditorialOptimizer;
        this.durationAwareCandidateSelector = durationAwareCandidateSelector;
        this.pacingOptimizer = pacingOptimizer;
        this.narrativeRepairOptimizer = narrativeRepairOptimizer;
        this.timelineOptimizer = timelineOptimizer;
        this.rawVideoFileAnalyzer = rawVideoFileAnalyzer;
        this.progressReporter = progressReporter;
    }

    public EditPlan buildEditPlan(RawVideoAnalysisRequest request) {
        return buildEditPlan(request, "sync");
    }

    public EditPlan buildEditPlan(RawVideoAnalysisRequest request, String jobId) {
        progressReporter.report(jobId, 0, "Starting intelligent video editing");
        progressReporter.report(jobId, 5, "Discovering videos and checking approval");
        List<LocalMediaFile> discoveredVideos = discoveryService.discover().stream()
                .filter(media -> media.type() == MediaFileType.VIDEO)
                .toList();
        List<LocalMediaFile> eligibleVideos = discoveredVideos.stream()
                .filter(this::isEligibleVideo)
                .toList();
        String approvalStatus = approvalRequired ? "approved" : "eligible (approval disabled)";
        progressReporter.report(jobId, 5, String.format(
                "Discovered %d videos; %d %s for processing",
                discoveredVideos.size(), eligibleVideos.size(), approvalStatus));
        if (eligibleVideos.isEmpty()) {
            throw new IllegalStateException("No eligible videos are available in the configured video-analysis.input-directory");
        }

        List<RawVideoClipAnalysis> approvedVideos = new ArrayList<>();
        List<ClipCandidate> candidates = new ArrayList<>();
        int totalVideos = eligibleVideos.size();
        int maxConcurrentVideos = Math.max(1, inputProperties.maxConcurrentVideos());
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(maxConcurrentVideos, totalVideos));
        try {
            List<Future<RawVideoClipAnalysis>> futures = new ArrayList<>();
            for (LocalMediaFile media : eligibleVideos) {
                futures.add(executor.submit(() -> approvedVideo(media.relativePath())));
            }
            for (int index = 0; index < futures.size(); index++) {
                try {
                    RawVideoClipAnalysis clip = futures.get(index).get();
                    approvedVideos.add(clip);
                    candidates.addAll(scoringService.score(request.storyIntent(), clip));
                    int completed = index + 1;
                    progressReporter.report(jobId, 10 + ((completed * 25) / totalVideos),
                            String.format("Analyzed %d/%d videos (%d workers)", completed, totalVideos, Math.min(maxConcurrentVideos, totalVideos)));
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Video analysis was interrupted", ex);
                } catch (ExecutionException ex) {
                    Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                    if (cause instanceof RuntimeException runtimeException) throw runtimeException;
                    throw new IllegalStateException("Video analysis failed", cause);
                }
            }
        } finally {
            executor.shutdownNow();
        }
        progressReporter.report(jobId, 35, "Video analysis completed; scoring edit candidates");
        List<ClipCandidate> orderedCandidates = sequenceOptimizer.optimize(candidates);
        progressReporter.report(jobId, 45, "Optimizing sequence and transition coherence");
        List<ClipCandidate> globallyOptimizedCandidates = globalCandidateOptimizer.optimize(
                request.storyIntent(), orderedCandidates, candidates);
        progressReporter.report(jobId, 55, "Applying global candidate optimization");
        List<ClipCandidate> repairedCandidates = narrativeRepairOptimizer.repair(
                request.storyIntent(), globallyOptimizedCandidates, candidates);
        progressReporter.report(jobId, 62, "Repairing narrative arc and story continuity");
        List<ClipCandidate> editorialCandidates = youtubeEditorialOptimizer.optimize(repairedCandidates);
        progressReporter.report(jobId, 68, "Applying YouTube editorial structure");
        List<ClipCandidate> speechSafeCandidates = speechAwareClipOptimizer.optimize(editorialCandidates, approvedVideos);
        progressReporter.report(jobId, 74, "Making speech-safe cuts and selecting target duration");
        List<ClipCandidate> selectedCandidates = durationAwareCandidateSelector.select(
                speechSafeCandidates, request.targetDurationMinutes());
        List<ClipCandidate> pacedCandidates = pacingOptimizer.optimize(selectedCandidates);
        progressReporter.report(jobId, 82, "Optimizing shot pacing and building final timeline");
        EditPlan plan = timelineOptimizer.buildPlan(request.projectId(), request.storyIntent(),
                pacedCandidates, request.targetDurationMinutes());
        progressReporter.report(jobId, 88, "Edit plan ready; rendering can now begin");
        return plan;
    }

    private List<RawVideoClipAnalysis> approvedVideoAnalyses(List<LocalMediaFile> discoveredVideos) {
        return discoveredVideos.stream()
                .filter(this::isEligibleVideo)
                .map(media -> approvedVideo(media.relativePath()))
                .toList();
    }

    private boolean isEligibleVideo(LocalMediaFile media) {
        return !approvalRequired || approvalService.isApproved(media.relativePath());
    }

    private RawVideoClipAnalysis approvedVideo(String relativePath) {
        if (approvalRequired) {
            approvalService.getApprovedPath(relativePath);
        }
        return rawVideoFileAnalyzer.analyze(relativePath);
    }
}
