package com.youtube.analytics.service.creatorcoach;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Builds a compact, one-page plain-language PDF and a copyable text checklist from a stored report. */
@Service
public class CreatorCoachExportService {
    private final ObjectMapper mapper;

    public CreatorCoachExportService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public byte[] plainText(Map<String, Object> report) {
        return reportText(report).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    public byte[] pdf(Map<String, Object> report) {
        String text = reportText(report);
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            PDFont font = loadUnicodeFont(document);
            boolean unicodeFont = font instanceof PDType0Font;
            if (!unicodeFont) text = toPortableText(text);
            List<String> lines = List.of();
            float fontSize = 9f;
            for (; fontSize >= 6.5f; fontSize -= 0.5f) {
                lines = wrap(text, font, fontSize, 540f);
                if (lines.size() <= 66) break;
            }
            if (lines.size() > 66) {
                List<String> bounded = new ArrayList<>(lines.subList(0, 65));
                bounded.add("... Full checklist and report details are available as text export.");
                lines = bounded;
            }
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(font, fontSize);
                stream.newLineAtOffset(36, 756);
                float lineHeight = fontSize + 2.2f;
                for (String line : lines) {
                    String safeLine = unicodeFont ? line : toPortableText(line);
                    stream.showText(safeLine);
                    stream.newLineAtOffset(0, -lineHeight);
                }
                stream.endText();
            }
            document.save(bytes);
            return bytes.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Could not create the report PDF.", ex);
        }
    }

    private PDFont loadUnicodeFont(PDDocument document) {
        List<String> candidates = List.of(
                "/System/Library/Fonts/Supplemental/Arial Unicode.ttf",
                "/System/Library/Fonts/Supplemental/Arial.ttf",
                "/Library/Fonts/Arial Unicode.ttf",
                "/usr/share/fonts/truetype/noto/NotoSansDevanagari-Regular.ttf",
                "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
                "C:/Windows/Fonts/arial.ttf");
        for (String candidate : candidates) {
            try {
                File file = new File(candidate);
                if (file.isFile()) return PDType0Font.load(document, file);
            } catch (IOException ignored) {
                // Try the next installed font.
            }
        }
        return new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    }

    private List<String> wrap(String text, PDFont font, float size, float maxWidth) throws IOException {
        List<String> out = new ArrayList<>();
        for (String paragraph : text.split("\\R", -1)) {
            if (paragraph.isBlank()) {
                out.add("");
                continue;
            }
            StringBuilder line = new StringBuilder();
            for (String word : paragraph.split("\\s+")) {
                String candidate = line.isEmpty() ? word : line + " " + word;
                float width;
                try {
                    width = font.getStringWidth(candidate) / 1000f * size;
                } catch (IllegalArgumentException unsupportedGlyph) {
                    width = maxWidth + 1;
                }
                if (width > maxWidth && !line.isEmpty()) {
                    out.add(line.toString());
                    line.setLength(0);
                    line.append(word);
                } else {
                    if (!line.isEmpty()) line.append(' ');
                    line.append(word);
                }
            }
            if (!line.isEmpty()) out.add(line.toString());
        }
        return out;
    }

    public byte[] plainTextPlan(Map<String, Object> plan) {
        Map<String, Object> outline = asMap(plan.get("outline"));
        StringBuilder text = new StringBuilder();
        text.append(string(plan.get("title"), "Untitled video plan")).append('\n');
        text.append("Five-part travel-video outline\n\n");
        for (String key : List.of("opening", "personal_reason", "journey_discoveries", "reflection", "ending")) {
            text.append(switch (key) {
                case "opening" -> "1. Opening / curiosity";
                case "personal_reason" -> "2. Why this journey matters to you";
                case "journey_discoveries" -> "3. Journey and discoveries";
                case "reflection" -> "4. Personal reflection";
                default -> "5. Ending / answer to the opening";
            }).append('\n');
            String value = string(outline.get(key), "");
            text.append(value.isBlank() ? "[Write your notes here]" : value).append("\n\n");
        }
        return text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private String reportText(Map<String, Object> report) {
        StringBuilder text = new StringBuilder();
        append(text, string(report.get("title"), "Your video"));
        append(text, "Your story-coach report");
        append(text, "In simple words");
        append(text, string(report.get("plain_summary"), "No summary was returned."));
        if (report.get("early_data_note") != null) append(text, string(report.get("early_data_note"), ""));
        Map<String, Object> fix = asMap(report.get("fix_first"));
        append(text, "FIX THIS FIRST");
        append(text, string(fix.get("action"), "No action returned."));
        append(text, string(fix.get("why"), ""));
        for (Object step : list(fix.get("steps"))) append(text, "• " + step);
        append(text, "Your video, part by part");
        List<Map<String, Object>> segments = mapList(report.get("segments"));
        for (Map<String, Object> segment : segments.stream().limit(5).toList()) {
            String percent = segment.get("percent_of_runtime") instanceof Number
                    ? " · " + displayPercent(segment.get("percent_of_runtime")) + " of runtime" : "";
            append(text, timestamp(segment.get("start_sec")) + "–" + timestamp(segment.get("end_sec"))
                    + " · " + string(segment.get("label"), "Part of the story") + percent);
            append(text, clip(string(segment.get("what_happens"), ""), 105));
        }
        if (segments.size() > 5) append(text, "More parts are available in the full report.");
        Map<String, Object> titleCheck = asMap(report.get("title_check"));
        append(text, "Does your title match your video?");
        append(text, clip(string(titleCheck.get("explanation"), ""), 150));
        if (titleCheck.get("first_delivered_at_sec") instanceof Number) {
            append(text, "First delivered around " + timestamp(titleCheck.get("first_delivered_at_sec")) + ".");
        }
        if (titleCheck.get("percent_on_title_promise") != null) {
            append(text, displayPercent(titleCheck.get("percent_on_title_promise")) + " of your video was marked as delivering the title promise.");
        }
        append(text, "Keep doing this");
        List<Map<String, Object>> strengths = mapList(report.get("keep_doing"));
        for (Map<String, Object> strength : strengths.stream().limit(2).toList()) {
            append(text, string(strength.get("strength"), ""));
            if (strength.get("at_sec") instanceof Number) append(text, "From your video at " + timestamp(strength.get("at_sec")) + ".");
            String quote = clip(string(strength.get("quote"), ""), 90);
            if (!quote.isBlank()) append(text, "“" + quote + "”");
        }
        if (strengths.size() > 2) append(text, "More strengths are available in the full report.");
        append(text, "What we noticed");
        List<Map<String, Object>> findings = mapList(report.get("findings"));
        for (Map<String, Object> finding : findings.stream().limit(2).toList()) {
            append(text, string(finding.get("title"), ""));
            append(text, clip(string(finding.get("observation"), ""), 130));
            List<Map<String, Object>> evidence = mapList(finding.get("evidence"));
            if (!evidence.isEmpty()) {
                Map<String, Object> item = evidence.get(0);
                if (item.get("quote") != null) append(text, "“" + clip(string(item.get("quote"), ""), 80) + "” · " + timestamp(item.get("at_sec")));
            }
            append(text, "Our best guess: " + clip(string(finding.get("best_guess"), ""), 120));
        }
        append(text, "Try these next");
        List<Map<String, Object>> recommendations = mapList(report.get("recommendations"));
        for (Map<String, Object> rec : recommendations.stream().limit(2).toList()) {
            append(text, "try_and_see".equals(rec.get("tag")) ? "Try it and see" : "Backed by your video");
            append(text, clip(string(rec.get("action"), ""), 100));
            append(text, "Our best guess: " + clip(string(rec.get("reason"), ""), 110));
            List<?> testSteps = list(rec.get("test_steps"));
            if (!testSteps.isEmpty()) append(text, "Try this: " + clip(String.valueOf(testSteps.get(0)), 90));
            if (rec.get("success_signal") != null) append(text, "Look for: " + clip(string(rec.get("success_signal"), ""), 100));
        }
        append(text, "Your numbers");
        Map<String, Object> metrics = asMap(report.get("metrics"));
        append(text, "Views: " + displayNumber(metrics.get("views"))
                + " — Views counted in the selected reporting window.");
        append(text, "How long people watch on average: " + displayDuration(metrics.get("avg_watch_seconds"))
                + " — An average per view, not the exact second people left.");
        append(text, "How much of your video people watch: " + displayPercent(metrics.get("average_watch_percent"))
                + " — The average share of the full video watched per view.");
        append(text, "Times your thumbnail was shown: " + displayNumber(metrics.get("impressions"))
                + " — How often YouTube counted your thumbnail as shown.");
        append(text, "Out of people who saw your thumbnail, how many clicked: " + displayPercent(metrics.get("thumbnail_click_percent"))
                + " — A clue about your title and thumbnail together.");
        append(text, "New subscribers: " + displayNumber(metrics.get("new_subscribers"))
                + " — Viewers who subscribed after this video, when available.");
        append(text, "Shares: " + displayNumber(metrics.get("shares")) + " — How often the video was shared, when available.");
        Map<String, Object> traffic = asMap(metrics.get("traffic_share"));
        append(text, "How people found your video");
        List<Map<String, Object>> sourceRows = mapList(traffic.get("sources"));
        if (!Boolean.TRUE.equals(traffic.get("available")) || sourceRows.isEmpty()) {
            append(text, "This breakdown was not available for this video.");
        } else {
            for (Map<String, Object> source : sourceRows.stream().limit(4).toList()) {
                append(text, plainTrafficSource(source.get("source_type")) + ": "
                        + displayPercent(source.get("percent_of_views")) + " of the sources returned.");
            }
        }
        append(text, "Next-video checklist");
        for (Object item : list(report.get("next_video_checklist")).stream().limit(5).toList()) {
            append(text, "☐ " + clip(String.valueOf(item), 100));
        }
        List<?> questions = list(report.get("questions_for_creator"));
        Map<String, Object> answers = asMap(report.get("creatorAnswers"));
        if (!questions.isEmpty()) {
            append(text, "Questions only you can answer");
            for (Object item : questions.stream().limit(2).toList()) {
                String question = String.valueOf(item);
                append(text, clip(question, 100));
                if (answers.get(question) != null) append(text, "Your note: " + clip(string(answers.get(question), ""), 100));
            }
        }
        append(text, "What this report cannot tell you");
        for (Object item : list(report.get("cannot_tell_you")).stream().limit(3).toList()) {
            append(text, "• " + clip(String.valueOf(item), 100));
        }
        append(text, "Numbers are early hints, not proof of cause. Preserve your own voice and test one change at a time.");
        return text.toString();
    }

    private String clip(String value, int maxCharacters) {
        if (value == null || value.isBlank()) return "";
        String text = value.trim();
        return text.length() <= maxCharacters ? text : text.substring(0, Math.max(0, maxCharacters - 1)).trim() + "…";
    }

    private String displayNumber(Object value) {
        if (!(value instanceof Number number)) return "Not available";
        java.text.NumberFormat format = java.text.NumberFormat.getNumberInstance(java.util.Locale.US);
        format.setMaximumFractionDigits(1);
        format.setMinimumFractionDigits(0);
        return format.format(number.doubleValue());
    }

    private String displayPercent(Object value) {
        return value instanceof Number ? displayNumber(value) + "%" : "Not available";
    }

    private String displayDuration(Object value) {
        if (!(value instanceof Number number)) return "Not available";
        int seconds = Math.max(0, (int)Math.round(number.doubleValue()));
        int minutes = seconds / 60;
        int remainder = seconds % 60;
        if (minutes == 0) return remainder + " s";
        return remainder == 0 ? minutes + " min" : minutes + " min " + remainder + " s";
    }

    private String plainTrafficSource(Object value) {
        String key = value == null ? "" : String.valueOf(value).toUpperCase(java.util.Locale.ROOT);
        return switch (key) {
            case "YT_SEARCH" -> "YouTube search";
            case "RELATED_VIDEO" -> "Suggested videos";
            case "BROWSE_FEATURES" -> "Home and browse";
            case "SHORTS" -> "Shorts feed";
            case "EXTERNAL" -> "External websites or apps";
            case "NOTIFICATION", "NO_LINK_NOTIFICATION" -> "Notifications";
            case "PLAYLIST", "NO_LINK_PLAYLIST" -> "Playlists";
            case "CHANNEL", "NO_LINK_CHANNEL" -> "Channel pages";
            case "END_SCREEN" -> "End screens";
            case "SUBSCRIBER" -> "Subscriptions";
            case "DIRECT_OR_UNKNOWN" -> "Direct or unknown";
            case "ADVERTISING" -> "Advertising";
            case "NO_LINK_EMBEDDED" -> "Embedded players";
            case "LIVE_REDIRECT" -> "Live redirect";
            case "NO_LINK_OTHER" -> "Other YouTube features";
            default -> "Other sources";
        };
    }

    private void append(StringBuilder builder, String value) {
        if (value == null || value.isBlank()) return;
        builder.append(value.trim()).append('\n');
    }

    private String timestamp(Object value) {
        if (!(value instanceof Number number)) return "--:--";
        int seconds = Math.max(0, number.intValue());
        return seconds >= 3600
                ? String.format("%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
                : String.format("%02d:%02d", seconds / 60, seconds % 60);
    }

    private String toPortableText(String text) {
        return text.replace("☐", "[ ]").replace("•", "-").replaceAll("[^\\u0000-\\u00FF]", "?");
    }

    private List<Map<String, Object>> mapList(Object value) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (value instanceof List<?> rows) for (Object row : rows) result.add(asMap(row));
        return result;
    }

    private List<?> list(Object value) {
        return value instanceof List<?> values ? values : List.of();
    }

    private Map<String, Object> asMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) return Map.of();
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private String string(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }
}
