package com.youtube.analytics.videoanalysis.ingestion;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

@Service
public class MediaApprovalService {

    private final Path inputDirectory;
    private final boolean approvalRequired;
    private final Set<Path> approvedFiles = new HashSet<>();

    public MediaApprovalService(LocalMediaInputProperties properties) {
        if (properties.inputDirectory() == null || properties.inputDirectory().isBlank()) {
            throw new IllegalArgumentException("video-analysis.input-directory must not be blank");
        }
        this.inputDirectory = Path.of(properties.inputDirectory()).toAbsolutePath().normalize();
        this.approvalRequired = properties.approvalRequired();
    }

    public synchronized LocalMediaFile approve(String relativePath) {
        Path file = resolveApprovedPath(relativePath);
        try {
            if (!Files.isRegularFile(file)) {
                throw new IllegalArgumentException("Media file does not exist: " + relativePath);
            }
            approvedFiles.add(file);
            return toMetadata(file);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to read approved media file metadata", ex);
        }
    }

    public synchronized boolean isApproved(String relativePath) {
        return approvedFiles.contains(resolveApprovedPath(relativePath));
    }

    public synchronized Path getApprovedPath(String relativePath) {
        Path file = resolveApprovedPath(relativePath);
        if (approvalRequired && !approvedFiles.contains(file)) {
            throw new IllegalStateException("Media file has not been approved for reading: " + relativePath);
        }
        return file;
    }

    public synchronized Path getPathForRead(String relativePath) {
        return getApprovedPath(relativePath);
    }

    public synchronized Path getPath(String relativePath) {
        return resolveApprovedPath(relativePath);
    }

    public synchronized Set<String> getApprovedRelativePaths() {
        return approvedFiles.stream()
                .map(inputDirectory::relativize)
                .map(Path::toString)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private Path resolveApprovedPath(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("relativePath must not be blank");
        }
        Path resolved = inputDirectory.resolve(relativePath).normalize();
        if (!resolved.startsWith(inputDirectory)) {
            throw new IllegalArgumentException("Media path is outside the configured input directory");
        }
        return resolved;
    }

    private LocalMediaFile toMetadata(Path file) throws IOException {
        String name = file.getFileName().toString();
        String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(java.util.Locale.ROOT);
        MediaFileType type = switch (extension) {
            case "mp4", "mov", "m4v", "avi", "mkv", "webm", "wmv", "flv", "mpeg", "mpg", "3gp" -> MediaFileType.VIDEO;
            case "jpg", "jpeg", "png", "webp", "gif", "bmp", "tif", "tiff", "heic", "heif" -> MediaFileType.IMAGE;
            default -> throw new IllegalArgumentException("Unsupported media file: " + relativePath(file));
        };
        return new LocalMediaFile(name, relativePath(file), type, Files.size(file), Files.getLastModifiedTime(file).toInstant());
    }

    private String relativePath(Path file) {
        return inputDirectory.relativize(file).toString();
    }
}
