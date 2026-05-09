package com.example.ragcsdn.cleaning.eval;

public record CleaningMetrics(
        String articleId,
        double codeIntegrity,
        double structureRetention,
        double invalidContentRatio,
        double top1Hit,
        double top3Hit,
        double top5Hit
) {
}
