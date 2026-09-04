package com.youtube.analytics.videoanalysis.sequencing;

import com.youtube.analytics.videoanalysis.analyzer.RuleBasedSemanticAnalyzer;
import com.youtube.analytics.videoanalysis.model.AudioProfile;
import com.youtube.analytics.videoanalysis.model.CandidateRole;
import com.youtube.analytics.videoanalysis.model.ClipCandidate;
import com.youtube.analytics.videoanalysis.model.RawVideoClipAnalysis;
import com.youtube.analytics.videoanalysis.model.SceneSegment;
import com.youtube.analytics.videoanalysis.model.SpeechSegment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClipCandidateScoringServiceTest {

    private final ClipCandidateScoringService scoringService =
            new ClipCandidateScoringService(new RuleBasedSemanticAnalyzer());

    @Test
    void storyRelevantPayoffOutscoresGenericBroll() {
        RawVideoClipAnalysis analysis = analysis(
                new SceneSegment(0, 5000, "A generic road and trees", 0.90),
                new SceneSegment(5000, 10000, "Finally arrived at a mountain destination with a stunning view", 0.82));

        List<ClipCandidate> candidates = scoringService.score("weekend mountain destination", analysis);

        assertThat(candidates).hasSize(2);
        assertThat(candidates.get(0).role()).isEqualTo(CandidateRole.PAYOFF);
        assertThat(candidates.get(0).score()).isGreaterThan(candidates.get(1).score());
        assertThat(candidates.get(0).reasons()).contains("relevant to story intent");
    }

    @Test
    void spokenAndVisualEvidenceBothContributeToScore() {
        RawVideoClipAnalysis analysis = new RawVideoClipAnalysis(
                "drive.mp4", 10000,
                List.of(new SceneSegment(0, 10000, "Driving on a mountain road", 0.80)),
                List.of(new SpeechSegment(1000, 9000, "We are driving to the mountain destination", 0.95)),
                new AudioProfile(true, 0.95, 0.10, false),
                0.90);

        ClipCandidate candidate = scoringService.score("mountain drive", analysis).get(0);

        assertThat(candidate.role()).isEqualTo(CandidateRole.JOURNEY);
        assertThat(candidate.score()).isGreaterThan(0.75);
        assertThat(candidate.reasons()).contains("strong visual quality", "clear spoken-audio content",
                "relevant to story intent", "strong fit for journey role");
    }

    @Test
    void semanticConceptMatchRecognizesRelatedIntentWords() {
        RawVideoClipAnalysis analysis = new RawVideoClipAnalysis(
                "trip.mp4", 5000,
                List.of(new SceneSegment(0, 5000, "Driving through scenic hills at sunrise", 0.80)),
                List.of(), new AudioProfile(false, 0.0, 0.0, false), 0.80);

        ClipCandidate candidate = scoringService.score("weekend getaway mountain sunrise", analysis).get(0);

        assertThat(candidate.score()).isGreaterThan(0.45);
        assertThat(candidate.reasons()).contains("relevant to story intent");
    }

    @Test
    void temporallyStableVisualScoreInfluencesCandidateRanking() {
        RawVideoClipAnalysis analysis = new RawVideoClipAnalysis(
                "drive.mp4", 10000,
                List.of(
                        new SceneSegment(0, 5000, "Driving on a mountain road", 0.90),
                        new SceneSegment(5000, 10000, "Driving on a mountain road", 0.60)),
                List.of(new SpeechSegment(1000, 9000, "We are driving", 0.80)),
                new AudioProfile(true, 0.80, 0.10, false), 0.80);

        List<ClipCandidate> candidates = scoringService.score("mountain drive", analysis);

        assertThat(candidates).hasSize(2);
        assertThat(candidates.get(0).sourceStartMs()).isEqualTo(0L);
        assertThat(candidates.get(0).score()).isGreaterThan(candidates.get(1).score());
    }

    @Test
    void scoreIsBoundedAndTieBreaksBySourceStart() {
        RawVideoClipAnalysis analysis = new RawVideoClipAnalysis(
                "clip.mp4", 10000,
                List.of(
                        new SceneSegment(5000, 10000, "", 1.0),
                        new SceneSegment(0, 5000, "", 1.0)),
                List.of(), new AudioProfile(false, 0.0, 0.0, false), 1.0);

        List<ClipCandidate> candidates = scoringService.score("", analysis);

        assertThat(candidates).extracting(ClipCandidate::sourceStartMs).containsExactly(0L, 5000L);
        assertThat(candidates).allSatisfy(candidate -> assertThat(candidate.score()).isBetween(0.0, 1.0));
    }

    private RawVideoClipAnalysis analysis(SceneSegment... scenes) {
        return new RawVideoClipAnalysis("trip.mp4", 10000, List.of(scenes), List.of(),
                new AudioProfile(false, 0.0, 0.0, false), 0.90);
    }
}
