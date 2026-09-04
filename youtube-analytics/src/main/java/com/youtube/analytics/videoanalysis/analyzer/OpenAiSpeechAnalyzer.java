package com.youtube.analytics.videoanalysis.analyzer;

import com.fasterxml.jackson.databind.JsonNode;
import com.youtube.analytics.config.OpenAiApiKeyProvider;
import com.youtube.analytics.config.OpenAiConfig;
import com.youtube.analytics.videoanalysis.model.SpeechSegment;
import com.youtube.analytics.videoanalysis.service.FfprobeMediaMetadataService;
import com.youtube.analytics.videoanalysis.service.MediaToolResolver;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.file.Path;
import java.util.List;

@Service
public class OpenAiSpeechAnalyzer implements SpeechAnalyzer {

    private final WebClient webClient;
    private final OpenAiConfig config;
    private final OpenAiApiKeyProvider apiKeyProvider;
    private final FfprobeMediaMetadataService metadataService;

    public OpenAiSpeechAnalyzer(WebClient.Builder webClientBuilder,
                                OpenAiConfig config,
                                OpenAiApiKeyProvider apiKeyProvider,
                                FfprobeMediaMetadataService metadataService) {
        this.webClient = webClientBuilder.baseUrl(config.resolvedBaseUrl()).build();
        this.config = config;
        this.apiKeyProvider = apiKeyProvider;
        this.metadataService = metadataService;
    }

    @Override
    public List<SpeechSegment> transcribe(Path sourceFile) {
        Path audioFile = extractAudio(sourceFile);
        try {
            MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
            form.add("model", config.resolvedTranscriptionModel());
            form.add("response_format", "json");
            form.add("file", new FileSystemResource(audioFile));

            JsonNode response = webClient.post()
                    .uri("/audio/transcriptions")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .headers(headers -> headers.setBearerAuth(apiKeyProvider.getApiKey()))
                    .body(BodyInserters.fromMultipartData(form))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            String text = response == null ? "" : response.path("text").asText("").trim();
            if (text.isBlank()) return List.of();
            long durationMs = metadataService.probe(sourceFile).durationMs();
            return List.of(new SpeechSegment(0, durationMs, text, 1.0));
        } finally {
            try {
                java.nio.file.Files.deleteIfExists(audioFile);
            } catch (java.io.IOException ignored) {
                // Best-effort cleanup of the temporary derived audio file.
            }
        }
    }

    private Path extractAudio(Path sourceFile) {
        try {
            Path output = java.nio.file.Files.createTempFile("youtube-analysis-", ".mp3");
            ProcessBuilder processBuilder = new ProcessBuilder(
                    MediaToolResolver.resolve("ffmpeg"), "-y", "-i", sourceFile.toAbsolutePath().toString(),
                    "-vn", "-ac", "1", "-ar", "16000", "-codec:a", "libmp3lame", "-b:a", "64k",
                    output.toAbsolutePath().toString());
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();
            String outputText = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            if (!process.waitFor(5, java.util.concurrent.TimeUnit.MINUTES)) {
                process.destroyForcibly();
                java.nio.file.Files.deleteIfExists(output);
                throw new IllegalStateException("ffmpeg timed out while extracting audio");
            }
            if (process.exitValue() != 0) {
                java.nio.file.Files.deleteIfExists(output);
                throw new IllegalStateException("ffmpeg failed while extracting audio: exit " + process.exitValue());
            }
            return output;
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("Unable to execute ffmpeg; install ffmpeg and ensure it is on PATH", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while extracting audio", ex);
        }
    }
}
