package com.youtube.analytics.videoanalysis.scene;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import com.youtube.analytics.videoanalysis.service.MediaToolResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class FfmpegSceneDetector implements SceneDetector {

    private final ObjectMapper objectMapper;

    public FfmpegSceneDetector(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public List<SceneBoundary> detect(Path sourceFile, long durationMs) {
        ProcessBuilder builder = new ProcessBuilder(MediaToolResolver.resolve("ffprobe"), "-v", "error", "-print_format", "json",
                "-show_entries", "frame=best_effort_timestamp_time", "-of", "json",
                "-f", "lavfi", "movie='" + sourceFile.toAbsolutePath() + "',select=gt(scene\\,0.35)");
        builder.redirectErrorStream(true);
        try {
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(5, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IllegalStateException("ffprobe timed out during scene detection");
            }
            if (process.exitValue() != 0) throw new IllegalStateException("ffprobe scene detection failed: exit " + process.exitValue());
            return parseBoundaries(output, durationMs);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to execute ffprobe for scene detection", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during scene detection", ex);
        }
    }

    private List<SceneBoundary> parseBoundaries(String output, long durationMs) {
        try {
            JsonNode frames = objectMapper.readTree(output).path("frames");
            List<Long> cuts = new ArrayList<>();
            if (frames.isArray()) for (JsonNode frame : frames) {
                double seconds = frame.path("best_effort_timestamp_time").asDouble(-1);
                if (seconds >= 0) cuts.add(Math.round(seconds * 1000));
            }
            List<SceneBoundary> result = new ArrayList<>();
            long previous = 0;
            for (Long cut : cuts) {
                if (cut > previous) result.add(new SceneBoundary(previous, cut));
                previous = cut;
            }
            if (durationMs > previous) result.add(new SceneBoundary(previous, durationMs));
            return result;
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to parse ffprobe scene detection output", ex);
        }
    }
}
