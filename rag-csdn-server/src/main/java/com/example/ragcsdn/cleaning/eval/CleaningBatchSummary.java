package com.example.ragcsdn.cleaning.eval;

public record CleaningBatchSummary(
        int articleCount,
        double averageCodeIntegrity,
        double averageStructureRetention,
        double averageInvalidContentRatio,
        double top1HitRate,
        double top3HitRate,
        double top5HitRate
) {
}
