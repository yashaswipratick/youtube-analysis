package com.youtube.analytics.videoanalysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youtube.analytics.videoanalysis.ingestion.LocalMediaInputProperties;
import com.youtube.analytics.videoanalysis.model.RawVideoClipAnalysis;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Service
public class AnalysisCacheService {

    private static final int SCHEMA_VERSION = 1;

    private final ObjectMapper objectMapper;
    private final Path cacheDirectory;
    private final boolean enabled;

    public AnalysisCacheService(ObjectMapper objectMapper, LocalMediaInputProperties properties) {
        this.objectMapper = objectMapper;
        this.cacheDirectory = Path.of(properties.analysisCacheDirectory()).toAbsolutePath().normalize();
        this.enabled = properties.analysisCacheEnabled();
    }

    public RawVideoClipAnalysis load(Path sourceFile) {
        if (!enabled) return null;
        try {
            String hash = sha256(sourceFile);
            Path cacheFile = cacheDirectory.resolve(hash + ".json");
            if (!Files.isRegularFile(cacheFile)) return null;
            CacheEntry entry = objectMapper.readValue(cacheFile.toFile(), CacheEntry.class);
            if (entry.schemaVersion() != SCHEMA_VERSION || !hash.equals(entry.sha256())) return null;
            if (!sourceFile.getFileName().toString().equals(entry.sourceFileName())) return null;
            return entry.analysis();
        } catch (Exception ex) {
            return null;
        }
    }

    public void save(Path sourceFile, RawVideoClipAnalysis analysis) {
        if (!enabled) return;
        try {
            Files.createDirectories(cacheDirectory);
            String hash = sha256(sourceFile);
            CacheEntry entry = new CacheEntry(SCHEMA_VERSION, sourceFile.getFileName().toString(), hash,
                    Files.size(sourceFile), analysis.durationMs(), analysis);
            Path target = cacheDirectory.resolve(hash + ".json");
            Path temporary = Files.createTempFile(cacheDirectory, hash + "-", ".tmp");
            try {
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), entry);
                try {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException | NoSuchAlgorithmException ex) {
            // Cache failures must never fail an otherwise valid video analysis.
        }
    }

    private String sha256(Path sourceFile) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(sourceFile)) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest()) result.append(String.format("%02x", value));
        return result.toString();
    }

    public record CacheEntry(int schemaVersion, String sourceFileName, String sha256,
                             long sizeBytes, long durationMs, RawVideoClipAnalysis analysis) {
    }
}
