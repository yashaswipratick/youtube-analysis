package com.youtube.analytics.videoanalysis.model;

import com.youtube.analytics.videoanalysis.ingestion.MediaFileType;

public record MediaReadAnalysis(
        String fileName,
        String relativePath,
        MediaFileType type,
        long sizeBytes,
        String contentType,
        String sha256,
        Integer imageWidth,
        Integer imageHeight) {
}
