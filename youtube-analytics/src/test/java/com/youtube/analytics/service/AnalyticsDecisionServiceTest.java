package com.youtube.analytics.service;

import com.youtube.analytics.model.AnalyticsDecisionResult;
import com.youtube.analytics.model.DiscoveryOptimizationResult;
import com.youtube.analytics.model.RetentionAnalysisResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnalyticsDecisionServiceTest {

    private final AnalyticsDecisionService service = new AnalyticsDecisionService();

    @Test
    void returnsInsufficientDataWhenDiscoveryDataIsIncomplete() {
        DiscoveryOptimizationResult discovery = discovery(
                DiscoveryOptimizationResult.DiscoveryDiagnosis.INSUFFICIENT_DATA,
                RetentionAnalysisResult.RetentionSeverity.HEALTHY,
                null,
                List.of("impressions", "impressionsClickThroughRate"));

        AnalyticsDecisionResult result = service.decide(discovery);

        assertThat(result.action()).isEqualTo(AnalyticsDecisionResult.DecisionAction.INSUFFICIENT_DATA);
        assertThat(result.missingData()).containsExactly("impressions", "impressionsClickThroughRate");
    }

    @Test
    void prioritizesPackagingWhenCtrIsLowButRetentionIsHealthy() {
        DiscoveryOptimizationResult discovery = discovery(
                DiscoveryOptimizationResult.DiscoveryDiagnosis.LOW_CTR,
                RetentionAnalysisResult.RetentionSeverity.HEALTHY,
                null,
                List.of());

        AnalyticsDecisionResult result = service.decide(discovery);

        assertThat(result.action()).isEqualTo(AnalyticsDecisionResult.DecisionAction.PACKAGING);
        assertThat(result.summary()).contains("title and thumbnail");
    }

    @Test
    void prioritizesDistributionWhenReachIsLowAndRetentionIsHealthy() {
        DiscoveryOptimizationResult discovery = discovery(
                DiscoveryOptimizationResult.DiscoveryDiagnosis.LOW_REACH,
                RetentionAnalysisResult.RetentionSeverity.HEALTHY,
                null,
                List.of());

        AnalyticsDecisionResult result = service.decide(discovery);

        assertThat(result.action()).isEqualTo(AnalyticsDecisionResult.DecisionAction.DISTRIBUTION_TOPIC);
        assertThat(result.evidence()).anyMatch(e -> e.contains("LOW_REACH"));
    }

    @Test
    void prioritizesDistributionWhenBothReachAndCtrAreLow() {
        DiscoveryOptimizationResult discovery = discovery(
                DiscoveryOptimizationResult.DiscoveryDiagnosis.LOW_REACH_AND_LOW_CTR,
                RetentionAnalysisResult.RetentionSeverity.STRONG,
                null,
                List.of());

        AnalyticsDecisionResult result = service.decide(discovery);

        assertThat(result.action()).isEqualTo(AnalyticsDecisionResult.DecisionAction.DISTRIBUTION_TOPIC);
    }

    @Test
    void prioritizesContentRetentionWhenRetentionIsWeakEvenIfCtrIsLow() {
        DiscoveryOptimizationResult discovery = discovery(
                DiscoveryOptimizationResult.DiscoveryDiagnosis.LOW_CTR,
                RetentionAnalysisResult.RetentionSeverity.WEAK,
                null,
                List.of());

        AnalyticsDecisionResult result = service.decide(discovery);

        assertThat(result.action()).isEqualTo(AnalyticsDecisionResult.DecisionAction.CONTENT_RETENTION);
        assertThat(result.evidence()).anyMatch(e -> e.contains("Retention severity is WEAK"));
        assertThat(result.evidence()).anyMatch(e -> e.contains("LOW_CTR"));
    }

    @Test
    void continuesObservingHealthyDiscoveryHealthyRetentionAndAcceleratingMomentum() {
        DiscoveryOptimizationResult discovery = discovery(
                DiscoveryOptimizationResult.DiscoveryDiagnosis.HEALTHY_DISCOVERY,
                RetentionAnalysisResult.RetentionSeverity.HEALTHY,
                new DiscoveryOptimizationResult.ViewVelocity(200.0, 100.0, 100.0,
                        DiscoveryOptimizationResult.MomentumStatus.ACCELERATING),
                List.of());

        AnalyticsDecisionResult result = service.decide(discovery);

        assertThat(result.action()).isEqualTo(AnalyticsDecisionResult.DecisionAction.CONTINUE_OBSERVING);
        assertThat(result.evidence()).anyMatch(e -> e.contains("ACCELERATING"));
    }

    @Test
    void doesNotLetStableMomentumOverrideAHealthyDiscoveryDecision() {
        DiscoveryOptimizationResult discovery = discovery(
                DiscoveryOptimizationResult.DiscoveryDiagnosis.HEALTHY_DISCOVERY,
                RetentionAnalysisResult.RetentionSeverity.HEALTHY,
                new DiscoveryOptimizationResult.ViewVelocity(100.0, 100.0, 0.0,
                        DiscoveryOptimizationResult.MomentumStatus.STABLE),
                List.of());

        AnalyticsDecisionResult result = service.decide(discovery);

        assertThat(result.action()).isEqualTo(AnalyticsDecisionResult.DecisionAction.CONTINUE_OBSERVING);
    }

    private DiscoveryOptimizationResult discovery(
            DiscoveryOptimizationResult.DiscoveryDiagnosis diagnosis,
            RetentionAnalysisResult.RetentionSeverity retentionSeverity,
            DiscoveryOptimizationResult.ViewVelocity velocity,
            List<String> missingData) {
        RetentionAnalysisResult retention = new RetentionAnalysisResult(
                "abc123", 120.0, 60.0, retentionSeverity, null, null, null, null,
                List.of(), null, List.of(), List.of(), List.of());
        return DiscoveryOptimizationResult.builder()
                .videoId("abc123")
                .impressions(10000L)
                .impressionsClickThroughRate(8.0)
                .impressionsPerDay(1000.0)
                .reachStatus(DiscoveryOptimizationResult.DiscoveryStatus.HEALTHY)
                .packagingStatus(DiscoveryOptimizationResult.DiscoveryStatus.HEALTHY)
                .primaryDiagnosis(diagnosis)
                .retentionAnalysis(retention)
                .viewVelocity(velocity)
                .missingData(missingData)
                .build();
    }
}
