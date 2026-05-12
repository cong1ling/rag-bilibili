package com.example.ragcsdn.cleaning.eval;

import java.util.List;

public class CleaningMetricsAggregator {

    public CleaningBatchSummary summarize(List<CleaningMetrics> metrics) {
        int articleCount = metrics.size();
        double avgCode = metrics.stream().mapToDouble(CleaningMetrics::codeIntegrity).average().orElse(0.0d);
        double avgStructure = metrics.stream().mapToDouble(CleaningMetrics::structureRetention).average().orElse(0.0d);
        double avgInvalid = metrics.stream().mapToDouble(CleaningMetrics::invalidContentRatio).average().orElse(0.0d);
        double top1 = metrics.stream().mapToDouble(CleaningMetrics::top1Hit).average().orElse(0.0d);
        double top3 = metrics.stream().mapToDouble(CleaningMetrics::top3Hit).average().orElse(0.0d);
        double top5 = metrics.stream().mapToDouble(CleaningMetrics::top5Hit).average().orElse(0.0d);

        return new CleaningBatchSummary(articleCount, avgCode, avgStructure, avgInvalid, top1, top3, top5);
    }
}
