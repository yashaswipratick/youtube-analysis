package com.youtube.analytics.videoanalysis.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class EditingProgressReporterTest {
    @Test
    void acceptsProgressAndNeverThrowsForOutOfRangeValues() {
        EditingProgressReporter reporter = new EditingProgressReporter();
        assertDoesNotThrow(() -> {
            reporter.report(-10, "starting");
            reporter.report(25, "analysis");
            reporter.report(10, "ignored regression");
            reporter.complete("completed");
            reporter.report(110, "completed");
        });
    }
}
