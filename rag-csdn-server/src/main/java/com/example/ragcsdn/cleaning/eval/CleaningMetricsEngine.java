package com.example.ragcsdn.cleaning.eval;

import com.example.ragcsdn.cleaning.StructuredArticleCleaner;
import com.example.ragcsdn.cleaning.model.CleaningBlock;
import com.example.ragcsdn.cleaning.model.CleaningBlockType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public class CleaningMetricsEngine {

    private final StructuredArticleCleaner structuredArticleCleaner = new StructuredArticleCleaner();

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

    public List<CleaningMetrics> evaluate(List<EvaluationArticleSample> samples, List<CleaningRunArtifact> artifacts) {
        Map<String, EvaluationArticleSample> sampleById = samples.stream()
                .collect(Collectors.toMap(EvaluationArticleSample::articleId, Function.identity(), (left, right) -> left));
        List<ChunkCandidate> corpus = buildCorpus(artifacts);
        List<CleaningMetrics> metrics = new ArrayList<>();
        for (CleaningRunArtifact artifact : artifacts) {
            EvaluationArticleSample sample = sampleById.get(artifact.articleId());
            if (sample == null) {
                continue;
            }
            metrics.add(evaluateSingle(sample, artifact, corpus));
        }
        return metrics;
    }

    public List<CleaningArticleComparison> compare(
            List<EvaluationArticleSample> samples,
            List<CleaningRunArtifact> baselineArtifacts,
            List<CleaningRunArtifact> optimizedArtifacts,
            List<CleaningMetrics> baselineMetrics,
            List<CleaningMetrics> optimizedMetrics) {
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
            int retainedNoiseBaseline = retainedNoiseChars(sample, baseline.cleanedText());
            int retainedNoiseOptimized = retainedNoiseChars(sample, optimized.cleanedText());
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

    private CleaningMetrics evaluateSingle(EvaluationArticleSample sample, CleaningRunArtifact artifact, List<ChunkCandidate> corpus) {
        List<CleaningBlock> blocks = structuredArticleCleaner.extractBlocks(sample.rawHtml());
        List<CleaningBlock> codeBlocks = blocks.stream().filter(block -> block.type() == CleaningBlockType.CODE).toList();
        List<CleaningBlock> headingBlocks = blocks.stream().filter(block -> block.type() == CleaningBlockType.HEADING).toList();
        List<CleaningBlock> bodyBlocks = blocks.stream()
                .filter(block -> block.type() == CleaningBlockType.PARAGRAPH
                        || block.type() == CleaningBlockType.LIST
                        || block.type() == CleaningBlockType.BLOCKQUOTE
                        || block.type() == CleaningBlockType.TABLE_ROW)
                .toList();

        double codeIntegrity = ratio(
                codeBlocks.stream().filter(block -> containsExactBlock(artifact.cleanedText(), artifact.chunks(), block.content())).count(),
                codeBlocks.size());
        double headingScore = ratio(
                headingBlocks.stream().filter(block -> artifact.cleanedText().contains(formatHeading(block))).count(),
                headingBlocks.size());
        double bodyScore = ratio(
                bodyBlocks.stream().filter(block -> artifact.cleanedText().contains(normalizeWhitespace(block.content()))).count(),
                bodyBlocks.size());

        double structureScore = weightedAverage(
                List.of(
                        weightedComponent(headingBlocks.isEmpty(), headingScore, 2.0d),
                        weightedComponent(codeBlocks.isEmpty(), codeIntegrity, 2.0d),
                        weightedComponent(bodyBlocks.isEmpty(), bodyScore, 1.0d)
                ));

        int retainedNoiseChars = retainedNoiseChars(sample, artifact.cleanedText());
        double invalidContentRatio = artifact.cleanedText().isBlank()
                ? 0.0d
                : (double) retainedNoiseChars / artifact.cleanedText().length();

        Ranking ranking = rankForSample(sample, artifact.articleId(), corpus);
        return new CleaningMetrics(
                artifact.articleId(),
                codeIntegrity,
                structureScore,
                invalidContentRatio,
                ranking.top1Hit ? 1.0d : 0.0d,
                ranking.top3Hit ? 1.0d : 0.0d,
                ranking.top5Hit ? 1.0d : 0.0d
        );
    }

    private int retainedNoiseChars(EvaluationArticleSample sample, String cleanedText) {
        return structuredArticleCleaner.extractBlocks(sample.rawHtml()).stream()
                .filter(block -> block.type() == CleaningBlockType.NOISE)
                .map(CleaningBlock::content)
                .filter(content -> cleanedText.contains(content))
                .mapToInt(String::length)
                .sum();
    }

    private Ranking rankForSample(EvaluationArticleSample sample, String expectedArticleId, List<ChunkCandidate> corpus) {
        String query = buildQuery(sample);
        List<ChunkScore> ranked = corpus.stream()
                .map(candidate -> new ChunkScore(candidate.articleId(), score(query, candidate.text())))
                .sorted(Comparator.comparingDouble(ChunkScore::score).reversed())
                .toList();
        return new Ranking(hitWithin(ranked, expectedArticleId, 1), hitWithin(ranked, expectedArticleId, 3), hitWithin(ranked, expectedArticleId, 5));
    }

    private boolean hitWithin(List<ChunkScore> ranked, String articleId, int limit) {
        return ranked.stream().limit(limit).anyMatch(score -> score.articleId().equals(articleId));
    }

    private double score(String query, String text) {
        Set<String> queryTerms = extractTerms(query);
        Set<String> textTerms = extractTerms(text);
        if (queryTerms.isEmpty() || textTerms.isEmpty()) {
            return 0.0d;
        }
        long overlap = queryTerms.stream().filter(textTerms::contains).count();
        double exactBonus = text.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT)) ? 1.0d : 0.0d;
        return overlap + exactBonus;
    }

    private Set<String> extractTerms(String value) {
        String normalized = normalizeWhitespace(value).toLowerCase(Locale.ROOT);
        Set<String> terms = new LinkedHashSet<>();
        for (String token : normalized.split("[^\\p{L}\\p{N}\\u4E00-\\u9FFF]+")) {
            if (token.length() >= 2) {
                terms.add(token);
            }
            if (token.matches(".*[\\u4E00-\\u9FFF].*")) {
                for (int i = 0; i < token.length() - 1; i++) {
                    terms.add(token.substring(i, i + 2));
                }
            }
        }
        return terms;
    }

    private List<ChunkCandidate> buildCorpus(List<CleaningRunArtifact> artifacts) {
        List<ChunkCandidate> corpus = new ArrayList<>();
        for (CleaningRunArtifact artifact : artifacts) {
            for (String chunk : artifact.chunks()) {
                corpus.add(new ChunkCandidate(artifact.articleId(), chunk));
            }
        }
        return corpus;
    }

    private String buildQuery(EvaluationArticleSample sample) {
        List<CleaningBlock> blocks = structuredArticleCleaner.extractBlocks(sample.rawHtml());
        String heading = blocks.stream()
                .filter(block -> block.type() == CleaningBlockType.HEADING)
                .map(CleaningBlock::content)
                .findFirst()
                .orElse("");
        String paragraph = blocks.stream()
                .filter(block -> block.type() == CleaningBlockType.PARAGRAPH)
                .map(CleaningBlock::content)
                .map(this::compactSnippet)
                .findFirst()
                .orElse("");
        String code = blocks.stream()
                .filter(block -> block.type() == CleaningBlockType.CODE)
                .map(CleaningBlock::content)
                .map(this::firstCodeSignal)
                .filter(value -> !value.isBlank())
                .findFirst()
                .orElse("");

        if (!heading.isBlank() && !code.isBlank()) {
            return heading + " " + code;
        }
        if (!heading.isBlank() && !paragraph.isBlank()) {
            return heading + " " + paragraph;
        }
        if (!paragraph.isBlank()) {
            return paragraph;
        }
        if (!heading.isBlank()) {
            return heading;
        }
        return sample.title();
    }

    private String compactSnippet(String value) {
        String normalized = normalizeWhitespace(value);
        if (normalized.length() <= 18) {
            return normalized;
        }
        return normalized.substring(0, 18);
    }

    private String firstCodeSignal(String code) {
        String normalized = code == null ? "" : code.replace("\r\n", "\n").replace('\r', '\n');
        for (String line : normalized.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isBlank()) {
                return compactSnippet(trimmed);
            }
        }
        return "";
    }

    private boolean containsExactBlock(String cleanedText, List<String> chunks, String blockContent) {
        if (cleanedText.contains(blockContent)) {
            return true;
        }
        return chunks.stream().anyMatch(chunk -> chunk.contains(blockContent));
    }

    private String formatHeading(CleaningBlock block) {
        return "#".repeat(Math.max(2, block.level())) + " " + normalizeWhitespace(block.content());
    }

    private String normalizeWhitespace(String value) {
        return value == null ? "" : value.replace('\u00A0', ' ').replaceAll("\\s+", " ").trim();
    }

    private double ratio(long numerator, int denominator) {
        return denominator == 0 ? 1.0d : (double) numerator / denominator;
    }

    private WeightedComponent weightedComponent(boolean absent, double value, double weight) {
        return new WeightedComponent(absent, value, weight);
    }

    private double weightedAverage(List<WeightedComponent> components) {
        double totalWeight = components.stream().filter(component -> !component.absent).mapToDouble(WeightedComponent::weight).sum();
        if (totalWeight == 0.0d) {
            return 1.0d;
        }
        double weightedValue = components.stream()
                .filter(component -> !component.absent)
                .mapToDouble(component -> component.value * component.weight)
                .sum();
        return weightedValue / totalWeight;
    }

    private record ChunkCandidate(String articleId, String text) {
    }

    private record ChunkScore(String articleId, double score) {
    }

    private record Ranking(boolean top1Hit, boolean top3Hit, boolean top5Hit) {
    }

    private record WeightedComponent(boolean absent, double value, double weight) {
    }
}
