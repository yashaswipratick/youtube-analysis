package com.youtube.analytics.videoanalysis.service;

import com.youtube.analytics.videoanalysis.ingestion.LocalMediaFile;
import com.youtube.analytics.videoanalysis.ingestion.LocalMediaInputProperties;
import com.youtube.analytics.videoanalysis.ingestion.MediaApprovalService;
import com.youtube.analytics.videoanalysis.ingestion.MediaDiscoveryService;
import com.youtube.analytics.videoanalysis.ingestion.MediaFileType;
import com.youtube.analytics.videoanalysis.model.AudioProfile;
import com.youtube.analytics.videoanalysis.model.EditPlan;
import com.youtube.analytics.videoanalysis.model.RawVideoAnalysisRequest;
import com.youtube.analytics.videoanalysis.model.RawVideoClipAnalysis;
import com.youtube.analytics.videoanalysis.model.SceneSegment;
import com.youtube.analytics.videoanalysis.sequencing.ClipCandidateScoringService;
import com.youtube.analytics.videoanalysis.sequencing.DurationAwareCandidateSelector;
import com.youtube.analytics.videoanalysis.sequencing.PacingOptimizer;
import com.youtube.analytics.videoanalysis.sequencing.NarrativeRepairOptimizer;
import com.youtube.analytics.videoanalysis.sequencing.SequenceOptimizer;
import com.youtube.analytics.videoanalysis.timeline.TimelineOptimizer;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RawVideoAnalysisServiceTest {

    @Test
    void buildsEditPlanFromApprovedVideosDiscoveredInConfiguredDirectory() {
        MediaApprovalService approvalService = mock(MediaApprovalService.class);
        MediaDiscoveryService discoveryService = mock(MediaDiscoveryService.class);
        ClipCandidateScoringService scoringService = mock(ClipCandidateScoringService.class);
        SequenceOptimizer sequenceOptimizer = mock(SequenceOptimizer.class);
        DurationAwareCandidateSelector durationSelector = mock(DurationAwareCandidateSelector.class);
        PacingOptimizer pacingOptimizer = mock(PacingOptimizer.class);
        NarrativeRepairOptimizer narrativeRepairOptimizer = mock(NarrativeRepairOptimizer.class);
        TimelineOptimizer timelineOptimizer = mock(TimelineOptimizer.class);
        RawVideoFileAnalyzer fileAnalyzer = mock(RawVideoFileAnalyzer.class);

        LocalMediaFile approvedVideo = new LocalMediaFile(
                "clip.mp4", "nested/clip.mp4", MediaFileType.VIDEO, 100L, Instant.now());
        LocalMediaFile unapprovedVideo = new LocalMediaFile(
                "other.mp4", "other.mp4", MediaFileType.VIDEO, 100L, Instant.now());
        LocalMediaFile image = new LocalMediaFile(
                "cover.jpg", "cover.jpg", MediaFileType.IMAGE, 100L, Instant.now());
        RawVideoClipAnalysis analysis = clip("clip.mp4", 5_000);
        EditPlan expected = new EditPlan("bangalore-trip", "weekend getaway from Bangalore", List.of(), 0, List.of());

        when(discoveryService.discover()).thenReturn(List.of(approvedVideo, unapprovedVideo, image));
        when(approvalService.isApproved("nested/clip.mp4")).thenReturn(true);
        when(approvalService.isApproved("other.mp4")).thenReturn(false);
        when(approvalService.getApprovedPath("nested/clip.mp4")).thenReturn(java.nio.file.Path.of("/tmp/nested/clip.mp4"));
        when(fileAnalyzer.analyze("nested/clip.mp4")).thenReturn(analysis);
        when(scoringService.score("weekend getaway from Bangalore", analysis)).thenReturn(List.of());
        when(sequenceOptimizer.optimize(List.of())).thenReturn(List.of());
        when(narrativeRepairOptimizer.repair("weekend getaway from Bangalore", List.of(), List.of())).thenReturn(List.of());
        when(durationSelector.select(List.of(), 8L)).thenReturn(List.of());
        when(pacingOptimizer.optimize(List.of())).thenReturn(List.of());
        when(timelineOptimizer.buildPlan("bangalore-trip", "weekend getaway from Bangalore", List.of(), 8L))
                .thenReturn(expected);

        RawVideoAnalysisService service = new RawVideoAnalysisService(
                approvalService, discoveryService, new LocalMediaInputProperties("/tmp", true, "renders"),
                scoringService, sequenceOptimizer, durationSelector, pacingOptimizer, narrativeRepairOptimizer, timelineOptimizer, fileAnalyzer);

        EditPlan result = service.buildEditPlan(
                new RawVideoAnalysisRequest("bangalore-trip", "weekend getaway from Bangalore", 8L));

        assertEquals(expected, result);
        verify(fileAnalyzer).analyze("nested/clip.mp4");
    }

    @Test
    void refusesToBuildPlanWhenNoApprovedVideosExist() {
        MediaApprovalService approvalService = mock(MediaApprovalService.class);
        MediaDiscoveryService discoveryService = mock(MediaDiscoveryService.class);
        when(discoveryService.discover()).thenReturn(List.of());

        RawVideoAnalysisService service = new RawVideoAnalysisService(
                approvalService,
                discoveryService,
                new LocalMediaInputProperties("/tmp", true, "renders"),
                mock(ClipCandidateScoringService.class),
                mock(SequenceOptimizer.class),
                mock(DurationAwareCandidateSelector.class),
                mock(PacingOptimizer.class),
                mock(NarrativeRepairOptimizer.class),
                mock(TimelineOptimizer.class),
                mock(RawVideoFileAnalyzer.class));

        assertThrows(IllegalStateException.class, () -> service.buildEditPlan(
                new RawVideoAnalysisRequest("project", "story", 8L)));
    }

    @Test
    void processesDiscoveredVideosDirectlyWhenApprovalIsDisabled() {
        MediaApprovalService approvalService = mock(MediaApprovalService.class);
        MediaDiscoveryService discoveryService = mock(MediaDiscoveryService.class);
        ClipCandidateScoringService scoringService = mock(ClipCandidateScoringService.class);
        SequenceOptimizer sequenceOptimizer = mock(SequenceOptimizer.class);
        DurationAwareCandidateSelector durationSelector = mock(DurationAwareCandidateSelector.class);
        PacingOptimizer pacingOptimizer = mock(PacingOptimizer.class);
        NarrativeRepairOptimizer narrativeRepairOptimizer = mock(NarrativeRepairOptimizer.class);
        TimelineOptimizer timelineOptimizer = mock(TimelineOptimizer.class);
        RawVideoFileAnalyzer fileAnalyzer = mock(RawVideoFileAnalyzer.class);

        LocalMediaFile video = new LocalMediaFile("clip.mp4", "clip.mp4", MediaFileType.VIDEO, 100L, Instant.now());
        RawVideoClipAnalysis analysis = clip("clip.mp4", 5_000);
        EditPlan expected = new EditPlan("project", "story", List.of(), 0, List.of());

        when(discoveryService.discover()).thenReturn(List.of(video));
        when(approvalService.getPath("clip.mp4")).thenReturn(java.nio.file.Path.of("/tmp/clip.mp4"));
        when(fileAnalyzer.analyze("clip.mp4")).thenReturn(analysis);
        when(scoringService.score("story", analysis)).thenReturn(List.of());
        when(sequenceOptimizer.optimize(List.of())).thenReturn(List.of());
        when(narrativeRepairOptimizer.repair("story", List.of(), List.of())).thenReturn(List.of());
        when(durationSelector.select(List.of(), 8L)).thenReturn(List.of());
        when(pacingOptimizer.optimize(List.of())).thenReturn(List.of());
        when(timelineOptimizer.buildPlan("project", "story", List.of(), 8L)).thenReturn(expected);

        RawVideoAnalysisService service = new RawVideoAnalysisService(
                approvalService, discoveryService, new LocalMediaInputProperties("/tmp", false, "renders"),
                scoringService, sequenceOptimizer, durationSelector, pacingOptimizer, narrativeRepairOptimizer, timelineOptimizer, fileAnalyzer);

        assertEquals(expected, service.buildEditPlan(new RawVideoAnalysisRequest("project", "story", 8L)));
        verify(fileAnalyzer).analyze("clip.mp4");
    }

    private RawVideoClipAnalysis clip(String name, long durationMs) {
        return new RawVideoClipAnalysis(
                name, durationMs,
                List.of(new SceneSegment(0, durationMs, "road", 0.8)),
                List.of(),
                new AudioProfile(false, 0.0, 0.0, false),
                0.8);
    }
}
