package com.example.ragcsdn.cleaning.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public class CleaningFindingCollector {
    private final CleaningSampleEvaluator sampleEvaluator;

    public CleaningFindingCollector() {
        this(new CleaningSampleEvaluator());
    }

    CleaningFindingCollector(CleaningSampleEvaluator sampleEvaluator) {
        this.sampleEvaluator = sampleEvaluator;
    }

    public List<CleaningArticleComparison> compare(
            List<EvaluationArticleSample> samples,
            List<CleaningRunArtifact> baselineArtifacts,
            List<CleaningRunArtifact> optimizedArtifacts,
            List<CleaningMetrics> baselineMetrics,
            List<CleaningMetrics> optimizedMetrics
    ) {
        Map<String, CleaningRunArtifact> baselineById = baselineArtifacts.stream()
                .collect(Collectors.toMap(CleaningRunArtifact::articleId, Function.identity(), (left, right) -> left));
        Map<String, CleaningRunArtifact> optimizedById = optimizedArtifacts.stream()
                .collect(Collectors.toMap(CleaningRunArtifact::articleId, Function.identity(), (left, right) -> left));
        Map<String, CleaningMetrics> baselineMetricsById = baselineMetrics.stream()
                .collect(Collectors.toMap(CleaningMetrics::articleId, Function.identity(), (left, right) -> left));
        Map<String, CleaningMetrics> optimizedMetricsById = optimizedMetrics.stream()
                .collect(Collectors.toMap(CleaningMetrics::articleId, Function.identity(), (left, right) -> left));

        List<CleaningArticleComparison> comparisons = new ArrayList<>();
        for (EvaluationArticleSample sample : samples) {
            CleaningRunArtifact baseline = baselineById.get(sample.articleId());
            CleaningRunArtifact optimized = optimizedById.get(sample.articleId());
            CleaningMetrics baselineMetric = baselineMetricsById.get(sample.articleId());
            CleaningMetrics optimizedMetric = optimizedMetricsById.get(sample.articleId());
            if (baseline == null || optimized == null || baselineMetric == null || optimizedMetric == null) {
                continue;
            }
            int retainedNoiseBaseline = sampleEvaluator.retainedNoiseChars(sample, baseline.cleanedText());
            int retainedNoiseOptimized = sampleEvaluator.retainedNoiseChars(sample, optimized.cleanedText());
            comparisons.add(new CleaningArticleComparison(
                    sample.articleId(),
                    sample.title(),
                    sample.sourceUrl(),
                    sample.sourceType(),
                    baselineMetric,
                    optimizedMetric,
                    baseline.chunks().size(),
                    optimized.chunks().size(),
                    retainedNoiseBaseline,
                    retainedNoiseOptimized
            ));
        }
        return comparisons;
    }
}
