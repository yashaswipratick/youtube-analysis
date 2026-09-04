package com.youtube.analytics.videoanalysis.ingestion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MediaDiscoveryServiceTest {

    @TempDir
    Path inputDirectory;

    @Test
    void discoversSupportedVideosAndImagesRecursivelyWithoutReadingMediaContent() throws Exception {
        Files.createDirectories(inputDirectory.resolve("travel"));
        Files.writeString(inputDirectory.resolve("travel/clip.MP4"), "video-bytes");
        Files.writeString(inputDirectory.resolve("photo.JPG"), "image-bytes");
        Files.writeString(inputDirectory.resolve("notes.txt"), "ignore");

        MediaDiscoveryService service = new MediaDiscoveryService(new LocalMediaInputProperties(inputDirectory.toString(), true));

        List<LocalMediaFile> files = service.discover();

        assertEquals(2, files.size());
        assertEquals("photo.JPG", files.get(0).fileName());
        assertEquals(MediaFileType.IMAGE, files.get(0).type());
        assertEquals("travel/clip.MP4", files.get(1).relativePath());
        assertEquals(MediaFileType.VIDEO, files.get(1).type());
        assertEquals(Files.size(inputDirectory.resolve("photo.JPG")), files.get(0).sizeBytes());
        assertEquals(Instant.class, files.get(0).lastModified().getClass());
    }

    @Test
    void rejectsMissingInputDirectory() {
        Path missingDirectory = inputDirectory.resolve("missing");
        MediaDiscoveryService service = new MediaDiscoveryService(new LocalMediaInputProperties(missingDirectory.toString(), true));

        assertThrows(IllegalStateException.class, service::discover);
    }
}
