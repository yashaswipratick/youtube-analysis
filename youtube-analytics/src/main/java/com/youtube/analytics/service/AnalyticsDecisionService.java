package com.youtube.analytics.service;

import com.youtube.analytics.model.AnalyticsDecisionResult;
import com.youtube.analytics.model.DiscoveryOptimizationResult;
import com.youtube.analytics.model.RetentionAnalysisResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/** Produces one deterministic, explainable action from the existing analysis signals. */
@Service
@RequiredArgsConstructor
public class AnalyticsDecisionService {

    public AnalyticsDecisionResult decide(DiscoveryOptimizationResult discovery) {
        if (discovery == null) {
            return insufficient(null, List.of("discovery"));
        }

        List<String> missingData = discovery.getMissingData() == null
                ? List.of()
                : List.copyOf(discovery.getMissingData());
        if (discovery.getPrimaryDiagnosis() == DiscoveryOptimizationResult.DiscoveryDiagnosis.INSUFFICIENT_DATA) {
            return insufficient(discovery.getVideoId(), missingData);
        }

        RetentionAnalysisResult.RetentionSeverity retentionSeverity = discovery.getRetentionAnalysis() == null
                ? RetentionAnalysisResult.RetentionSeverity.UNKNOWN
                : discovery.getRetentionAnalysis().overallSeverity();

        if (retentionSeverity == RetentionAnalysisResult.RetentionSeverity.UNKNOWN) {
            return insufficient(discovery.getVideoId(), missingDataWithRetention(missingData));
        }

        if (retentionSeverity == RetentionAnalysisResult.RetentionSeverity.CRITICAL
                || retentionSeverity == RetentionAnalysisResult.RetentionSeverity.WEAK) {
            List<String> evidence = new ArrayList<>();
            evidence.add("Retention severity is " + retentionSeverity + ".");
            if (discovery.getPrimaryDiagnosis() == DiscoveryOptimizationResult.DiscoveryDiagnosis.LOW_CTR) {
                evidence.add("Discovery diagnosis is LOW_CTR, so packaging may also need a secondary experiment.");
            } else if (discovery.getPrimaryDiagnosis() == DiscoveryOptimizationResult.DiscoveryDiagnosis.LOW_REACH_AND_LOW_CTR) {
                evidence.add("Discovery diagnosis is LOW_REACH_AND_LOW_CTR; retention is the higher-priority content signal.");
            }
            return result(discovery, AnalyticsDecisionResult.DecisionAction.CONTENT_RETENTION,
                    "Prioritize content and retention improvements before optimizing discovery packaging.", evidence, missingData);
        }

        return switch (discovery.getPrimaryDiagnosis()) {
            case LOW_CTR -> result(discovery, AnalyticsDecisionResult.DecisionAction.PACKAGING,
                    "Prioritize title and thumbnail experiments because reach is healthy enough while click-through is weak.",
                    List.of("Discovery diagnosis is LOW_CTR.", "Retention is not weak enough to make content the primary action."), missingData);
            case LOW_REACH, LOW_REACH_AND_LOW_CTR -> result(discovery, AnalyticsDecisionResult.DecisionAction.DISTRIBUTION_TOPIC,
                    "Prioritize topic fit and distribution before making packaging the primary intervention.",
                    evidenceForLowReach(discovery), missingData);
            case HEALTHY_DISCOVERY -> healthyDecision(discovery, missingData);
            case INSUFFICIENT_DATA -> insufficient(discovery.getVideoId(), missingData);
        };
    }

    private AnalyticsDecisionResult healthyDecision(DiscoveryOptimizationResult discovery, List<String> missingData) {
        DiscoveryOptimizationResult.ViewVelocity velocity = discovery.getViewVelocity();
        RetentionAnalysisResult.RetentionSeverity retention = discovery.getRetentionAnalysis() == null
                ? RetentionAnalysisResult.RetentionSeverity.UNKNOWN
                : discovery.getRetentionAnalysis().overallSeverity();
        if (retention == RetentionAnalysisResult.RetentionSeverity.STRONG
                || retention == RetentionAnalysisResult.RetentionSeverity.HEALTHY) {
            List<String> evidence = new ArrayList<>();
            evidence.add("Discovery diagnosis is HEALTHY_DISCOVERY.");
            evidence.add("Retention severity is " + retention + ".");
            if (velocity != null && velocity.status() == DiscoveryOptimizationResult.MomentumStatus.ACCELERATING) {
                evidence.add("View momentum is ACCELERATING.");
            }
            return result(discovery, AnalyticsDecisionResult.DecisionAction.CONTINUE_OBSERVING,
                    "Discovery and retention signals are healthy; avoid premature intervention and continue observing performance.",
                    evidence, missingData);
        }
        return result(discovery, AnalyticsDecisionResult.DecisionAction.CONTINUE_OBSERVING,
                "No dominant discovery or retention intervention is supported by the available signals; continue observing.",
                List.of("Discovery diagnosis is HEALTHY_DISCOVERY.", "Retention data is not weak enough to prioritize a content intervention."), missingData);
    }

    private List<String> evidenceForLowReach(DiscoveryOptimizationResult discovery) {
        List<String> evidence = new ArrayList<>();
        evidence.add("Discovery diagnosis is " + discovery.getPrimaryDiagnosis() + ".");
        if (discovery.getImpressionsPerDay() != null) {
            evidence.add("Impressions per day are " + discovery.getImpressionsPerDay() + ".");
        }
        if (discovery.getImpressionsClickThroughRate() != null) {
            evidence.add("CTR is " + discovery.getImpressionsClickThroughRate() + "%.");
        }
        return evidence;
    }

    private List<String> missingDataWithRetention(List<String> missingData) {
        if (missingData.contains("retention")) {
            return missingData;
        }
        List<String> updated = new ArrayList<>(missingData);
        updated.add("retention");
        return List.copyOf(updated);
    }

    private AnalyticsDecisionResult insufficient(String videoId, List<String> missingData) {
        return new AnalyticsDecisionResult(videoId, AnalyticsDecisionResult.DecisionAction.INSUFFICIENT_DATA,
                "There is not enough analytics data to make a reliable primary decision.",
                List.of("Required discovery data is incomplete; do not infer a performance problem yet."), missingData);
    }

    private AnalyticsDecisionResult result(DiscoveryOptimizationResult discovery,
                                           AnalyticsDecisionResult.DecisionAction action,
                                           String summary,
                                           List<String> evidence,
                                           List<String> missingData) {
        return new AnalyticsDecisionResult(
                discovery == null ? null : discovery.getVideoId(), action, summary,
                List.copyOf(evidence), List.copyOf(missingData));
    }
}
