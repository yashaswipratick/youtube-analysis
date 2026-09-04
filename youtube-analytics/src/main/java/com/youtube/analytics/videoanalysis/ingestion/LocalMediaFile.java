package com.youtube.analytics.videoanalysis.ingestion;

import java.time.Instant;

public record LocalMediaFile(
        String fileName,
        String relativePath,
        MediaFileType type,
        long sizeBytes,
        Instant lastModified) {
}
