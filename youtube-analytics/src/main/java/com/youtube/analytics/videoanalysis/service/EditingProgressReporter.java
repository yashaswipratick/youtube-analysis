package com.youtube.analytics.videoanalysis.service;

import org.springframework.stereotype.Service;

@Service
public class EditingProgressReporter {
    private static final int BAR_WIDTH = 30;
    private int lastProgress = -1;

    public synchronized void report(int percent, String stage) {
        int bounded = Math.max(0, Math.min(100, percent));
        if (bounded < lastProgress) return;
        lastProgress = bounded;
        int filled = (bounded * BAR_WIDTH) / 100;
        String bar = "█".repeat(filled) + "░".repeat(BAR_WIDTH - filled);
        System.out.printf("[EDITING] [%s] %3d%%  %s%n", bar, bounded, stage);
    }

    public synchronized void complete(String stage) { report(100, stage); }
}
