package com.youtube.analytics.videoanalysis.service;

import com.youtube.analytics.videoanalysis.model.ClipCandidate;
import com.youtube.analytics.videoanalysis.model.EditPlan;
import com.youtube.analytics.videoanalysis.model.RawVideoAnalysisRequest;
import com.youtube.analytics.videoanalysis.sequencing.ClipCandidateScoringService;
import com.youtube.analytics.videoanalysis.sequencing.DurationAwareCandidateSelector;
import com.youtube.analytics.videoanalysis.sequencing.SequenceOptimizer;
import com.youtube.analytics.videoanalysis.timeline.TimelineOptimizer;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class RawVideoAnalysisService {

    private final ClipCandidateScoringService scoringService;
    private final SequenceOptimizer sequenceOptimizer;
    private final DurationAwareCandidateSelector durationAwareCandidateSelector;
    private final TimelineOptimizer timelineOptimizer;

    public RawVideoAnalysisService(ClipCandidateScoringService scoringService,
                                   SequenceOptimizer sequenceOptimizer,
                                   DurationAwareCandidateSelector durationAwareCandidateSelector,
                                   TimelineOptimizer timelineOptimizer) {
        this.scoringService = scoringService;
        this.sequenceOptimizer = sequenceOptimizer;
        this.durationAwareCandidateSelector = durationAwareCandidateSelector;
        this.timelineOptimizer = timelineOptimizer;
    }

    public EditPlan buildEditPlan(RawVideoAnalysisRequest request) {
        List<ClipCandidate> candidates = new ArrayList<>();
        request.clips().forEach(clip -> candidates.addAll(scoringService.score(request.storyIntent(), clip)));
        List<ClipCandidate> orderedCandidates = sequenceOptimizer.optimize(candidates);
        List<ClipCandidate> selectedCandidates = durationAwareCandidateSelector.select(
                orderedCandidates, request.targetDurationMinutes());
        return timelineOptimizer.buildPlan(request.projectId(), request.storyIntent(),
                selectedCandidates, request.targetDurationMinutes());
    }
}
