package com.youtube.analytics.videoanalysis.sequencing;

import com.youtube.analytics.videoanalysis.analyzer.SemanticAnalyzer;
import com.youtube.analytics.videoanalysis.model.AudioProfile;
import com.youtube.analytics.videoanalysis.model.CandidateRole;
import com.youtube.analytics.videoanalysis.model.ClipCandidate;
import com.youtube.analytics.videoanalysis.model.RawVideoClipAnalysis;
import com.youtube.analytics.videoanalysis.model.SceneSegment;
import com.youtube.analytics.videoanalysis.model.SpeechSegment;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Service
public class ClipCandidateScoringService {

    private static final double VISUAL_WEIGHT = 0.30;
    private static final double SPEECH_WEIGHT = 0.20;
    private static final double RELEVANCE_WEIGHT = 0.25;
    private static final double QUALITY_WEIGHT = 0.10;
    private static final double ROLE_FIT_WEIGHT = 0.15;

    private final SemanticAnalyzer semanticAnalyzer;
    private final StoryIntentMatcher storyIntentMatcher;

    public ClipCandidateScoringService(SemanticAnalyzer semanticAnalyzer) {
        this(semanticAnalyzer, new StoryIntentMatcher());
    }

    @Autowired
    public ClipCandidateScoringService(SemanticAnalyzer semanticAnalyzer, StoryIntentMatcher storyIntentMatcher) {
        this.semanticAnalyzer = semanticAnalyzer;
        this.storyIntentMatcher = storyIntentMatcher;
    }

    public List<ClipCandidate> score(String storyIntent, RawVideoClipAnalysis clip) {
        List<ClipCandidate> candidates = new ArrayList<>();
        for (SceneSegment scene : clip.scenes() == null ? List.<SceneSegment>of() : clip.scenes()) {
            if (scene.endMs() <= scene.startMs()) continue;

            String spokenText = speechOverlapping(scene.startMs(), scene.endMs(), clip.speechSegments());
            CandidateRole role = semanticAnalyzer.classifyRole(storyIntent, scene.visualSummary(), spokenText);
            double speechScore = speechScore(scene.startMs(), scene.endMs(), clip.speechSegments(), clip.audio());
            double relevance = storyIntentMatcher.relevance(storyIntent, scene.visualSummary(), spokenText);
            double roleFit = roleFit(role, storyIntent, scene.visualSummary(), spokenText);
            double score = round((clamp(scene.visualScore()) * VISUAL_WEIGHT)
                    + (clamp(speechScore) * SPEECH_WEIGHT)
                    + (relevance * RELEVANCE_WEIGHT)
                    + (clamp(clip.visualQualityScore()) * QUALITY_WEIGHT)
                    + (roleFit * ROLE_FIT_WEIGHT));

            List<String> reasons = new ArrayList<>();
            if (scene.visualScore() >= 0.7) reasons.add("strong visual quality");
            if (speechScore >= 0.7) reasons.add("clear spoken-audio content");
            if (relevance >= 0.5) reasons.add("relevant to story intent");
            if (roleFit >= 0.7) reasons.add("strong fit for " + role.name().toLowerCase(Locale.ROOT) + " role");
            if (reasons.isEmpty()) reasons.add("candidate retained for structural coverage");

            candidates.add(new ClipCandidate(clip.sourceFileName(), scene.startMs(), scene.endMs(), role,
                    score, spokenText, scene.visualSummary(), List.copyOf(reasons)));
        }
        return candidates.stream()
                .sorted(Comparator.comparingDouble(ClipCandidate::score).reversed()
                        .thenComparingLong(ClipCandidate::sourceStartMs))
                .toList();
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
        if (segments == null || segments.isEmpty()) return audio == null ? 0.0 : clamp(audio.speechClarityScore()) * 0.5;
        return segments.stream()
                .filter(s -> s.endMs() > startMs && s.startMs() < endMs)
                .mapToDouble(SpeechSegment::clarityScore)
                .map(this::clamp)
                .average()
                .orElse(audio == null ? 0.0 : clamp(audio.speechClarityScore()));
    }

    private double roleFit(CandidateRole role, String storyIntent, String visualSummary, String spokenText) {
        String evidence = ((visualSummary == null ? "" : visualSummary) + " " + (spokenText == null ? "" : spokenText))
                .toLowerCase(Locale.ROOT);
        double fit = switch (role) {
            case HOOK -> phrasePresence(evidence, "welcome", "today", "let's go", "let us go", "journey begins") ? 1.0 : 0.45;
            case SETUP -> phrasePresence(evidence, "because", "plan", "heading", "going to", "on the way") ? 1.0 : 0.45;
            case JOURNEY -> phrasePresence(evidence, "drive", "driving", "road", "travel", "walking", "journey") ? 1.0 : 0.45;
            case EXPERIENCE -> phrasePresence(evidence, "food", "explore", "experience", "inside", "activity") ? 1.0 : 0.45;
            case PAYOFF -> phrasePresence(evidence, "finally", "arrived", "destination", "view", "sunset", "best part") ? 1.0 : 0.45;
            case VOICE_BRIDGE -> spokenText != null && !spokenText.isBlank() ? 1.0 : 0.25;
            case B_ROLL -> visualSummary != null && !visualSummary.isBlank() ? 1.0 : 0.25;
            case ENDING -> phrasePresence(evidence, "bye", "goodbye", "that's it", "that is it", "see you") ? 1.0 : 0.45;
            case UNKNOWN -> 0.20;
        };
        if (storyIntent == null || storyIntent.isBlank()) return fit;
        return Math.min(1.0, fit + storyIntentMatcher.relevance(storyIntent, visualSummary, spokenText) * 0.20);
    }

    private boolean phrasePresence(String evidence, String... terms) {
        for (String term : terms) if (evidence.contains(term)) return true;
        return false;
    }

    private double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
