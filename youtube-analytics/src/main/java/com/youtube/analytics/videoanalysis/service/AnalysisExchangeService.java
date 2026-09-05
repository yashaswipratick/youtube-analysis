package com.youtube.analytics.videoanalysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youtube.analytics.videoanalysis.ingestion.LocalMediaInputProperties;
import com.youtube.analytics.videoanalysis.model.RawVideoClipAnalysis;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

@Service
public class AnalysisExchangeService {

    private final ObjectMapper objectMapper;
    private final Path analysisDirectory;

    public AnalysisExchangeService(ObjectMapper objectMapper, LocalMediaInputProperties properties) {
        this.objectMapper = objectMapper;
        this.analysisDirectory = Path.of(properties.analysisDirectory()).toAbsolutePath().normalize();
    }

    public void saveAnalysis(Path sourceFile, RawVideoClipAnalysis analysis) {
        write(sourceFile, analysis);
    }

    public String readAnalysis(String fileName) {
        return read(fileName);
    }

    public String readAllAnalysis() {
        return readDirectory();
    }

    public boolean exists(Path sourceFile) {
        String fileName = safeName(sourceFile.getFileName().toString()) + ".json";
        return Files.isRegularFile(safeResolve(analysisDirectory, fileName));
    }

    private void write(Path sourceFile, RawVideoClipAnalysis analysis) {
        try {
            Files.createDirectories(analysisDirectory);
            String fileName = safeName(sourceFile.getFileName().toString()) + ".json";
            Path target = safeResolve(analysisDirectory, fileName);
            Path temporary = Files.createTempFile(analysisDirectory, fileName + "-", ".tmp");
            try {
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), analysis);
                try {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to persist analysis exchange data", ex);
        }
    }

    private String read(String fileName) {
        Path file = safeResolve(analysisDirectory, fileName);
        try {
            if (!Files.isRegularFile(file)) {
                throw new IllegalArgumentException("Analysis file not found: " + fileName);
            }
            return Files.readString(file);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to read analysis file: " + fileName, ex);
        }
    }

    private String readDirectory() {
        try {
            Files.createDirectories(analysisDirectory);
            try (var files = Files.list(analysisDirectory)) {
                List<String> values = files
                        .filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".json"))
                        .sorted()
                        .map(path -> {
                            try {
                                return Files.readString(path);
                            } catch (IOException ex) {
                                throw new IllegalStateException("Unable to read analysis file: " + path.getFileName(), ex);
                            }
                        })
                        .toList();
                return objectMapper.writeValueAsString(values.stream().map(value -> {
                    try {
                        return objectMapper.readTree(value);
                    } catch (IOException ex) {
                        throw new IllegalStateException("Invalid analysis JSON", ex);
                    }
                }).toList());
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to list analysis directory", ex);
        }
    }

    private Path safeResolve(Path directory, String fileName) {
        String normalized = safeName(fileName);
        if (!normalized.endsWith(".json")) normalized += ".json";
        Path resolved = directory.resolve(normalized).normalize();
        if (!resolved.getParent().equals(directory)) {
            throw new IllegalArgumentException("Invalid analysis file name");
        }
        return resolved;
    }

    private String safeName(String value) {
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
