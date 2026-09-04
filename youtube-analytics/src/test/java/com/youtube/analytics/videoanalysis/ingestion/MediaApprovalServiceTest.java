package com.youtube.analytics.videoanalysis.ingestion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaApprovalServiceTest {

    @TempDir
    Path inputDirectory;

    @Test
    void unapprovedFileCannotBeResolvedForMediaReading() throws Exception {
        Path video = inputDirectory.resolve("clip.mp4");
        Files.writeString(video, "video-bytes");
        MediaApprovalService service = new MediaApprovalService(new LocalMediaInputProperties(inputDirectory.toString(), true));

        assertThrows(IllegalStateException.class, () -> service.getApprovedPath("clip.mp4"));
    }

    @Test
    void approvedFileCanBeResolvedForLaterMediaReading() throws Exception {
        Path video = inputDirectory.resolve("clip.mp4");
        Files.writeString(video, "video-bytes");
        MediaApprovalService service = new MediaApprovalService(new LocalMediaInputProperties(inputDirectory.toString(), true));

        assertFalse(service.isApproved("clip.mp4"));
        LocalMediaFile approved = service.approve("clip.mp4");

        assertEquals("clip.mp4", approved.relativePath());
        assertTrue(service.isApproved("clip.mp4"));
        assertEquals(video.toAbsolutePath().normalize(), service.getApprovedPath("clip.mp4"));
    }

    @Test
    void approvesUppercaseMp4ExtensionAsVideo() throws Exception {
        Path video = inputDirectory.resolve("clip.MP4");
        Files.writeString(video, "video-bytes");
        MediaApprovalService service = new MediaApprovalService(new LocalMediaInputProperties(inputDirectory.toString(), true));

        LocalMediaFile approved = service.approve("clip.MP4");

        assertEquals("clip.MP4", approved.fileName());
        assertEquals("clip.MP4", approved.relativePath());
        assertEquals(MediaFileType.VIDEO, approved.type());
        assertTrue(service.isApproved("clip.MP4"));
        assertEquals(video.toAbsolutePath().normalize(), service.getApprovedPath("clip.MP4"));
    }

    @Test
    void rejectsPathTraversalOutsideConfiguredDirectory() {
        MediaApprovalService service = new MediaApprovalService(new LocalMediaInputProperties(inputDirectory.toString(), true));

        assertThrows(IllegalArgumentException.class, () -> service.approve("../outside.mp4"));
    }

    @Test
    void rejectsUnsupportedMediaType() throws Exception {
        Files.writeString(inputDirectory.resolve("notes.txt"), "not-media");
        MediaApprovalService service = new MediaApprovalService(new LocalMediaInputProperties(inputDirectory.toString(), true));

        assertThrows(IllegalArgumentException.class, () -> service.approve("notes.txt"));
    }
}
