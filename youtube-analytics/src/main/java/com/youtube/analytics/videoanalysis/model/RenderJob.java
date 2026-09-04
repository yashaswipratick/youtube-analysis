package com.youtube.analytics.videoanalysis.model;

import java.time.Instant;

public record RenderJob(String jobId, String projectId, Status status, String outputPath,
                        String error, Instant createdAt, Instant completedAt) {
    public enum Status { QUEUED, RUNNING, COMPLETED, FAILED }
}
