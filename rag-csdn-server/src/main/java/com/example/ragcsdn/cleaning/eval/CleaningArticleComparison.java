package com.example.ragcsdn.cleaning.eval;

public record CleaningArticleComparison(
        String articleId,
        String title,
        String sourceUrl,
        String sourceType,
        CleaningMetrics baselineMetrics,
        CleaningMetrics optimizedMetrics,
        int baselineChunkCount,
        int optimizedChunkCount,
        int retainedNoiseCharsBaseline,
        int retainedNoiseCharsOptimized
) {
}
