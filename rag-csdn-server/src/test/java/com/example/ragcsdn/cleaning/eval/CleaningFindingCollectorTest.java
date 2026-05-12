package com.example.ragcsdn.cleaning.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CleaningFindingCollectorTest {

    @Test
    void compare_shouldAssembleChunkCountsAndNoiseDifferences() {
        CleaningFindingCollector collector = new CleaningFindingCollector();
        EvaluationArticleSample sample = new EvaluationArticleSample(
                "a-1",
                "https://blog.csdn.net/test_author/article/details/147000001",
                "标题",
                "<div id=\"content_views\"><p>正文</p><p>点赞数 1</p></div>",
                "blog"
        );
        CleaningRunArtifact baseline = new CleaningRunArtifact(
                "a-1",
                sample.sourceUrl(),
                sample.title(),
                sample.sourceType(),
                "正文\n点赞数 1",
                List.of("正文", "点赞数 1")
        );
        CleaningRunArtifact optimized = new CleaningRunArtifact(
                "a-1",
                sample.sourceUrl(),
                sample.title(),
                sample.sourceType(),
                "正文",
                List.of("正文")
        );
        CleaningMetrics baselineMetrics = new CleaningMetrics("a-1", 0.6d, 0.5d, 0.2d, 1.0d, 1.0d, 1.0d);
        CleaningMetrics optimizedMetrics = new CleaningMetrics("a-1", 0.9d, 0.8d, 0.0d, 1.0d, 1.0d, 1.0d);

        List<CleaningArticleComparison> comparisons = collector.compare(
                List.of(sample),
                List.of(baseline),
                List.of(optimized),
                List.of(baselineMetrics),
                List.of(optimizedMetrics)
        );

        assertThat(comparisons).hasSize(1);
        CleaningArticleComparison comparison = comparisons.get(0);
        assertThat(comparison.baselineChunkCount()).isEqualTo(2);
        assertThat(comparison.optimizedChunkCount()).isEqualTo(1);
        assertThat(comparison.retainedNoiseCharsBaseline()).isGreaterThan(comparison.retainedNoiseCharsOptimized());
    }
}
