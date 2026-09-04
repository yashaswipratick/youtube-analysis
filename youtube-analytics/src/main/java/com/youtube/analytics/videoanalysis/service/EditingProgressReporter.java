package com.youtube.analytics.videoanalysis.service;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class EditingProgressReporter {
    private static final int BAR_WIDTH = 30;
    private final Map<String, Integer> lastProgressByJob = new ConcurrentHashMap<>();

    public void report(String jobId, int percent, String stage) {
        String reference = jobId == null || jobId.isBlank() ? "sync" : jobId;
        int bounded = Math.max(0, Math.min(100, percent));
        lastProgressByJob.compute(reference, (key, previous) -> {
            if (previous != null && bounded < previous) return previous;
            int filled = (bounded * BAR_WIDTH) / 100;
            String bar = "█".repeat(filled) + "░".repeat(BAR_WIDTH - filled);
            System.out.printf("[EDITING] [jobId=%s] [%s] %3d%%  %s%n", reference, bar, bounded, stage);
            return bounded;
        });
    }

    public void report(int percent, String stage) {
        report("sync", percent, stage);
    }

    public void complete(String jobId, String stage) {
        report(jobId, 100, stage);
    }

    public void complete(String stage) {
        complete("sync", stage);
    }
}
