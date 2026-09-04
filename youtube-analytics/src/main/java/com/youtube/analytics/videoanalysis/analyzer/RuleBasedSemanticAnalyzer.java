package com.youtube.analytics.videoanalysis.analyzer;

import com.youtube.analytics.videoanalysis.model.CandidateRole;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public class RuleBasedSemanticAnalyzer implements SemanticAnalyzer {

    @Override
    public CandidateRole classifyRole(String storyIntent, String visualSummary, String spokenText) {
        String evidence = ((visualSummary == null ? "" : visualSummary) + " " + (spokenText == null ? "" : spokenText)).toLowerCase(Locale.ROOT);
        if (containsAny(evidence, "welcome", "today", "let's go", "let us go", "journey begins")) return CandidateRole.HOOK;
        if (containsAny(evidence, "because", "plan", "heading", "going to", "on the way")) return CandidateRole.SETUP;
        if (containsAny(evidence, "drive", "driving", "road", "travel", "walking", "journey")) return CandidateRole.JOURNEY;
        if (containsAny(evidence, "finally", "arrived", "destination", "view", "sunset", "best part")) return CandidateRole.PAYOFF;
        if (containsAny(evidence, "food", "explore", "experience", "inside", "activity")) return CandidateRole.EXPERIENCE;
        if (spokenText != null && !spokenText.isBlank()) return CandidateRole.VOICE_BRIDGE;
        if (visualSummary != null && !visualSummary.isBlank()) return CandidateRole.B_ROLL;
        return CandidateRole.UNKNOWN;
    }

    private boolean containsAny(String text, String... terms) {
        for (String term : terms) if (text.contains(term)) return true;
        return false;
    }
}
