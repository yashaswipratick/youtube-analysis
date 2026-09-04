package com.youtube.analytics.videoanalysis.analyzer;

import com.youtube.analytics.videoanalysis.model.AudioProfile;
import com.youtube.analytics.videoanalysis.model.RawVideoClipAnalysis;
import com.youtube.analytics.videoanalysis.model.SceneSegment;
import com.youtube.analytics.videoanalysis.service.FfprobeMediaMetadataService;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;

@Service
public class FfprobeVideoAnalyzer implements VideoAnalyzer {

    private final FfprobeMediaMetadataService metadataService;

    public FfprobeVideoAnalyzer(FfprobeMediaMetadataService metadataService) {
        this.metadataService = metadataService;
    }

    @Override
    public RawVideoClipAnalysis analyze(Path sourceFile) {
        FfprobeMediaMetadataService.VideoMetadata metadata = metadataService.probe(sourceFile);
        String summary = buildSummary(metadata);
        List<SceneSegment> scenes = metadata.durationMs() == 0
                ? List.of()
                : List.of(new SceneSegment(0, metadata.durationMs(), summary, visualQuality(metadata)));
        return new RawVideoClipAnalysis(sourceFile.getFileName().toString(), metadata.durationMs(), scenes,
                List.of(), new AudioProfile(false, 0.0, 0.0, false), visualQuality(metadata));
    }

    private String buildSummary(FfprobeMediaMetadataService.VideoMetadata metadata) {
        String dimensions = metadata.width() == null || metadata.height() == null
                ? "unknown dimensions"
                : metadata.width() + "x" + metadata.height();
        String frameRate = metadata.frameRate() == null || metadata.frameRate().isBlank()
                ? "unknown frame rate"
                : metadata.frameRate() + " fps source rate";
        return "Video media, " + dimensions + ", " + frameRate;
    }

    private double visualQuality(FfprobeMediaMetadataService.VideoMetadata metadata) {
        if (metadata.width() == null || metadata.height() == null) return 0.5;
        long pixels = (long) metadata.width() * metadata.height();
        if (pixels >= 3840L * 2160L) return 1.0;
        if (pixels >= 1920L * 1080L) return 0.9;
        if (pixels >= 1280L * 720L) return 0.75;
        return 0.6;
    }
}
