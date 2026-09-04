package com.youtube.analytics.videoanalysis.render;

import com.youtube.analytics.videoanalysis.config.AudioMixProperties;
import com.youtube.analytics.videoanalysis.service.MediaToolResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class AudioMixService {

    private final AudioMixProperties properties;

    public AudioMixService(AudioMixProperties properties) {
        this.properties = properties;
    }

    public Path mix(Path video) {
        if (properties.musicDirectory() == null || properties.musicDirectory().isBlank()
                || properties.musicFileName() == null || properties.musicFileName().isBlank()) {
            return video;
        }
        Path musicDirectory = Path.of(properties.musicDirectory()).toAbsolutePath().normalize();
        Path music = musicDirectory.resolve(properties.musicFileName()).normalize();
        if (!music.startsWith(musicDirectory)) {
            throw new IllegalArgumentException("Configured music file must be inside the configured music directory");
        }
        if (!Files.isRegularFile(music)) {
            throw new IllegalArgumentException("Configured music file does not exist: " + music);
        }
        Path mixed = video.resolveSibling(video.getFileName().toString().replace(".mp4", "-mixed.mp4"));
        List<String> filter = new ArrayList<>();
        filter.add("[1:a]volume=" + properties.musicVolume() + "[music]");
        if (properties.duckSpeech()) {
            filter.add("[music][0:a]sidechaincompress=threshold=0.03:ratio=8:attack=20:release=300[ducked]");
            filter.add("[0:a][ducked]amix=inputs=2:duration=first:dropout_transition=2[aout]");
        } else {
            filter.add("[0:a][music]amix=inputs=2:duration=first:dropout_transition=2[aout]");
        }
        run(List.of(MediaToolResolver.resolve("ffmpeg"), "-y", "-i", video.toString(), "-stream_loop", "-1",
                "-i", music.toString(), "-filter_complex", String.join(";", filter),
                "-map", "0:v:0", "-map", "[aout]", "-c:v", "copy", "-c:a", "aac", "-shortest", mixed.toString()),
                "ffmpeg failed while mixing background music");
        try {
            Files.move(mixed, video, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to replace rendered video with mixed audio", ex);
        }
        return video;
    }

    private void run(List<String> command, String failureMessage) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(10, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IllegalStateException(failureMessage + ": timed out");
            }
            if (process.exitValue() != 0) throw new IllegalStateException(failureMessage + ": " + output);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to execute ffmpeg for audio mixing", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while mixing audio", ex);
        }
    }
}
