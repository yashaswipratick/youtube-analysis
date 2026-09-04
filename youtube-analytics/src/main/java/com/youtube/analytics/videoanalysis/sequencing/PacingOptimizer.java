package com.youtube.analytics.videoanalysis.sequencing;

import com.youtube.analytics.videoanalysis.model.CandidateRole;
import com.youtube.analytics.videoanalysis.model.ClipCandidate;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Service
public class PacingOptimizer {

    private static final Map<CandidateRole, Long> MAX_DURATION_MS = maxDurations();

    public List<ClipCandidate> optimize(List<ClipCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        return candidates.stream()
                .map(this::trimForRole)
                .toList();
    }

    private ClipCandidate trimForRole(ClipCandidate candidate) {
        long maxDurationMs = MAX_DURATION_MS.getOrDefault(candidate.role(), 8_000L);
        long durationMs = candidate.durationMs();
        if (durationMs <= maxDurationMs) {
            return candidate;
        }

        long trimmedEndMs = candidate.sourceStartMs() + maxDurationMs;
        return new ClipCandidate(
                candidate.sourceFileName(),
                candidate.sourceStartMs(),
                trimmedEndMs,
                candidate.role(),
                candidate.score(),
                candidate.spokenText(),
                candidate.visualSummary(),
                candidate.reasons());
    }

    private static Map<CandidateRole, Long> maxDurations() {
        EnumMap<CandidateRole, Long> durations = new EnumMap<>(CandidateRole.class);
        durations.put(CandidateRole.HOOK, 8_000L);
        durations.put(CandidateRole.SETUP, 12_000L);
        durations.put(CandidateRole.JOURNEY, 6_000L);
        durations.put(CandidateRole.EXPERIENCE, 8_000L);
        durations.put(CandidateRole.PAYOFF, 10_000L);
        durations.put(CandidateRole.VOICE_BRIDGE, 12_000L);
        durations.put(CandidateRole.B_ROLL, 5_000L);
        durations.put(CandidateRole.ENDING, 5_000L);
        durations.put(CandidateRole.UNKNOWN, 8_000L);
        return Map.copyOf(durations);
    }
}
