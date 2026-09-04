package com.youtube.analytics.videoanalysis.sequencing;

import com.youtube.analytics.videoanalysis.model.CandidateRole;
import com.youtube.analytics.videoanalysis.model.ClipCandidate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class YouTubeEditorialOptimizerTest {

    @Test
    void placesStrongHookFirstAndEndingLast() {
        ClipCandidate ending = candidate(CandidateRole.ENDING, 0.9, "end");
        ClipCandidate journey = candidate(CandidateRole.JOURNEY, 0.8, "drive");
        ClipCandidate hook = candidate(CandidateRole.HOOK, 0.7, "welcome");
        List<ClipCandidate> result = new YouTubeEditorialOptimizer().optimize(List.of(ending, journey, hook));
        assertThat(result).extracting(ClipCandidate::role)
                .containsExactly(CandidateRole.HOOK, CandidateRole.JOURNEY, CandidateRole.ENDING);
    }

    @Test
    void handlesEmptyAndNullCandidates() {
        YouTubeEditorialOptimizer optimizer = new YouTubeEditorialOptimizer();
        assertThat(optimizer.optimize(null)).isEmpty();
        assertThat(optimizer.optimize(List.of())).isEmpty();
    }

    private ClipCandidate candidate(CandidateRole role, double score, String text) {
        return new ClipCandidate("clip.mp4", 0, 1_000, role, score, text, "", List.of());
    }
}
