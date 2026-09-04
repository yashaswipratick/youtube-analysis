package com.youtube.analytics.videoanalysis.ingestion;

import com.youtube.analytics.videoanalysis.model.MediaReadAnalysis;
import com.youtube.analytics.videoanalysis.service.ApprovedMediaReadService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApprovedMediaReadServiceTest {

    @TempDir
    Path inputDirectory;

    @Test
    void readsAndAnalyzesApprovedImage() throws IOException {
        BufferedImage image = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB);
        Path file = inputDirectory.resolve("photo.jpg");
        ImageIO.write(image, "jpg", file.toFile());

        MediaApprovalService approvalService = new MediaApprovalService(new LocalMediaInputProperties(inputDirectory.toString()));
        approvalService.approve("photo.jpg");

        MediaReadAnalysis result = new ApprovedMediaReadService(approvalService).readAndAnalyze("photo.jpg");

        assertEquals("photo.jpg", result.fileName());
        assertEquals(MediaFileType.IMAGE, result.type());
        assertEquals(3, result.imageWidth());
        assertEquals(2, result.imageHeight());
        assertNotNull(result.sha256());
        assertEquals(64, result.sha256().length());
    }

    @Test
    void refusesToReadUnapprovedMedia() throws IOException {
        Files.writeString(inputDirectory.resolve("clip.mp4"), "video");

        MediaApprovalService approvalService = new MediaApprovalService(new LocalMediaInputProperties(inputDirectory.toString()));

        assertThrows(IllegalStateException.class,
                () -> new ApprovedMediaReadService(approvalService).readAndAnalyze("clip.mp4"));
    }
}
