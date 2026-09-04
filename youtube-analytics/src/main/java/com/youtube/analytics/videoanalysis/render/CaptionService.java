package com.youtube.analytics.videoanalysis.render;

import com.youtube.analytics.videoanalysis.model.EditPlan;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@Service
public class CaptionService {

    public Path writeSrt(EditPlan plan, Path outputDirectory) {
        Path output = outputDirectory.resolve(safeProjectId(plan.projectId()) + "-captions.srt");
        StringBuilder srt = new StringBuilder();
        int index = 1;
        for (EditPlan.EditSequenceItem item : plan.sequence()) {
            String text = item.clip().spokenText();
            if (text == null || text.isBlank()) continue;
            srt.append(index++).append('\n')
                    .append(format(item.timelineStartMs())).append(" --> ")
                    .append(format(item.timelineEndMs())).append('\n')
                    .append(text.trim()).append("\n\n");
        }
        try {
            Files.writeString(output, srt.toString(), StandardCharsets.UTF_8);
            return output;
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to write caption file", ex);
        }
    }

    private String format(long ms) {
        long hours = ms / 3_600_000;
        long minutes = (ms % 3_600_000) / 60_000;
        long seconds = (ms % 60_000) / 1_000;
        long millis = ms % 1_000;
        return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d,%03d", hours, minutes, seconds, millis);
    }

    private String safeProjectId(String projectId) {
        return projectId == null || projectId.isBlank() ? "video-edit" : projectId.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
