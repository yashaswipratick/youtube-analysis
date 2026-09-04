package com.youtube.analytics.videoanalysis.sequencing;

import com.youtube.analytics.videoanalysis.model.ClipCandidate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class BenchmarkService {
    private final EditQualityEvaluator evaluator;
    public BenchmarkService(EditQualityEvaluator evaluator) { this.evaluator = evaluator; }

    public BenchmarkResult evaluate(List<ClipCandidate> candidates, long targetDurationMs) {
        double quality = evaluator.score(candidates, targetDurationMs);
        return new BenchmarkResult(quality, candidates == null ? 0 : candidates.size(),
                quality >= 0.70 ? "PASS" : "REVIEW");
    }

    public record BenchmarkResult(double qualityScore, int candidateCount, String recommendation) {}
}
