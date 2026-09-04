package com.youtube.analytics.videoanalysis.scene;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FfmpegSceneDetectorTest {
    @Test
    void detectsCutAndKeepsFinalScene() throws Exception {
        Path file = Files.createTempFile("youtube-analysis-scene-", ".mp4");
        try {
            Process process = new ProcessBuilder("/opt/homebrew/bin/ffmpeg", "-y", "-f", "lavfi",
                    "-i", "color=c=black:size=320x240:rate=25:duration=1", "-f", "lavfi",
                    "-i", "color=c=white:size=320x240:rate=25:duration=1", "-filter_complex",
                    "[0:v][1:v]concat=n=2:v=1:a=0", "-c:v", "libx264", file.toString())
                    .redirectErrorStream(true).start();
            assertTrue(process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(0, process.exitValue());

            List<SceneDetector.SceneBoundary> scenes =
                    new FfmpegSceneDetector(new ObjectMapper()).detect(file, 2000);

            assertTrue(scenes.size() >= 2);
            assertEquals(0, scenes.get(0).startMs());
            assertEquals(2000, scenes.get(scenes.size() - 1).endMs());
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
