package com.youtube.analytics.videoanalysis.sequencing;

import com.youtube.analytics.videoanalysis.model.CandidateRole;
import com.youtube.analytics.videoanalysis.model.ClipCandidate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class EditQualityEvaluator {
    public double score(List<ClipCandidate> candidates, long targetDurationMs) {
        if (candidates == null || candidates.isEmpty()) return 0.0;
        double score = 0.0;
        if (contains(candidates, CandidateRole.HOOK)) score += 0.20;
        if (contains(candidates, CandidateRole.PAYOFF)) score += 0.20;
        if (contains(candidates, CandidateRole.ENDING)) score += 0.15;
        if (containsDevelopment(candidates)) score += 0.15;
        score += 0.20 * averageCandidateScore(candidates);
        if (targetDurationMs <= 0) score += 0.10;
        else score += 0.10 * durationFit(candidates, targetDurationMs);
        return Math.max(0.0, Math.min(1.0, score));
    }

    private boolean contains(List<ClipCandidate> c, CandidateRole r) { return c.stream().anyMatch(x -> x.role() == r); }
    private boolean containsDevelopment(List<ClipCandidate> c) {
        return c.stream().anyMatch(x -> x.role() == CandidateRole.SETUP || x.role() == CandidateRole.JOURNEY
                || x.role() == CandidateRole.EXPERIENCE || x.role() == CandidateRole.VOICE_BRIDGE);
    }
    private double averageCandidateScore(List<ClipCandidate> c) { return c.stream().mapToDouble(ClipCandidate::score).average().orElse(0.0); }
    private double durationFit(List<ClipCandidate> c, long target) {
        long actual = c.stream().mapToLong(ClipCandidate::durationMs).sum();
        if (actual == 0) return 0;
        return Math.max(0.0, 1.0 - Math.abs(actual - target) / (double) target);
    }
}
