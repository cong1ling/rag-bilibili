package com.example.ragcsdn.cleaning.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CleaningMetricsAggregatorTest {

    @Test
    void aggregate_shouldAverageMetricColumns() {
        CleaningMetricsAggregator aggregator = new CleaningMetricsAggregator();

        CleaningBatchSummary summary = aggregator.summarize(List.of(
                new CleaningMetrics("a-1", 0.8d, 0.7d, 0.1d, 1.0d, 1.0d, 1.0d),
                new CleaningMetrics("a-2", 0.6d, 0.5d, 0.3d, 0.0d, 1.0d, 1.0d)
        ));

        assertThat(summary.averageCodeIntegrity()).isEqualTo(0.7d);
        assertThat(summary.top1HitRate()).isEqualTo(0.5d);
    }
}
