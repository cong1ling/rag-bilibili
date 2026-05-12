package com.example.ragcsdn.cleaning.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public class CleaningMetricsEngine {
    private final CleaningSampleEvaluator sampleEvaluator;
    private final CleaningMetricsAggregator metricsAggregator;
    private final CleaningFindingCollector findingCollector;

    public CleaningMetricsEngine() {
        this(new CleaningSampleEvaluator(), new CleaningMetricsAggregator(), new CleaningFindingCollector());
    }

    CleaningMetricsEngine(
            CleaningSampleEvaluator sampleEvaluator,
            CleaningMetricsAggregator metricsAggregator,
            CleaningFindingCollector findingCollector
    ) {
        this.sampleEvaluator = sampleEvaluator;
        this.metricsAggregator = metricsAggregator;
        this.findingCollector = findingCollector;
    }

    public CleaningBatchSummary summarize(List<CleaningMetrics> metrics) {
        return metricsAggregator.summarize(metrics);
    }

    public List<CleaningMetrics> evaluate(List<EvaluationArticleSample> samples, List<CleaningRunArtifact> artifacts) {
        Map<String, EvaluationArticleSample> sampleById = samples.stream()
                .collect(Collectors.toMap(EvaluationArticleSample::articleId, Function.identity(), (left, right) -> left));
        List<CleaningMetrics> metrics = new ArrayList<>();
        for (CleaningRunArtifact artifact : artifacts) {
            EvaluationArticleSample sample = sampleById.get(artifact.articleId());
            if (sample == null) {
                continue;
            }
            metrics.add(sampleEvaluator.evaluateSingle(sample, artifact, artifacts));
        }
        return metrics;
    }

    public List<CleaningArticleComparison> compare(
            List<EvaluationArticleSample> samples,
            List<CleaningRunArtifact> baselineArtifacts,
            List<CleaningRunArtifact> optimizedArtifacts,
            List<CleaningMetrics> baselineMetrics,
            List<CleaningMetrics> optimizedMetrics) {
        return findingCollector.compare(
                samples,
                baselineArtifacts,
                optimizedArtifacts,
                baselineMetrics,
                optimizedMetrics
        );
    }
}
