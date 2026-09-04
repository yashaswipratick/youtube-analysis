package com.youtube.analytics.videoanalysis.ingestion;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

@Service
public class MediaDiscoveryService {

    private static final Map<MediaFileType, Set<String>> SUPPORTED_EXTENSIONS = Map.of(
            MediaFileType.VIDEO, Set.of("mp4", "mov", "m4v", "avi", "mkv", "webm", "wmv", "flv", "mpeg", "mpg", "3gp"),
            MediaFileType.IMAGE, Set.of("jpg", "jpeg", "png", "webp", "gif", "bmp", "tif", "tiff", "heic", "heif")
    );

    private final Path inputDirectory;
    private final Path outputDirectory;

    public MediaDiscoveryService(LocalMediaInputProperties properties) {
        if (properties.inputDirectory() == null || properties.inputDirectory().isBlank()) {
            throw new IllegalArgumentException("video-analysis.input-directory must not be blank");
        }
        this.inputDirectory = Path.of(properties.inputDirectory()).toAbsolutePath().normalize();
        this.outputDirectory = properties.outputDirectory() == null || properties.outputDirectory().isBlank()
                ? null
                : Path.of(properties.outputDirectory()).toAbsolutePath().normalize();
    }

    public List<LocalMediaFile> discover() {
        if (!Files.isDirectory(inputDirectory)) {
            throw new IllegalStateException("Configured video-analysis input directory does not exist: " + inputDirectory);
        }

        try (Stream<Path> paths = Files.walk(inputDirectory)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> outputDirectory == null || !path.startsWith(outputDirectory))
                    .map(this::toMediaFile)
                    .filter(java.util.Objects::nonNull)
                    .sorted(Comparator.comparing(LocalMediaFile::relativePath))
                    .toList();
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to discover media files from configured input directory", ex);
        }
    }

    private LocalMediaFile toMediaFile(Path path) {
        MediaFileType type = detectType(path);
        if (type == null) {
            return null;
        }
        try {
            return new LocalMediaFile(
                    path.getFileName().toString(),
                    inputDirectory.relativize(path).toString(),
                    type,
                    Files.size(path),
                    Files.getLastModifiedTime(path).toInstant());
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to read media file metadata: " + path.getFileName(), ex);
        }
    }

    private MediaFileType detectType(Path path) {
        String fileName = path.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return null;
        }
        String extension = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return SUPPORTED_EXTENSIONS.entrySet().stream()
                .filter(entry -> entry.getValue().contains(extension))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }
}
