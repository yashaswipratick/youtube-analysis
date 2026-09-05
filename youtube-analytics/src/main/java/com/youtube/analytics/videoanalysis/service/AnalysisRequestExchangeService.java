package com.youtube.analytics.videoanalysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youtube.analytics.config.OpenAiConfig;
import com.youtube.analytics.videoanalysis.ingestion.LocalMediaInputProperties;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Persists the OpenAI request bodies locally for out-of-band Bridge/ChatGPT analysis. */
@Service
public class AnalysisRequestExchangeService {
    private final ObjectMapper objectMapper;
    private final OpenAiConfig openAiConfig;
    private final Path analysisDirectory;
    private final Map<String, RequestDocument> documents = new LinkedHashMap<>();

    public AnalysisRequestExchangeService(ObjectMapper objectMapper, OpenAiConfig openAiConfig,
                                          LocalMediaInputProperties properties) {
        this.objectMapper = objectMapper;
        this.openAiConfig = openAiConfig;
        this.analysisDirectory = Path.of(properties.analysisDirectory()).toAbsolutePath().normalize();
    }

    public synchronized void saveVisualRequest(Path sourceFile, long timestampMs, Path imageFile) {
        try {
            byte[] bytes = Files.readAllBytes(imageFile);
            if (bytes.length == 0) throw new IllegalArgumentException("Visual analysis image is empty: " + imageFile);
            String contentType = Files.probeContentType(imageFile);
            if (contentType == null || !SUPPORTED_IMAGES.contains(contentType.toLowerCase())) contentType = "image/jpeg";
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", openAiConfig.resolvedModel());
            body.put("input", List.of(Map.of("role", "user", "content", List.of(
                    Map.of("type", "input_text", "text", visualPrompt()),
                    Map.of("type", "input_image", "image_url", "data:" + contentType + ";base64," + Base64.getEncoder().encodeToString(bytes))
            ))));
            body.put("text", Map.of("format", Map.of(
                    "type", "json_schema", "name", "video_frame_observation", "strict", true,
                    "schema", visualResponseSchema())));
            document(sourceFile).videoRequests().add(new VideoRequest(timestampMs, body));
            persist(sourceFile);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to persist visual analysis request", ex);
        }
    }

    public synchronized void saveSpeechRequest(Path sourceFile, long durationMs, Path audioFile) {
        try {
            byte[] bytes = Files.readAllBytes(audioFile);
            if (bytes.length == 0) throw new IllegalArgumentException("Speech analysis audio is empty: " + audioFile);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", openAiConfig.resolvedTranscriptionModel());
            body.put("response_format", "json");
            body.put("file", Map.of("filename", audioFile.getFileName().toString(), "contentType", "audio/mpeg",
                    "base64", Base64.getEncoder().encodeToString(bytes)));
            body.put("durationMs", durationMs);
            document(sourceFile).speechRequests().add(new SpeechRequest(body));
            persist(sourceFile);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to persist speech analysis request", ex);
        }
    }

    private RequestDocument document(Path sourceFile) {
        return documents.computeIfAbsent(sourceFile.getFileName().toString(), key -> new RequestDocument(key, new ArrayList<>(), new ArrayList<>()));
    }

    private void persist(Path sourceFile) {
        try {
            Files.createDirectories(analysisDirectory);
            String fileName = safeName(sourceFile.getFileName().toString()) + ".json";
            Path target = analysisDirectory.resolve(fileName).normalize();
            Path temporary = Files.createTempFile(analysisDirectory, fileName + "-", ".tmp");
            try {
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), document(sourceFile));
                try { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
                catch (java.nio.file.AtomicMoveNotSupportedException ex) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
            } finally { Files.deleteIfExists(temporary); }
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to persist analysis request exchange data", ex);
        }
    }

    private String visualPrompt() {
        return "Analyze this representative frame from a raw travel/video clip. Describe only visible evidence. "
                + "Return a concise factual summary of the scene, the main visible objects or subjects, and the visible environment. "
                + "Assess visual quality from 0 to 1 based on framing, clarity, exposure, stability, and usefulness as a video-editing shot. "
                + "Do not infer events, audio, speech, identity, location, or intent that cannot be established from the frame. "
                + "Prefer editing-relevant descriptions such as driving POV, road, mountain landscape, people, food, building, interior, landscape, or close-up when visibly supported.";
    }

    private Map<String, Object> visualResponseSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object"); schema.put("additionalProperties", false);
        schema.put("properties", Map.of("summary", Map.of("type", "string"), "objects", Map.of("type", "array", "items", Map.of("type", "string")),
                "environment", Map.of("type", "string"), "qualityScore", Map.of("type", "number", "minimum", 0, "maximum", 1)));
        schema.put("required", List.of("summary", "objects", "environment", "qualityScore"));
        return schema;
    }

    public RequestDocument readRequestDocument(String fileName) {
        try {
            return objectMapper.readValue(readFile(fileName), RequestDocument.class);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to parse analysis request exchange data: " + fileName, ex);
        }
    }

    public String readAnalysis(String fileName) {
        return readFile(fileName);
    }

    public String readAllAnalysis() {
        try {
            Files.createDirectories(analysisDirectory);
            List<Object> values = new ArrayList<>();
            try (var files = Files.list(analysisDirectory)) {
                for (Path file : files.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".json"))
                        .sorted().toList()) {
                    values.add(objectMapper.readTree(Files.readString(file)));
                }
            }
            return objectMapper.writeValueAsString(values);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to read analysis request exchange data", ex);
        }
    }

    private String readFile(String fileName) {
        Path file = safeResolve(fileName);
        try {
            if (!Files.isRegularFile(file)) {
                throw new IllegalArgumentException("Analysis file not found: " + fileName);
            }
            return Files.readString(file);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to read analysis file: " + fileName, ex);
        }
    }

    private Path safeResolve(String fileName) {
        String normalized = safeName(fileName);
        if (!normalized.endsWith(".json")) normalized += ".json";
        Path resolved = analysisDirectory.resolve(normalized).normalize();
        if (!resolved.getParent().equals(analysisDirectory)) {
            throw new IllegalArgumentException("Invalid analysis file name");
        }
        return resolved;
    }

    private String safeName(String value) { return value.replaceAll("[^a-zA-Z0-9._-]", "_"); }
    private static final java.util.Set<String> SUPPORTED_IMAGES = java.util.Set.of("image/jpeg", "image/png", "image/webp");

    public record RequestDocument(String sourceFileName, List<VideoRequest> videoRequests, List<SpeechRequest> speechRequests) { }
    public record VideoRequest(long timestampMs, Map<String, Object> body) { }
    public record SpeechRequest(Map<String, Object> body) { }
}
