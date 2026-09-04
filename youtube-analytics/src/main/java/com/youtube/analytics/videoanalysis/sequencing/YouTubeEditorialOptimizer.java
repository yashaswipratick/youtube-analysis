package com.youtube.analytics.videoanalysis.sequencing;

import com.youtube.analytics.videoanalysis.model.CandidateRole;
import com.youtube.analytics.videoanalysis.model.ClipCandidate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class YouTubeEditorialOptimizer {

    public List<ClipCandidate> optimize(List<ClipCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) return List.of();
        List<ClipCandidate> ordered = new ArrayList<>(candidates);
        moveBestRoleToFront(ordered, CandidateRole.HOOK);
        moveBestRoleToEnd(ordered, CandidateRole.ENDING);
        return List.copyOf(ordered);
    }

    private void moveBestRoleToFront(List<ClipCandidate> candidates, CandidateRole role) {
        candidates.stream().filter(c -> c.role() == role).max(Comparator.comparingDouble(ClipCandidate::score))
                .ifPresent(best -> { candidates.remove(best); candidates.add(0, best); });
    }

    private void moveBestRoleToEnd(List<ClipCandidate> candidates, CandidateRole role) {
        candidates.stream().filter(c -> c.role() == role).max(Comparator.comparingDouble(ClipCandidate::score))
                .ifPresent(best -> { candidates.remove(best); candidates.add(best); });
    }
}
