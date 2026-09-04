package com.youtube.analytics.videoanalysis.service;

import com.youtube.analytics.videoanalysis.ingestion.MediaApprovalService;
import com.youtube.analytics.videoanalysis.ingestion.MediaFileType;
import com.youtube.analytics.videoanalysis.model.MediaReadAnalysis;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

@Service
public class ApprovedMediaReadService {

    private final MediaApprovalService approvalService;

    public ApprovedMediaReadService(MediaApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    public MediaReadAnalysis readAndAnalyze(String relativePath) {
        Path file = approvalService.getApprovedPath(relativePath);
        try {
            if (!Files.isRegularFile(file)) {
                throw new IllegalArgumentException("Approved media file does not exist: " + relativePath);
            }

            MediaFileType type = detectType(file);
            String contentType = Files.probeContentType(file);
            long sizeBytes = Files.size(file);
            String sha256 = sha256(file);

            Integer width = null;
            Integer height = null;
            if (type == MediaFileType.IMAGE) {
                BufferedImage image = ImageIO.read(file.toFile());
                if (image == null) {
                    throw new IllegalStateException("Unable to decode approved image: " + relativePath);
                }
                width = image.getWidth();
                height = image.getHeight();
            }

            return new MediaReadAnalysis(
                    file.getFileName().toString(),
                    relativePath,
                    type,
                    sizeBytes,
                    contentType,
                    sha256,
                    width,
                    height);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to read approved media file: " + relativePath, ex);
        }
    }

    private MediaFileType detectType(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            throw new IllegalArgumentException("Unsupported media file: " + name);
        }
        return switch (name.substring(dot + 1).toLowerCase(Locale.ROOT)) {
            case "mp4", "mov", "m4v", "avi", "mkv", "webm", "wmv", "flv", "mpeg", "mpg", "3gp" -> MediaFileType.VIDEO;
            case "jpg", "jpeg", "png", "webp", "gif", "bmp", "tif", "tiff", "heic", "heif" -> MediaFileType.IMAGE;
            default -> throw new IllegalArgumentException("Unsupported media file: " + name);
        };
    }

    private String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
