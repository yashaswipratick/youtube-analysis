package com.youtube.analytics.videoanalysis.frame;

import org.springframework.stereotype.Service;
import com.youtube.analytics.videoanalysis.service.MediaToolResolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class FfmpegFrameExtractor implements FrameExtractor {

    @Override
    public List<ExtractedFrame> extract(Path sourceFile, List<Long> timestampsMs) {
        List<ExtractedFrame> frames = new ArrayList<>();
        for (Long timestamp : timestampsMs) {
            if (timestamp == null || timestamp < 0) continue;
            Path output = null;
            try {
                output = Files.createTempFile("youtube-analysis-frame-", ".jpg");
                Process process = new ProcessBuilder(MediaToolResolver.resolve("ffmpeg"), "-y", "-ss", formatTimestamp(timestamp),
                        "-i", sourceFile.toAbsolutePath().toString(), "-frames:v", "1", "-q:v", "3",
                        output.toAbsolutePath().toString())
                        .redirectErrorStream(true).start();
                String processOutput = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                if (!process.waitFor(60, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    Files.deleteIfExists(output);
                    throw new IllegalStateException("ffmpeg timed out while extracting frame at " + timestamp + "ms");
                }
                if (process.exitValue() != 0 || !Files.isRegularFile(output) || Files.size(output) == 0) {
                    Files.deleteIfExists(output);
                    throw new IllegalStateException("ffmpeg failed while extracting frame at " + timestamp + "ms: " + processOutput);
                }
                frames.add(new ExtractedFrame(timestamp, output));
            } catch (IOException ex) {
                deleteQuietly(output);
                throw new IllegalStateException("Unable to execute ffmpeg; install ffmpeg and ensure it is on PATH", ex);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                deleteQuietly(output);
                throw new IllegalStateException("Interrupted while extracting video frame", ex);
            }
        }
        return frames;
    }

    private String formatTimestamp(long timestampMs) {
        return String.format(java.util.Locale.ROOT, "%.3f", timestampMs / 1000.0);
    }

    private void deleteQuietly(Path path) {
        if (path == null) return;
        try { Files.deleteIfExists(path); } catch (IOException ignored) { }
    }
}
