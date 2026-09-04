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
import com.youtube.analytics.videoanalysis.sequencing.NarrativeRepairOptimizer;
import com.youtube.analytics.videoanalysis.sequencing.SequenceOptimizer;
import com.youtube.analytics.videoanalysis.timeline.TimelineOptimizer;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class RawVideoAnalysisService {

    private final MediaApprovalService approvalService;
    private final MediaDiscoveryService discoveryService;
    private final boolean approvalRequired;
    private final ClipCandidateScoringService scoringService;
    private final SequenceOptimizer sequenceOptimizer;
    private final GlobalCandidateOptimizer globalCandidateOptimizer;
    private final SpeechAwareClipOptimizer speechAwareClipOptimizer;
    private final DurationAwareCandidateSelector durationAwareCandidateSelector;
    private final PacingOptimizer pacingOptimizer;
    private final NarrativeRepairOptimizer narrativeRepairOptimizer;
    private final TimelineOptimizer timelineOptimizer;
    private final RawVideoFileAnalyzer rawVideoFileAnalyzer;

    public RawVideoAnalysisService(MediaApprovalService approvalService,
                                   MediaDiscoveryService discoveryService,
                                   LocalMediaInputProperties inputProperties,
                                   ClipCandidateScoringService scoringService,
                                   SequenceOptimizer sequenceOptimizer,
                                   GlobalCandidateOptimizer globalCandidateOptimizer,
                                   SpeechAwareClipOptimizer speechAwareClipOptimizer,
                                   DurationAwareCandidateSelector durationAwareCandidateSelector,
                                   PacingOptimizer pacingOptimizer,
                                   NarrativeRepairOptimizer narrativeRepairOptimizer,
                                   TimelineOptimizer timelineOptimizer,
                                   RawVideoFileAnalyzer rawVideoFileAnalyzer) {
        this.approvalService = approvalService;
        this.discoveryService = discoveryService;
        this.approvalRequired = inputProperties.approvalRequired();
        this.scoringService = scoringService;
        this.sequenceOptimizer = sequenceOptimizer;
        this.globalCandidateOptimizer = globalCandidateOptimizer;
        this.speechAwareClipOptimizer = speechAwareClipOptimizer;
        this.durationAwareCandidateSelector = durationAwareCandidateSelector;
        this.pacingOptimizer = pacingOptimizer;
        this.narrativeRepairOptimizer = narrativeRepairOptimizer;
        this.timelineOptimizer = timelineOptimizer;
        this.rawVideoFileAnalyzer = rawVideoFileAnalyzer;
    }

    public EditPlan buildEditPlan(RawVideoAnalysisRequest request) {
        List<RawVideoClipAnalysis> approvedVideos = approvedVideoAnalyses();
        if (approvedVideos.isEmpty()) {
            throw new IllegalStateException("No approved videos are available in the configured video-analysis.input-directory");
        }

        List<ClipCandidate> candidates = new ArrayList<>();
        approvedVideos.forEach(clip -> candidates.addAll(scoringService.score(request.storyIntent(), clip)));
        List<ClipCandidate> orderedCandidates = sequenceOptimizer.optimize(candidates);
        List<ClipCandidate> globallyOptimizedCandidates = globalCandidateOptimizer.optimize(
                request.storyIntent(), orderedCandidates, candidates);
        List<ClipCandidate> repairedCandidates = narrativeRepairOptimizer.repair(
                request.storyIntent(), globallyOptimizedCandidates, candidates);
        List<ClipCandidate> speechSafeCandidates = speechAwareClipOptimizer.optimize(repairedCandidates, approvedVideos);
        List<ClipCandidate> selectedCandidates = durationAwareCandidateSelector.select(

                speechSafeCandidates, request.targetDurationMinutes());
        List<ClipCandidate> pacedCandidates = pacingOptimizer.optimize(selectedCandidates);
        return timelineOptimizer.buildPlan(request.projectId(), request.storyIntent(),
                pacedCandidates, request.targetDurationMinutes());
    }

    private List<RawVideoClipAnalysis> approvedVideoAnalyses() {
        return discoveryService.discover().stream()
                .filter(media -> media.type() == MediaFileType.VIDEO)
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
