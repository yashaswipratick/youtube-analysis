package com.youtube.analytics.videoanalysis.model;

import java.util.List;

public record ClipCandidate(
        String sourceFileName,
        long sourceStartMs,
        long sourceEndMs,
        CandidateRole role,
        double score,
        String spokenText,
        String visualSummary,
        List<String> reasons) {

    public long durationMs() {
        return sourceEndMs - sourceStartMs;
    }
}
