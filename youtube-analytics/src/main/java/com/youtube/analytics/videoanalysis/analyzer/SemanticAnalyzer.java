package com.youtube.analytics.videoanalysis.analyzer;

import com.youtube.analytics.videoanalysis.model.CandidateRole;

public interface SemanticAnalyzer {
    CandidateRole classifyRole(String storyIntent, String visualSummary, String spokenText);
}
