package com.youtube.analytics.videoanalysis.sequencing;

import com.youtube.analytics.videoanalysis.analyzer.SemanticAnalyzer;
import com.youtube.analytics.videoanalysis.model.AudioProfile;
import com.youtube.analytics.videoanalysis.model.CandidateRole;
import com.youtube.analytics.videoanalysis.model.ClipCandidate;
import com.youtube.analytics.videoanalysis.model.RawVideoClipAnalysis;
import com.youtube.analytics.videoanalysis.model.SceneSegment;
import com.youtube.analytics.videoanalysis.model.SpeechSegment;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Service
public class ClipCandidateScoringService {

    private final SemanticAnalyzer semanticAnalyzer;

    public ClipCandidateScoringService(SemanticAnalyzer semanticAnalyzer) {
        this.semanticAnalyzer = semanticAnalyzer;
    }

    public List<ClipCandidate> score(String storyIntent, RawVideoClipAnalysis clip) {
        List<ClipCandidate> candidates = new ArrayList<>();
        for (SceneSegment scene : clip.scenes() == null ? List.<SceneSegment>of() : clip.scenes()) {
            if (scene.endMs() <= scene.startMs()) continue;
            String spokenText = speechOverlapping(scene.startMs(), scene.endMs(), clip.speechSegments());
            CandidateRole role = semanticAnalyzer.classifyRole(storyIntent, scene.visualSummary(), spokenText);
            double speechScore = speechScore(scene.startMs(), scene.endMs(), clip.speechSegments(), clip.audio());
            double relevance = keywordRelevance(storyIntent, scene.visualSummary(), spokenText);
            double score = round((scene.visualScore() * 0.45) + (speechScore * 0.25)
                    + (relevance * 0.20) + (clip.visualQualityScore() * 0.10));
            List<String> reasons = new ArrayList<>();
            if (scene.visualScore() >= 0.7) reasons.add("strong visual quality");
            if (speechScore >= 0.7) reasons.add("clear spoken-audio content");
            if (relevance >= 0.5) reasons.add("relevant to story intent");
            if (reasons.isEmpty()) reasons.add("candidate retained for structural coverage");
            candidates.add(new ClipCandidate(clip.sourceFileName(), scene.startMs(), scene.endMs(), role,
                    score, spokenText, scene.visualSummary(), List.copyOf(reasons)));
        }
        return candidates.stream().sorted(Comparator.comparingDouble(ClipCandidate::score).reversed()).toList();
    }

    private String speechOverlapping(long startMs, long endMs, List<SpeechSegment> segments) {
        if (segments == null) return "";
        return segments.stream()
                .filter(s -> s.endMs() > startMs && s.startMs() < endMs)
                .map(SpeechSegment::text)
                .filter(t -> t != null && !t.isBlank())
                .reduce("", (a, b) -> a.isBlank() ? b : a + " " + b);
    }

    private double speechScore(long startMs, long endMs, List<SpeechSegment> segments, AudioProfile audio) {
        if (segments == null || segments.isEmpty()) return audio == null ? 0.0 : audio.speechClarityScore() * 0.5;
        return segments.stream()
                .filter(s -> s.endMs() > startMs && s.startMs() < endMs)
                .mapToDouble(SpeechSegment::clarityScore)
                .average()
                .orElse(audio == null ? 0.0 : audio.speechClarityScore());
    }

    private double keywordRelevance(String storyIntent, String visualSummary, String spokenText) {
        if (storyIntent == null || storyIntent.isBlank()) return 0.0;
        String evidence = ((visualSummary == null ? "" : visualSummary) + " " + (spokenText == null ? "" : spokenText)).toLowerCase(Locale.ROOT);
        String[] terms = storyIntent.toLowerCase(Locale.ROOT).split("\\W+");
        long useful = java.util.Arrays.stream(terms).filter(t -> t.length() > 2 && evidence.contains(t)).count();
        long total = java.util.Arrays.stream(terms).filter(t -> t.length() > 2).count();
        return total == 0 ? 0.0 : Math.min(1.0, (double) useful / total);
    }

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
