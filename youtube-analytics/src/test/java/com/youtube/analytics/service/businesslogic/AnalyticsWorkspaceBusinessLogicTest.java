package com.youtube.analytics.service.businesslogic;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AnalyticsWorkspaceBusinessLogicTest {
    private final AnalyticsWorkspaceBusinessLogic service = new AnalyticsWorkspaceBusinessLogic();

    @Test
    void publishingConsistencySeparatesShortsAndLongFormAndNormalizesByInclusiveDays() {
        List<Map<String, Object>> videos = List.of(
                video("short-1", "2026-10-05T10:00:00Z", "SHORT", 100),
                video("short-2", "2026-10-10T10:00:00Z", "SHORT", 200),
                video("long-1", "2026-10-12T10:00:00Z", "LONG_FORM", 1000),
                video("outside", "2026-10-20T10:00:00Z", "LONG_FORM", 9999));

        Map<String, Object> result = service.publishingConsistency(
                videos, LocalDate.parse("2026-10-05"), LocalDate.parse("2026-10-18"), "fixture sample");

        assertThat(result).containsEntry("shortVideosPublished", 2L)
                .containsEntry("longFormVideosPublished", 1L)
                .containsEntry("unclassifiedVideosPublished", 0L)
                .containsEntry("averageShortsPerWeek", 1.0)
                .containsEntry("averageLongFormPerWeek", 0.5);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> weeks = (List<Map<String, Object>>) result.get("weekly");
        assertThat(weeks).hasSize(2);
        assertThat(weeks.get(0)).containsEntry("shortVideos", 2L).containsEntry("longFormVideos", 0L);
        assertThat(weeks.get(1)).containsEntry("shortVideos", 0L).containsEntry("longFormVideos", 1L);
    }

    @Test
    void keywordSuggestionsKeepMeasuredSearchViewsSeparateFromCtrEstimates() {
        Map<String, Object> measured = Map.of("searchTerm", "coorg travel", "views", 42L);

        List<Map<String, Object>> result = service.keywordOpportunities(
                "coorg travel",
                List.of("coorg travel", "coorg travel guide", "coorg hidden places"),
                List.of(measured));

        assertThat(result).hasSize(2);
        Map<String, Object> guide = result.get(0);
        assertThat(guide).containsEntry("keyword", "coorg travel guide")
                .containsEntry("opportunityScoreType", "HEURISTIC_QUERY_FIT_ESTIMATE_NOT_CTR")
                .containsEntry("measuredVideoCtr", null);
        assertThat(guide.get("opportunityScoreEstimate")).isInstanceOf(Integer.class);
        assertThat((Integer) guide.get("opportunityScoreEstimate")).isBetween(0, 100);
        assertThat(guide.get("measuredSearchViews")).isEqualTo(42L);
        assertThat(String.valueOf(guide.get("ctrStatus"))).contains("does not expose CTR per search term");

        Map<String, Object> hiddenPlaces = result.get(1);
        assertThat(hiddenPlaces.get("measuredSearchViews")).isNull();
        assertThat(hiddenPlaces.get("measuredVideoCtr")).isNull();
    }

    @Test
    void missingPeriodRowsDoNotCorruptComparisonAverageOrFloatToTheTop() {
        Map<String, Object> withViews = new LinkedHashMap<>();
        withViews.put("videoId", "valid");
        withViews.put("views", 100L);
        withViews.put("likeCount", 5L);
        Map<String, Object> missing = new LinkedHashMap<>();
        missing.put("videoId", "no-period-row");
        missing.put("views", null);

        Map<String, Object> result = service.annotateComparison(List.of(missing, withViews), false);

        assertThat(result.get("averageViews")).isEqualTo(100.0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("rows");
        assertThat(rows.get(0).get("videoId")).isEqualTo("valid");
        assertThat(rows.get(0).get("viewsVsGroupAveragePercent")).isEqualTo(0.0);
        assertThat(rows.get(1).get("viewsVsGroupAveragePercent")).isNull();
    }

    @Test
    void publicChannelComparisonExplainsLimitationsInsteadOfInventingPrivateMetrics() {
        Map<String, Object> one = new LinkedHashMap<>();
        one.put("videoId", "one");
        one.put("title", "Public video one");
        one.put("views", 100L);
        one.put("likeCount", 10L);
        one.put("commentCount", 2L);
        Map<String, Object> two = new LinkedHashMap<>();
        two.put("videoId", "two");
        two.put("title", "Public video two");
        two.put("views", 50L);
        two.put("likeCount", 2L);
        two.put("commentCount", 1L);

        Map<String, Object> result = service.annotateComparison(List.of(one, two), true);

        assertThat(result).containsEntry("publicOnly", true).containsEntry("sampleSize", 2);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("rows");
        assertThat(rows.get(0)).containsEntry("videoId", "one").containsEntry("publicEngagementRatePercent", 12.0);
        @SuppressWarnings("unchecked")
        List<String> improvements = (List<String>) rows.get(0).get("improvementIdeas");
        assertThat(improvements).anyMatch(text -> text.contains("Public metrics cannot show impressions CTR"));
        assertThat(rows.get(0)).doesNotContainKey("impressionsClickThroughRate");
    }

    private Map<String, Object> video(String id, String publishedAt, String contentType, long views) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("videoId", id);
        row.put("publishedAt", publishedAt);
        row.put("contentType", contentType);
        row.put("views", views);
        return row;
    }
}
