package com.youtube.analytics.videoanalysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class FfprobeMediaMetadataService {

    private final ObjectMapper objectMapper;

    public FfprobeMediaMetadataService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public VideoMetadata probe(Path sourceFile) {
        ProcessBuilder processBuilder = new ProcessBuilder(List.of(
                MediaToolResolver.resolve("ffprobe"), "-v", "error", "-print_format", "json",
                "-show_entries", "format=duration:stream=codec_type,width,height,r_frame_rate",
                sourceFile.toAbsolutePath().toString()));
        processBuilder.redirectErrorStream(true);
        try {
            Process process = processBuilder.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("ffprobe timed out for media file");
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("ffprobe failed for media file: " + process.exitValue());
            }
            return parse(output);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to execute ffprobe; install ffmpeg/ffprobe and ensure it is on PATH", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while probing media file", ex);
        }
    }

    private VideoMetadata parse(String output) {
        try {
            JsonNode root = objectMapper.readTree(output);
            double durationSeconds = root.path("format").path("duration").asDouble(0.0);
            JsonNode streams = root.path("streams");
            Integer width = null;
            Integer height = null;
            String frameRate = null;
            boolean audioPresent = false;
            if (streams.isArray()) {
                for (JsonNode stream : streams) {
                    String codecType = stream.path("codec_type").asText();
                    if ("video".equals(codecType)) {
                        width = stream.path("width").isNumber() ? stream.path("width").asInt() : width;
                        height = stream.path("height").isNumber() ? stream.path("height").asInt() : height;
                        frameRate = stream.path("r_frame_rate").asText(null);
                    } else if ("audio".equals(codecType)) {
                        audioPresent = true;
                    }
                }
            }
            return new VideoMetadata(Math.max(0L, Math.round(durationSeconds * 1000)), width, height, frameRate, audioPresent);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to parse ffprobe media metadata", ex);
        }
    }

    public record VideoMetadata(long durationMs, Integer width, Integer height, String frameRate, boolean audioPresent) {
    }
}
