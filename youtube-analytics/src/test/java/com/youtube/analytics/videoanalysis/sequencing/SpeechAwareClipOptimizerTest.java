package com.youtube.analytics.videoanalysis.sequencing;

import com.youtube.analytics.videoanalysis.model.AudioProfile;
import com.youtube.analytics.videoanalysis.model.CandidateRole;
import com.youtube.analytics.videoanalysis.model.ClipCandidate;
import com.youtube.analytics.videoanalysis.model.RawVideoClipAnalysis;
import com.youtube.analytics.videoanalysis.model.SpeechSegment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SpeechAwareClipOptimizerTest {

    private final SpeechAwareClipOptimizer optimizer = new SpeechAwareClipOptimizer();

    @Test
    void movesBoundariesOutsideSpeechSegments() {
        ClipCandidate candidate = new ClipCandidate("clip.mp4", 2_000, 8_000, CandidateRole.JOURNEY,
                0.8, "speaking", "road", List.of());
        RawVideoClipAnalysis analysis = analysis(List.of(new SpeechSegment(1_000, 4_000, "hello", 0.9)));

        List<ClipCandidate> result = optimizer.optimize(List.of(candidate), List.of(analysis));

        assertThat(result.get(0).sourceStartMs()).isEqualTo(4_000);
        assertThat(result.get(0).sourceEndMs()).isEqualTo(8_000);
        assertThat(result.get(0).reasons()).contains("speech-safe cut boundaries");
    }

    @Test
    void leavesCandidateUnchangedWhenNoSpeechBoundaryIsCut() {
        ClipCandidate candidate = new ClipCandidate("clip.mp4", 0, 2_000, CandidateRole.B_ROLL,
                0.8, "", "road", List.of());
        RawVideoClipAnalysis analysis = analysis(List.of(new SpeechSegment(3_000, 4_000, "later", 0.9)));

        assertThat(optimizer.optimize(List.of(candidate), List.of(analysis))).containsExactly(candidate);
    }

    private RawVideoClipAnalysis analysis(List<SpeechSegment> speech) {
        return new RawVideoClipAnalysis("clip.mp4", 10_000, List.of(), speech,
                new AudioProfile(true, 0.9, 0.1, false), 0.8);
    }
}
