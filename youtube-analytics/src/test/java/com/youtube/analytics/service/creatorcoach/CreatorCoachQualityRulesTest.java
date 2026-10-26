package com.youtube.analytics.service.creatorcoach;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CreatorCoachQualityRulesTest {

    @Test
    void findsTwoLargestSmoothedDropsAndARecoverySpikeAfterOpeningTwoPercent() {
        List<Map<String, Object>> curve = new ArrayList<>();
        curve.add(point(0, 1.0));
        curve.add(point(1, 0.8));
        curve.add(point(3, 0.7));
        curve.add(point(20, 0.4));
        curve.add(point(40, 0.2));
        curve.add(point(60, 0.35));
        curve.add(point(80, 0.3));
        curve.add(point(100, 0.25));

        List<Map<String, Object>> moments = CreatorCoachQualityRules.audienceMoments(curve, 100);

        assertThat(moments).hasSize(3);
        assertThat(moments).extracting(item -> item.get("id")).containsExactly("drop1", "drop2", "spike1");
        assertThat(moments).allSatisfy(item -> assertThat((Integer) item.get("at_sec")).isGreaterThan(2));
        assertThat(moments.get(0)).containsEntry("type", "drop");
        assertThat(moments.get(2)).containsEntry("type", "spike");
        assertThat((Number) moments.get(0).get("lost_percent_points")).isNotNull();
        assertThat((Number) moments.get(2).get("rise_percent_points")).isNotNull();
    }

    @Test
    void quoteMustExistBeShortAndAlignToTranscriptCueWithinFiveSeconds() {
        String transcript = "[00:12] The misty mountains appeared beyond the road.";
        List<Map<String, Object>> cues = List.of(Map.of("start_sec", 12, "text", "The misty mountains appeared beyond the road."));

        assertThat(CreatorCoachQualityRules.isQuoteValid(transcript, cues, "The misty mountains appeared", 14, 60)).isTrue();
        assertThat(CreatorCoachQualityRules.isQuoteValid(transcript, cues, "A different mountain range", 14, 60)).isFalse();
        assertThat(CreatorCoachQualityRules.isQuoteValid(transcript, cues, "The misty mountains appeared", 18, 60)).isFalse();
        assertThat(CreatorCoachQualityRules.isQuoteValid(transcript, cues,
                "The misty mountains appeared beyond the road and made the morning feel magical and wonderfully calm to us all", 14, 60)).isFalse();
        assertThat(CreatorCoachQualityRules.isQuoteValid(transcript, cues, "The misty mountains appeared", 61, 60)).isFalse();
    }

    @Test
    void confidenceIsCappedWhenNoAudienceCurveIsAvailable() {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("title_check", new LinkedHashMap<>(Map.of("how_sure", "very")));
        report.put("fix_first", new LinkedHashMap<>(Map.of("how_sure", "very")));
        report.put("findings", new ArrayList<>(List.of(new LinkedHashMap<>(Map.of("how_sure", "very")))));
        report.put("recommendations", new ArrayList<>(List.of(new LinkedHashMap<>(Map.of("how_sure", "very")))));

        CreatorCoachQualityRules.capConfidence(report, false);

        assertThat(((Map<?, ?>) report.get("title_check")).get("how_sure")).isEqualTo("fairly");
        assertThat(((Map<?, ?>) report.get("fix_first")).get("how_sure")).isEqualTo("fairly");
        assertThat(((Map<?, ?>) ((List<?>) report.get("findings")).get(0)).get("how_sure")).isEqualTo("fairly");
        assertThat(((Map<?, ?>) ((List<?>) report.get("recommendations")).get(0)).get("how_sure")).isEqualTo("fairly");
    }

    @Test
    void confidenceRemainsUnchangedWhenCurveExists() {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("fix_first", new LinkedHashMap<>(Map.of("how_sure", "very")));

        CreatorCoachQualityRules.capConfidence(report, true);

        assertThat(((Map<?, ?>) report.get("fix_first")).get("how_sure")).isEqualTo("very");
    }

    private Map<String, Object> point(int at, double ratio) {
        return Map.of("at_sec", at, "audienceWatchRatio", ratio);
    }
}
