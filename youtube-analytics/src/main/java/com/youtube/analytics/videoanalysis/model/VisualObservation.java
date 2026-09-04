package com.youtube.analytics.videoanalysis.model;

import java.util.List;

public record VisualObservation(String summary, List<String> objects, String environment, double qualityScore) {
    public VisualObservation {
        if (qualityScore < 0 || qualityScore > 1) throw new IllegalArgumentException("qualityScore must be between 0 and 1");
        objects = objects == null ? List.of() : List.copyOf(objects);
    }
}
