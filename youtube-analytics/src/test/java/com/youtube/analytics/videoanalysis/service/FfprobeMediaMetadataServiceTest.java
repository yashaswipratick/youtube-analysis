package com.youtube.analytics.videoanalysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FfprobeMediaMetadataServiceTest {

    @Test
    void probesGeneratedMp4Metadata() throws Exception {
        Path file = Files.createTempFile("youtube-analysis-test-", ".mp4");
        try {
            Process process = new ProcessBuilder(
                    MediaToolResolver.resolve("ffmpeg"), "-y", "-f", "lavfi", "-i", "color=size=320x240:rate=25:duration=1",
                    "-f", "lavfi", "-i", "anullsrc=r=16000:cl=mono",
                    "-shortest", "-c:v", "libx264", "-c:a", "aac", file.toString())
                    .redirectErrorStream(true)
                    .start();
            assertTrue(process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(0, process.exitValue());

            FfprobeMediaMetadataService.VideoMetadata metadata =
                    new FfprobeMediaMetadataService(new ObjectMapper()).probe(file);

            assertTrue(metadata.durationMs() > 0);
            assertEquals(320, metadata.width());
            assertEquals(240, metadata.height());
            assertTrue(metadata.audioPresent());
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
