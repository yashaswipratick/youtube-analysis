package com.youtube.analytics.service.businesslogic;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Deterministic presentation-oriented analytics calculations.
 *
 * This class only transforms evidence returned by YouTube APIs. It never invents
 * CTR, search volume, watch time, or private metrics for public channels.
 */
@Service
public class AnalyticsWorkspaceBusinessLogic {

    public Map<String, Object> publishingConsistency(
            List<Map<String, Object>> videos, LocalDate start, LocalDate end, String coverageNote) {
        List<Map<String, Object>> source = videos == null ? List.of() : videos;
        LocalDate first = start.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate last = end.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        List<Map<String, Object>> buckets = new ArrayList<>();
        long shortTotal = 0;
        long longTotal = 0;
        long unknownTotal = 0;
        for (LocalDate week = first; !week.isAfter(last); week = week.plusWeeks(1)) {
            long shorts = 0;
            long longs = 0;
            long unknown = 0;
            LocalDate weekEnd = week.plusDays(6);
            for (Map<String, Object> video : source) {
                LocalDate published = date(video.get("publishedAt"));
                if (published == null || published.isBefore(week) || published.isAfter(weekEnd)
                        || published.isBefore(start) || published.isAfter(end)) continue;
                String type = String.valueOf(video.getOrDefault("contentType", "UNKNOWN"));
                if ("SHORT".equalsIgnoreCase(type) || "SHORT_FORM".equalsIgnoreCase(type)) shorts++;
                else if ("LONG_FORM".equalsIgnoreCase(type)) longs++;
                else unknown++;
            }
            shortTotal += shorts;
            longTotal += longs;
            unknownTotal += unknown;
            Map<String, Object> bucket = new LinkedHashMap<>();
            bucket.put("weekStart", week.toString());
            bucket.put("weekEnd", weekEnd.toString());
            bucket.put("shortVideos", shorts);
            bucket.put("longFormVideos", longs);
            bucket.put("totalVideos", shorts + longs + unknown);
            buckets.add(bucket);
        }
        long days = Math.max(1, ChronoUnit.DAYS.between(start, end) + 1);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("startDate", start.toString());
        result.put("endDate", end.toString());
        result.put("coverage", coverageNote);
        result.put("classificationMethod", "Duration-based approximation: 180 seconds or less is treated as likely Shorts; the official Data API does not expose a definitive Shorts-tab flag or video orientation.");
        result.put("weekly", buckets);
        result.put("shortVideosPublished", shortTotal);
        result.put("longFormVideosPublished", longTotal);
        result.put("unclassifiedVideosPublished", unknownTotal);
        result.put("averageShortsPerWeek", round(shortTotal * 7.0 / days));
        result.put("averageLongFormPerWeek", round(longTotal * 7.0 / days));
        result.put("weeksWithShorts", buckets.stream().filter(b -> number(b.get("shortVideos")) > 0).count());
        result.put("weeksWithLongForm", buckets.stream().filter(b -> number(b.get("longFormVideos")) > 0).count());
        result.put("totalWeeksInRange", buckets.size());
        result.put("note", "Publishing counts are calculated from the visible upload dates. A coverage note is provided when the Data API sample is capped.");
        return result;
    }

