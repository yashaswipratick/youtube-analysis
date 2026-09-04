package com.youtube.analytics.videoanalysis.service;

import com.youtube.analytics.videoanalysis.analyzer.AudioAnalyzer;
import com.youtube.analytics.videoanalysis.analyzer.SpeechAnalyzer;
import com.youtube.analytics.videoanalysis.analyzer.VideoAnalyzer;
import com.youtube.analytics.videoanalysis.ingestion.MediaApprovalService;
import com.youtube.analytics.videoanalysis.model.AudioProfile;
import com.youtube.analytics.videoanalysis.model.RawVideoClipAnalysis;
import com.youtube.analytics.videoanalysis.model.SceneSegment;
import com.youtube.analytics.videoanalysis.model.SpeechSegment;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RawVideoFileAnalyzerTest {

    @Test
    void analyzesOnlyApprovedPathAndCombinesAnalyzerResults() {
        MediaApprovalService approvalService = mock(MediaApprovalService.class);
        VideoAnalyzer videoAnalyzer = mock(VideoAnalyzer.class);
        AudioAnalyzer audioAnalyzer = mock(AudioAnalyzer.class);
        SpeechAnalyzer speechAnalyzer = mock(SpeechAnalyzer.class);
        Path approvedPath = Path.of("/tmp/clip.mp4");
        RawVideoClipAnalysis visual = new RawVideoClipAnalysis(
                "clip.mp4", 5_000,
                List.of(new SceneSegment(0, 5_000, "road", 0.8)),
                List.of(), new AudioProfile(false, 0.0, 0.0, false), 0.8);
        List<SpeechSegment> speech = List.of(new SpeechSegment(0, 5_000, "We are driving", 0.9));
        AudioProfile audio = new AudioProfile(true, 0.9, 0.1, false);

        when(approvalService.getApprovedPath("clip.mp4")).thenReturn(approvedPath);
        when(videoAnalyzer.analyze(approvedPath)).thenReturn(visual);
        when(audioAnalyzer.analyze(approvedPath)).thenReturn(audio);
        when(speechAnalyzer.transcribe(approvedPath)).thenReturn(speech);

        RawVideoClipAnalysis result = new RawVideoFileAnalyzer(
                approvalService, videoAnalyzer, audioAnalyzer, speechAnalyzer).analyzeApproved("clip.mp4");

        assertEquals("clip.mp4", result.sourceFileName());
        assertEquals(5_000, result.durationMs());
        assertEquals(visual.scenes(), result.scenes());
        assertEquals(speech, result.speechSegments());
        assertEquals(audio, result.audio());
        verify(approvalService).getApprovedPath("clip.mp4");
        verify(videoAnalyzer).analyze(approvedPath);
        verify(audioAnalyzer).analyze(approvedPath);
        verify(speechAnalyzer).transcribe(approvedPath);
    }
}
