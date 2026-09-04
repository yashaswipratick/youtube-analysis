package com.youtube.analytics.videoanalysis.sequencing;

import com.youtube.analytics.videoanalysis.model.CandidateRole;
import com.youtube.analytics.videoanalysis.model.ClipCandidate;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Service
public class SequenceOptimizer {

    private static final Map<CandidateRole, Integer> ROLE_ORDER = roleOrder();

    public List<ClipCandidate> optimize(List<ClipCandidate> candidates) {
        return candidates.stream()
                .sorted(Comparator.comparingInt((ClipCandidate c) -> ROLE_ORDER.getOrDefault(c.role(), 99))
                        .thenComparing(Comparator.comparingDouble(ClipCandidate::score).reversed()))
                .toList();
    }

    private static Map<CandidateRole, Integer> roleOrder() {
        Map<CandidateRole, Integer> order = new EnumMap<>(CandidateRole.class);
        order.put(CandidateRole.HOOK, 1);
        order.put(CandidateRole.SETUP, 2);
        order.put(CandidateRole.JOURNEY, 3);
        order.put(CandidateRole.VOICE_BRIDGE, 4);
        order.put(CandidateRole.EXPERIENCE, 5);
        order.put(CandidateRole.PAYOFF, 6);
        order.put(CandidateRole.B_ROLL, 7);
        order.put(CandidateRole.ENDING, 8);
        order.put(CandidateRole.UNKNOWN, 9);
        return Map.copyOf(order);
    }
}
