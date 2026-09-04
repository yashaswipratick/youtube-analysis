package com.youtube.analytics.videoanalysis.model;

import java.time.Instant;

public record EditJob(String jobId, String projectId, Status status, int progress, String stage,
                      int discoveredVideos, int eligibleVideos, String outputPath,
                      String error, Instant createdAt, Instant completedAt) {
    public enum Status { QUEUED, RUNNING, COMPLETED, FAILED }
}
