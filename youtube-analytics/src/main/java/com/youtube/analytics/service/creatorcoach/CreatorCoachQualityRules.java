package com.youtube.analytics.service.creatorcoach;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Deterministic, model-independent rules used before a story-coach report is returned. */
public final class CreatorCoachQualityRules {
    private CreatorCoachQualityRules() { }

    public static List<Map<String, Object>> audienceMoments(List<Map<String, Object>> curve, double durationSeconds) {
        if (curve == null || curve.size() < 3 || durationSeconds <= 0) return List.of();
        int ignoreBefore = (int) Math.ceil(durationSeconds * 0.02);
        int size = curve.size();
        int[] seconds = new int[size];
        double[] raw = new double[size];
        double[] smoothed = new double[size];
        for (int i = 0; i < size; i++) {
            seconds[i] = integer(curve.get(i).get("at_sec")) == null ? -1 : integer(curve.get(i).get("at_sec"));
            Object value = curve.get(i).get("audienceWatchRatio");
            raw[i] = value instanceof Number n ? n.doubleValue() : Double.NaN;
            smoothed[i] = Double.NaN;
        }

        // Smooth only with valid samples strictly after the opening 2%; do not let the common opening cliff
        // bleed into the first eligible point's moving average.
        for (int i = 0; i < size; i++) {
            if (seconds[i] <= ignoreBefore || !Double.isFinite(raw[i])) continue;
            double sum = 0;
            int count = 0;
            for (int j = Math.max(0, i - 1); j <= Math.min(size - 1, i + 1); j++) {
                if (seconds[j] <= ignoreBefore || !Double.isFinite(raw[j])) continue;
                sum += raw[j];
                count++;
            }
            if (count > 0) smoothed[i] = sum / count;
        }

        List<Map<String, Object>> drops = new ArrayList<>();
        List<Map<String, Object>> rises = new ArrayList<>();
        for (int i = 1; i < size; i++) {
            // Both sides of each compared interval must be outside the excluded opening interval.
            if (seconds[i] <= ignoreBefore || seconds[i - 1] <= ignoreBefore
                    || !Double.isFinite(smoothed[i]) || !Double.isFinite(smoothed[i - 1])) continue;
            double delta = (smoothed[i] - smoothed[i - 1]) * 100d;
            if (delta < 0) drops.add(moment("drop", seconds[i], "lost_percent_points", round1(-delta)));
            else if (delta > 0) rises.add(moment("spike", seconds[i], "rise_percent_points", round1(delta)));
        }
        drops.sort(Comparator.comparingDouble(item -> -doubleValue(item.get("lost_percent_points"))));
        rises.sort(Comparator.comparingDouble(item -> -doubleValue(item.get("rise_percent_points"))));
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < Math.min(2, drops.size()); i++) {
            Map<String, Object> item = new LinkedHashMap<>(drops.get(i));
            item.put("id", "drop" + (i + 1));
            result.add(item);
        }
        if (!rises.isEmpty()) {
            Map<String, Object> item = new LinkedHashMap<>(rises.get(0));
            item.put("id", "spike1");
            result.add(item);
        } else {
            int best = -1;
            double bestScore = Double.NEGATIVE_INFINITY;
            for (int i = 1; i < size - 1; i++) {
                if (seconds[i] <= ignoreBefore || seconds[i - 1] <= ignoreBefore || seconds[i + 1] <= ignoreBefore
                        || !Double.isFinite(smoothed[i - 1]) || !Double.isFinite(smoothed[i])
                        || !Double.isFinite(smoothed[i + 1])) continue;
                double score = smoothed[i] - Math.abs(smoothed[i + 1] - smoothed[i - 1]);
                if (score > bestScore) {
                    best = i;
                    bestScore = score;
                }
            }
            if (best >= 0) {
                Map<String, Object> plateau = new LinkedHashMap<>();
                plateau.put("id", "spike1");
                plateau.put("type", "plateau");
                plateau.put("at_sec", seconds[best]);
                plateau.put("rise_percent_points", 0d);
                result.add(plateau);
            }
        }
        return result;
    }

    public static boolean isQuoteValid(String transcript, List<Map<String, Object>> sourceSegments,
                                       String quote, Integer atSec, int durationSeconds) {
        if (transcript == null || quote == null || quote.isBlank() || atSec == null
                || atSec < 0 || atSec > durationSeconds || wordCount(quote) > 20
                || !normalize(transcript).contains(normalize(quote))) return false;
        Integer cueAt = matchedQuoteTimestamp(sourceSegments, quote);
        return cueAt != null && Math.abs(cueAt - atSec) <= 5;
    }

    public static void capConfidence(Map<String, Object> report, boolean audienceCurveAvailable) {
        if (audienceCurveAvailable || report == null) return;
        for (String key : List.of("title_check", "fix_first")) {
            Map<String, Object> item = map(report.get(key));
            cap(item);
            report.put(key, item);
        }
        for (String key : List.of("findings", "recommendations")) {
            Object raw = report.get(key);
            if (raw instanceof List<?> values) for (Object value : values) cap(map(value));
        }
    }

    private static Map<String, Object> moment(String type, int atSec, String amountField, double amount) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", type);
        result.put("type", type);
        result.put("at_sec", atSec);
        result.put(amountField, amount);
        return result;
    }

    private static Integer matchedQuoteTimestamp(List<Map<String, Object>> cues, String quote) {
        if (cues == null) return null;
        String normalizedQuote = normalize(quote);
        if (normalizedQuote.isBlank()) return null;
        String[] words = normalizedQuote.split(" ");
        String firstWords = words.length > 4 ? String.join(" ", java.util.Arrays.copyOf(words, 4)) : normalizedQuote;
        for (Map<String, Object> cue : cues) {
            String normalizedCue = normalize(String.valueOf(cue.getOrDefault("text", "")));
            if (!normalizedCue.isBlank()
                    && (normalizedCue.contains(firstWords) || normalizedQuote.startsWith(normalizedCue) || normalizedCue.startsWith(normalizedQuote))) {
                return integer(cue.get("start_sec"));
            }
        }
        return null;
    }

    private static void cap(Map<String, Object> item) {
        Object confidence = item.get("how_sure");
        if ("very".equals(confidence)) item.put("how_sure", "fairly");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        if (value instanceof Map<?, ?> raw) return (Map<String, Object>) raw;
        return new LinkedHashMap<>();
    }

    private static Integer integer(Object value) {
        if (value instanceof Number number) return number.intValue();
        try { return value == null ? null : (int)Math.round(Double.parseDouble(String.valueOf(value))); }
        catch (NumberFormatException ignored) { return null; }
    }

    private static double doubleValue(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        try { return value == null ? 0 : Double.parseDouble(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private static double round1(double value) {
        return Math.round(value * 10d) / 10d;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private static int wordCount(String value) {
        return value == null || value.isBlank() ? 0 : value.trim().split("\\s+").length;
    }
}
