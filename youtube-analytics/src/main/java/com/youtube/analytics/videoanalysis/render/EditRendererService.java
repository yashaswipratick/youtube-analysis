package com.youtube.analytics.videoanalysis.render;

import com.youtube.analytics.videoanalysis.ingestion.LocalMediaInputProperties;
import com.youtube.analytics.videoanalysis.ingestion.MediaApprovalService;
import com.youtube.analytics.videoanalysis.model.ClipCandidate;
import com.youtube.analytics.videoanalysis.model.EditPlan;
import com.youtube.analytics.videoanalysis.service.FfprobeMediaMetadataService;
import com.youtube.analytics.videoanalysis.service.MediaToolResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class EditRendererService {

    private final MediaApprovalService approvalService;
    private final LocalMediaInputProperties inputProperties;
    private final FfprobeMediaMetadataService metadataService;
    private final AudioMixService audioMixService;

    public EditRendererService(MediaApprovalService approvalService,
                               LocalMediaInputProperties inputProperties,
                               FfprobeMediaMetadataService metadataService,
                               AudioMixService audioMixService) {
        this.approvalService = approvalService;
        this.inputProperties = inputProperties;
        this.metadataService = metadataService;
        this.audioMixService = audioMixService;
    }

    public Path render(EditPlan plan) {
        if (plan == null || plan.sequence().isEmpty()) {
            throw new IllegalArgumentException("Edit plan must contain at least one sequence item");
        }

        Path outputDirectory = Path.of(inputProperties.outputDirectory()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(outputDirectory);
            Path workDirectory = Files.createTempDirectory(outputDirectory, "edit-");
            List<Path> segments = new ArrayList<>();
            try {
                for (EditPlan.EditSequenceItem item : plan.sequence()) {
                    segments.add(renderSegment(item.clip(), workDirectory));
                }
                Path concatFile = workDirectory.resolve("concat.txt");
                Files.writeString(concatFile, concatEntries(segments), StandardCharsets.UTF_8);
                Path output = outputDirectory.resolve(safeProjectId(plan.projectId()) + "-edit.mp4");
                run(List.of(MediaToolResolver.resolve("ffmpeg"), "-y", "-f", "concat", "-safe", "0",
                        "-i", concatFile.toString(), "-c", "copy", "-movflags", "+faststart", output.toString()),
                        "ffmpeg failed while assembling the edit");
                return audioMixService.mix(output);
            } finally {
                deleteRecursively(workDirectory);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to prepare edit render output", ex);
        }
    }

    private Path renderSegment(ClipCandidate clip, Path workDirectory) throws IOException {
        Path source = inputProperties.approvalRequired()
                ? approvalService.getApprovedPath(clip.sourceFileName())
                : approvalService.getPath(clip.sourceFileName());
        if (!Files.isRegularFile(source)) {
            throw new IllegalArgumentException("Source video does not exist: " + clip.sourceFileName());
        }
        long durationMs = clip.durationMs();
        if (durationMs <= 0) {
            throw new IllegalArgumentException("Clip duration must be positive: " + clip.sourceFileName());
        }

        Path segment = Files.createTempFile(workDirectory, "segment-", ".mp4");
        FfprobeMediaMetadataService.VideoMetadata metadata = metadataService.probe(source);
        List<String> command = new ArrayList<>(List.of(MediaToolResolver.resolve("ffmpeg"), "-y",
                "-ss", formatTimestamp(clip.sourceStartMs()), "-i", source.toString()));
        if (!metadata.audioPresent()) {
            command.addAll(List.of("-f", "lavfi", "-i", "anullsrc=channel_layout=stereo:sample_rate=48000"));
        }
        command.addAll(List.of("-t", formatTimestamp(durationMs), "-map", "0:v:0"));
        if (metadata.audioPresent()) {
            command.addAll(List.of("-map", "0:a:0"));
        } else {
            command.addAll(List.of("-map", "1:a:0"));
        }
        command.addAll(List.of("-c:v", "libx264", "-preset", "fast", "-crf", "18", "-pix_fmt", "yuv420p",
                "-r", "30", "-c:a", "aac", "-ar", "48000", "-ac", "2", "-shortest", segment.toString()));
        run(command, "ffmpeg failed while rendering clip " + clip.sourceFileName());
        return segment;
    }

    private String concatEntries(List<Path> segments) {
        return segments.stream()
                .map(path -> "file '" + path.toAbsolutePath().toString().replace("'", "'\\''") + "'\n")
                .reduce("", String::concat);
    }

    private void run(List<String> command, String failureMessage) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(10, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IllegalStateException(failureMessage + ": timed out");
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException(failureMessage + ": " + output);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to execute ffmpeg; install ffmpeg and ensure it is on PATH", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while rendering edit", ex);
        }
    }

    private String formatTimestamp(long timestampMs) {
        return String.format(java.util.Locale.ROOT, "%.3f", timestampMs / 1000.0);
    }

    private String safeProjectId(String projectId) {
        if (projectId == null || projectId.isBlank()) return "video-edit";
        return projectId.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private void deleteRecursively(Path directory) {
        if (directory == null || !Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }
}
