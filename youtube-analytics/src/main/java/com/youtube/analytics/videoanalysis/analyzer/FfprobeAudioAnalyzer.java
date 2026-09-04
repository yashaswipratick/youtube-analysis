package com.youtube.analytics.videoanalysis.analyzer;

import com.youtube.analytics.videoanalysis.model.AudioProfile;
import com.youtube.analytics.videoanalysis.service.FfprobeMediaMetadataService;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

@Service
public class FfprobeAudioAnalyzer implements AudioAnalyzer {

    private final FfprobeMediaMetadataService metadataService;

    public FfprobeAudioAnalyzer(FfprobeMediaMetadataService metadataService) {
        this.metadataService = metadataService;
    }

    @Override
    public AudioProfile analyze(Path sourceFile) {
        FfprobeMediaMetadataService.VideoMetadata metadata = metadataService.probe(sourceFile);
        return new AudioProfile(metadata.audioPresent(), metadata.audioPresent() ? 0.5 : 0.0, 0.0, false);
    }
}
