package com.youtube.analytics.videoanalysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youtube.analytics.videoanalysis.ingestion.LocalMediaInputProperties;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalysisHandoffManifestServiceTest {

    @Test
    void createsManifestWithDeterministicAnalysisAndCacheMapping() throws Exception {
        Path root = Files.createTempDirectory("analysis-manifest-test");
        Path source = root.resolve("DJI test clip.MP4");
        Files.writeString(source, "video-content");
        Path analysis = root.resolve("analysis");
        Path cache = root.resolve("analysis-cache");
        Path manifests = root.resolve("analysis-manifest");
        LocalMediaInputProperties properties = new LocalMediaInputProperties(
                root.resolve("input").toString(), false, "renders", cache.toString(), analysis.toString(),
                manifests.toString(), true, 4);

        String sha256 = "b"; // placeholder replaced below by the service's deterministic value
        Path expectedCache = cache.resolve("" + sha256 + ".json");
        // The manifest derives the real cache filename from the source bytes, so create only the analysis artifact here.
        Files.createDirectories(analysis);
        Files.writeString(analysis.resolve("DJI_test_clip.MP4.json"), "{}");

        new AnalysisHandoffManifestService(new ObjectMapper(), properties).save(source);

        Path manifest = manifests.resolve("DJI_test_clip.MP4.json");
        assertTrue(Files.isRegularFile(manifest));
        Map<?, ?> value = new ObjectMapper().readValue(manifest.toFile(), Map.class);
        assertEquals("DJI test clip.MP4", value.get("sourceFileName"));
        assertEquals("analysis/DJI_test_clip.MP4.json", value.get("analysisFile"));
        assertEquals("AI_PENDING", value.get("status"));
        assertTrue(String.valueOf(value.get("cacheFile")).startsWith("analysis-cache/"));
        assertTrue(String.valueOf(value.get("cacheFile")).endsWith(".json"));
        assertEquals(64, String.valueOf(value.get("sha256")).length());
        assertEquals("analysis-cache/" + value.get("sha256") + ".json", value.get("cacheFile"));
    }
}
