package com.youtube.analytics.videoanalysis.sequencing;

import com.youtube.analytics.videoanalysis.model.ClipCandidate;
import com.youtube.analytics.videoanalysis.model.RawVideoClipAnalysis;
import com.youtube.analytics.videoanalysis.model.SpeechSegment;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class SpeechAwareClipOptimizer {

    public List<ClipCandidate> optimize(List<ClipCandidate> candidates, List<RawVideoClipAnalysis> analyses) {
        if (candidates == null || candidates.isEmpty() || analyses == null || analyses.isEmpty()) {
            return candidates == null ? List.of() : List.copyOf(candidates);
        }
        Map<String, List<SpeechSegment>> speechByFile = new HashMap<>();
        analyses.forEach(analysis -> speechByFile.put(analysis.sourceFileName(),
                analysis.speechSegments() == null ? List.of() : analysis.speechSegments()));

        return candidates.stream()
                .map(candidate -> alignToSpeechBoundaries(candidate, speechByFile.getOrDefault(candidate.sourceFileName(), List.of())))
                .toList();
    }

    private ClipCandidate alignToSpeechBoundaries(ClipCandidate candidate, List<SpeechSegment> segments) {
        long start = candidate.sourceStartMs();
        long end = candidate.sourceEndMs();
        for (SpeechSegment speech : segments) {
            if (inside(speech, start)) start = speech.endMs();
            if (inside(speech, end)) end = speech.startMs();
        }
        if (end <= start) return candidate;
        if (start == candidate.sourceStartMs() && end == candidate.sourceEndMs()) return candidate;
        List<String> reasons = new ArrayList<>(candidate.reasons() == null ? List.of() : candidate.reasons());
        reasons.add("speech-safe cut boundaries");
        return new ClipCandidate(candidate.sourceFileName(), start, end, candidate.role(), candidate.score(),
                candidate.spokenText(), candidate.visualSummary(), List.copyOf(reasons));
    }

    private boolean inside(SpeechSegment segment, long timestamp) {
        return segment.startMs() < timestamp && timestamp < segment.endMs();
    }
}
