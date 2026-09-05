package com.youtube.analytics.videoanalysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youtube.analytics.videoanalysis.ingestion.LocalMediaInputProperties;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Persists the deterministic file-to-cache mapping used by the out-of-band Bridge worker. */
@Service
public class AnalysisHandoffManifestService {

    private static final String AI_PENDING = "AI_PENDING";
    private static final String AI_COMPLETE = "AI_COMPLETE";

    private final ObjectMapper objectMapper;
    private final Path analysisDirectory;
    private final Path cacheDirectory;
    private final Path manifestDirectory;

    public AnalysisHandoffManifestService(ObjectMapper objectMapper, LocalMediaInputProperties properties) {
        this.objectMapper = objectMapper;
        this.analysisDirectory = Path.of(properties.analysisDirectory()).toAbsolutePath().normalize();
        this.cacheDirectory = Path.of(properties.analysisCacheDirectory()).toAbsolutePath().normalize();
        this.manifestDirectory = Path.of(properties.analysisManifestDirectory()).toAbsolutePath().normalize();
    }

    public void save(Path sourceFile) {
        try {
            String sourceFileName = sourceFile.getFileName().toString();
            String safeName = safeName(sourceFileName);
            String sha256 = sha256(sourceFile);
            Path analysisFile = analysisDirectory.resolve(safeName + ".json");
            Path cacheFile = cacheDirectory.resolve(sha256 + ".json");
            String status = readCacheStatus(cacheFile);

            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("sourceFileName", sourceFileName);
            manifest.put("sha256", sha256);
            manifest.put("analysisFile", relativePath(analysisFile));
            manifest.put("cacheFile", relativePath(cacheFile));
            manifest.put("status", status);

            Files.createDirectories(manifestDirectory);
            Path target = manifestDirectory.resolve(safeName + ".json").normalize();
            Path temporary = Files.createTempFile(manifestDirectory, safeName + "-", ".tmp");
            try {
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), manifest);
                try {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Unable to persist analysis handoff manifest", ex);
        }
    }

    private String readCacheStatus(Path cacheFile) throws IOException {
        if (!Files.isRegularFile(cacheFile)) return AI_PENDING;
        Map<?, ?> cache = objectMapper.readValue(cacheFile.toFile(), Map.class);
        Object status = cache.get("status");
        return AI_COMPLETE.equals(status) ? AI_COMPLETE : AI_PENDING;
    }

    private String relativePath(Path file) {
        Path root = manifestDirectory.getParent();
        if (root != null) {
            return root.relativize(file).toString();
        }
        return file.toString();
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

    private String safeName(String value) {
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
