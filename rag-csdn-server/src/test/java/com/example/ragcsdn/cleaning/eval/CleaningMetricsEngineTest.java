package com.example.ragcsdn.cleaning.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CleaningMetricsEngineTest {

    @Test
    void summarize_computesAveragesAcrossArticles() {
        CleaningMetricsEngine engine = new CleaningMetricsEngine();

        CleaningBatchSummary summary = engine.summarize(List.of(
                new CleaningMetrics("a-1", 0.80d, 0.70d, 0.12d, 1.0d, 1.0d, 1.0d),
                new CleaningMetrics("a-2", 0.60d, 0.50d, 0.08d, 0.0d, 1.0d, 1.0d)
        ));

        assertThat(summary.articleCount()).isEqualTo(2);
        assertThat(summary.averageCodeIntegrity()).isEqualTo(0.70d);
        assertThat(summary.averageStructureRetention()).isEqualTo(0.60d);
        assertThat(summary.averageInvalidContentRatio()).isEqualTo(0.10d);
        assertThat(summary.top1HitRate()).isEqualTo(0.50d);
        assertThat(summary.top3HitRate()).isEqualTo(1.0d);
        assertThat(summary.top5HitRate()).isEqualTo(1.0d);
    }
}
