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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalysisExchangeServiceTest {

    @Test
    void savesAndReadsOneCombinedAnalysisPerVideo() throws Exception {
        Path root = Files.createTempDirectory("analysis-exchange-test-");
        Path analysisDirectory = root.resolve("analysis");
        LocalMediaInputProperties properties = new LocalMediaInputProperties(
                root.toString(), false, "renders", root.resolve("cache").toString(), analysisDirectory.toString(), true, 4);
        AnalysisExchangeService service = new AnalysisExchangeService(new ObjectMapper(), properties);
        Path video = root.resolve("clip one.mp4");
        Files.writeString(video, "video");
        RawVideoClipAnalysis expected = new RawVideoClipAnalysis(
                "clip one.mp4", 5_000, List.of(), List.of(),
                new AudioProfile(true, 0.9, 0.1, false), 0.8);

        service.saveAnalysis(video, expected);

        assertTrue(service.exists(video));
        assertEquals(expected, new ObjectMapper().readValue(service.readAnalysis("clip_one.mp4"), RawVideoClipAnalysis.class));
        try (var files = Files.list(analysisDirectory)) {
            assertEquals(1, files.count());
        }
        assertTrue(service.readAllAnalysis().contains("clip one.mp4"));
    }

    @Test
    void doesNotExposePathTraversalOutsideAnalysisDirectory() throws Exception {
        Path root = Files.createTempDirectory("analysis-exchange-test-");
        Path analysisDirectory = root.resolve("analysis");
        LocalMediaInputProperties properties = new LocalMediaInputProperties(
                root.toString(), false, "renders", root.resolve("cache").toString(), analysisDirectory.toString(), true, 4);
        AnalysisExchangeService service = new AnalysisExchangeService(new ObjectMapper(), properties);

        assertThrows(IllegalArgumentException.class,
                () -> service.readAnalysis("/absolute/secret"));
        assertFalse(Files.exists(root.resolve("secret.json")));
    }
}
