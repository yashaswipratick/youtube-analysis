package com.youtube.analytics.videoanalysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youtube.analytics.videoanalysis.ingestion.LocalMediaInputProperties;
import com.youtube.analytics.videoanalysis.model.AudioProfile;
import com.youtube.analytics.videoanalysis.model.RawVideoClipAnalysis;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AnalysisCacheServiceTest {

    @Test
    void savesAndLoadsAnalysisByContentHash() throws Exception {
        Path directory = Files.createTempDirectory("analysis-cache-test-");
        Path video = directory.resolve("clip.mp4");
        Path cache = directory.resolve("cache");
        Files.writeString(video, "video-content");
        LocalMediaInputProperties properties = new LocalMediaInputProperties(
                directory.toString(), false, "renders", cache.toString(), true, 4);
        AnalysisCacheService service = new AnalysisCacheService(new ObjectMapper(), properties);
        RawVideoClipAnalysis expected = new RawVideoClipAnalysis(
                "clip.mp4", 5_000, List.of(), List.of(),
                new AudioProfile(false, 0.0, 0.0, false), 0.8);

        service.save(video, expected);

        assertEquals(expected, service.load(video));
        try (var files = Files.list(cache)) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void ignoresCacheWhenVideoContentChanges() throws Exception {
        Path directory = Files.createTempDirectory("analysis-cache-test-");
        Path video = directory.resolve("clip.mp4");
        Path cache = directory.resolve("cache");
        Files.writeString(video, "first-content");
        LocalMediaInputProperties properties = new LocalMediaInputProperties(
                directory.toString(), false, "renders", cache.toString(), true, 4);
        AnalysisCacheService service = new AnalysisCacheService(new ObjectMapper(), properties);
        RawVideoClipAnalysis expected = new RawVideoClipAnalysis(
                "clip.mp4", 5_000, List.of(), List.of(),
                new AudioProfile(false, 0.0, 0.0, false), 0.8);
        service.save(video, expected);

        Files.writeString(video, "changed-content");

        assertNull(service.load(video));
    }
}