    public Map<String, Object> annotateComparison(List<Map<String, Object>> videos, boolean publicOnly) {
        List<Map<String, Object>> rows = videos == null ? new ArrayList<>() : videos.stream()
                .filter(Objects::nonNull).map(LinkedHashMap::new).map(m -> (Map<String, Object>) m).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        double meanViews = rows.stream().mapToDouble(row -> number(row.get("views")))
                .filter(Double::isFinite).average().orElse(Double.NaN);
        rows.forEach(row -> {
            double views = number(row.get("views"));
            if (Double.isFinite(meanViews) && meanViews > 0 && Double.isFinite(views)) {
                row.put("viewsVsGroupAveragePercent", round((views - meanViews) * 100.0 / meanViews));
            } else {
                row.put("viewsVsGroupAveragePercent", null);
            }
            double likes = number(row.get(publicOnly ? "likeCount" : "likes"));
            double comments = number(row.get(publicOnly ? "commentCount" : "comments"));
            double engagementNumerator = (Double.isFinite(likes) ? likes : 0) + (Double.isFinite(comments) ? comments : 0);
            if (Double.isFinite(views) && views > 0 && (Double.isFinite(likes) || Double.isFinite(comments))) {
                row.put("publicEngagementRatePercent", round(engagementNumerator * 100.0 / views));
            } else row.put("publicEngagementRatePercent", null);
            List<String> signals = new ArrayList<>();
            if (Double.isFinite(meanViews) && Double.isFinite(views) && meanViews > 0) {
                signals.add(views >= meanViews ? "Views are at or above this comparison group's average." : "Views are below this comparison group's average.");
            }
            Object avgPct = row.get("averageViewPercentage");
            if (avgPct instanceof Number n) signals.add("Average view percentage: " + round(n.doubleValue()) + "% (compare against the same format and reporting window).");
            Object subs = row.get("subscribersGained");
            if (subs instanceof Number n) signals.add("Subscribers gained in the selected report window: " + n.longValue() + ".");
            if (signals.isEmpty()) signals.add("Only public lifetime counters are available; retention and distribution reasons cannot be inferred.");
            row.put("observedSignals", signals);
            List<String> improvements = new ArrayList<>();
            if (!publicOnly && row.get("averageViewPercentage") instanceof Number n && n.doubleValue() < 35) {
                improvements.add("Test a clearer opening and tighter pacing, then compare the retention curve.");
            }
            if (!publicOnly && Double.isFinite(meanViews) && Double.isFinite(views) && meanViews > 0 && views < meanViews) {
                improvements.add("Review impressions, video-level CTR and retention before choosing whether to change packaging or content.");
            }
            if (publicOnly) improvements.add("Public metrics cannot show impressions CTR, retention or watch time; use these comparisons as reach/engagement context only.");
            if (improvements.isEmpty()) improvements.add("Preserve the observed strengths and test one change at a time; this comparison does not prove causation.");
            row.put("improvementIdeas", improvements);
        });
        rows.sort(Comparator.comparingDouble((Map<String, Object> row) -> {
            double value = number(row.get("views"));
            return Double.isFinite(value) ? value : -1.0;
        }).reversed());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", rows);
        result.put("averageViews", Double.isFinite(meanViews) ? round(meanViews) : null);
        result.put("highestViewsVideoId", rows.isEmpty() ? null : rows.get(0).get("videoId"));
        result.put("sampleSize", rows.size());
        result.put("scoreMethod", "Rows are compared within the selected set. Relative views and public engagement rates are descriptive signals, not causal attribution.");
        result.put("publicOnly", publicOnly);
        return result;
    }

    public List<Map<String, Object>> keywordOpportunities(
            String seed, List<String> suggestions, List<Map<String, Object>> measuredSearchTerms) {
        Map<String, Map<String, Object>> measuredByTerm = new LinkedHashMap<>();
        if (measuredSearchTerms != null) for (Map<String, Object> row : measuredSearchTerms) {
            Object term = row.get("searchTerm");
            if (term != null) measuredByTerm.put(normalize(String.valueOf(term)), row);
        }
        Map<String, Map<String, Object>> candidates = new LinkedHashMap<>();
        if (suggestions != null) for (String suggestion : suggestions) {
            if (suggestion == null || suggestion.isBlank()) continue;
            String key = normalize(suggestion);
            if (key.equals(normalize(seed))) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("keyword", suggestion);
            row.put("source", "YouTube public autocomplete");
            Map<String, Object> measured = measuredByTerm.get(key);
            if (measured == null) {
                measured = measuredByTerm.entrySet().stream().filter(e -> e.getKey().contains(key) || key.contains(e.getKey()))
                        .map(Map.Entry::getValue).findFirst().orElse(null);
            }
            row.put("opportunityType", measured == null ? "SUGGESTED_TEST_CANDIDATE" : "RELATED_SEARCH_TERM_GENERATES_VIEWS");
            row.put("measuredSearchViews", measured == null ? null : measured.get("views"));
            row.put("matchedMeasuredSearchTerm", measured == null ? null : measured.get("searchTerm"));
            int additionalWords = Math.max(0, key.split(" ").length - normalize(seed).split(" ").length);
            boolean intentModifier = List.of("best", "guide", "itinerary", "cost", "near", "review", "things to do", "how to", "hidden", "budget", "travel").stream().anyMatch(key::contains);
            int opportunityScore = Math.min(100, 30 + Math.min(25, additionalWords * 10)
                    + (intentModifier ? 15 : 0) + (measured == null ? 0 : 30));
            row.put("opportunityScoreEstimate", opportunityScore);
            row.put("opportunityScoreType", "HEURISTIC_QUERY_FIT_ESTIMATE_NOT_CTR");
            row.put("measuredVideoCtr", null);
            row.put("ctrStatus", "YouTube Analytics API does not expose CTR per search term; this field is intentionally not estimated.");
            row.put("interpretation", measured == null
                    ? "Autocomplete suggests this phrase is entered by users. This is a testing candidate, not proof of demand, ranking difficulty or CTR."
                    : "A similar search term generated views for this channel in the selected period. These views are measured; the suggested phrase's own CTR is not measured.");
            candidates.putIfAbsent(key, row);
        }
        return new ArrayList<>(candidates.values());
    }

    private LocalDate date(Object value) {
        if (value == null) return null;
        try { return LocalDate.parse(String.valueOf(value).substring(0, 10)); }
        catch (RuntimeException ex) { return null; }
    }
    private double number(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        if (value != null) try { return Double.parseDouble(String.valueOf(value)); } catch (RuntimeException ignored) { }
        return Double.NaN;
    }
    private String normalize(String value) { return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim(); }
    private double round(double value) { return Math.round(value * 100.0) / 100.0; }
}
