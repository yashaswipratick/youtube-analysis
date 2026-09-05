package com.youtube.analytics.videoanalysis.analyzer;

import com.youtube.analytics.config.BridgeConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youtube.analytics.videoanalysis.model.SpeechSegment;
import com.youtube.analytics.videoanalysis.service.FfprobeMediaMetadataService;
import com.youtube.analytics.videoanalysis.service.AnalysisRequestExchangeService;
import com.youtube.analytics.videoanalysis.service.MediaToolResolver;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Sends extracted audio to a Bridge adapter which delegates transcription to ChatGPT. */
@Service
public class BridgeSpeechAnalyzer implements SpeechAnalyzer {
    private final WebClient webClient;
    private final BridgeConfig config;
    private final FfprobeMediaMetadataService metadataService;
    private final AnalysisRequestExchangeService requestExchangeService;

    public BridgeSpeechAnalyzer(WebClient.Builder builder, BridgeConfig config,
                                FfprobeMediaMetadataService metadataService, ObjectMapper objectMapper,
                                AnalysisRequestExchangeService requestExchangeService) {
        this.webClient = builder.baseUrl(config.resolvedBaseUrl()).build();
        this.config = config;
        this.metadataService = metadataService;
        this.requestExchangeService = requestExchangeService;
    }

    @Override
    public List<SpeechSegment> transcribe(Path sourceFile) {
        if (!metadataService.probe(sourceFile).audioPresent()) return List.of();
        Path audioFile = extractAudio(sourceFile);
        try {
            Map<String, Object> request = Map.of(
                    "type", "SPEECH_TRANSCRIPTION",
                    "audioBase64", Base64.getEncoder().encodeToString(Files.readAllBytes(audioFile)),
                    "durationMs", metadataService.probe(sourceFile).durationMs());
            requestExchangeService.saveSpeechRequest(sourceFile, metadataService.probe(sourceFile).durationMs(), audioFile);
            return List.of();
        } catch (Exception ex) {
            if (ex instanceof IllegalStateException) throw (IllegalStateException) ex;
            throw new IllegalStateException("Bridge speech analysis failed: " + ex.getMessage(), ex);
        } finally {
            try { Files.deleteIfExists(audioFile); } catch (Exception ignored) { }
        }
    }

    private Path extractAudio(Path sourceFile) {
        try {
            Path output = Files.createTempFile("youtube-analysis-bridge-", ".mp3");
            ProcessBuilder pb = new ProcessBuilder(MediaToolResolver.resolve("ffmpeg"), "-y", "-i",
                    sourceFile.toAbsolutePath().toString(), "-vn", "-ac", "1", "-ar", "16000",
                    "-codec:a", "libmp3lame", "-b:a", "64k", output.toAbsolutePath().toString());
            pb.redirectErrorStream(true);
            Process process = pb.start();
            process.getInputStream().readAllBytes();
            if (!process.waitFor(5, TimeUnit.MINUTES)) {
                process.destroyForcibly(); Files.deleteIfExists(output);
                throw new IllegalStateException("ffmpeg timed out while extracting audio for Bridge");
            }
            if (process.exitValue() != 0) {
                Files.deleteIfExists(output);
                throw new IllegalStateException("ffmpeg failed while extracting audio for Bridge: exit " + process.exitValue());
            }
            return output;
        } catch (Exception ex) {
            if (ex instanceof IllegalStateException) throw (IllegalStateException) ex;
            throw new IllegalStateException("Unable to execute ffmpeg for Bridge speech analysis", ex);
        }
    }
}
