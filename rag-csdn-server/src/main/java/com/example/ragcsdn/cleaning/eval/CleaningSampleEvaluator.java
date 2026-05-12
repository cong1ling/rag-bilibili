package com.example.ragcsdn.cleaning.eval;

import com.example.ragcsdn.cleaning.StructuredArticleCleaner;
import com.example.ragcsdn.cleaning.model.CleaningBlock;
import com.example.ragcsdn.cleaning.model.CleaningBlockType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class CleaningSampleEvaluator {
    private final StructuredArticleCleaner structuredArticleCleaner;

    public CleaningSampleEvaluator() {
        this(new StructuredArticleCleaner());
    }

    CleaningSampleEvaluator(StructuredArticleCleaner structuredArticleCleaner) {
        this.structuredArticleCleaner = structuredArticleCleaner;
    }

    public CleaningMetrics evaluateSingle(
            EvaluationArticleSample sample,
            CleaningRunArtifact artifact,
            List<CleaningRunArtifact> corpusArtifacts
    ) {
        List<ChunkCandidate> corpus = buildCorpus(corpusArtifacts);
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
                codeBlocks.size()
        );
        double headingScore = ratio(
                headingBlocks.stream().filter(block -> artifact.cleanedText().contains(formatHeading(block))).count(),
                headingBlocks.size()
        );
        double bodyScore = ratio(
                bodyBlocks.stream().filter(block -> artifact.cleanedText().contains(normalizeWhitespace(block.content()))).count(),
                bodyBlocks.size()
        );

        double structureScore = weightedAverage(List.of(
                weightedComponent(headingBlocks.isEmpty(), headingScore, CleaningMetricsConstants.HEADING_WEIGHT),
                weightedComponent(codeBlocks.isEmpty(), codeIntegrity, CleaningMetricsConstants.CODE_WEIGHT),
                weightedComponent(bodyBlocks.isEmpty(), bodyScore, CleaningMetricsConstants.BODY_WEIGHT)
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

    int retainedNoiseChars(EvaluationArticleSample sample, String cleanedText) {
        return structuredArticleCleaner.extractBlocks(sample.rawHtml()).stream()
                .filter(block -> block.type() == CleaningBlockType.NOISE)
                .map(CleaningBlock::content)
                .filter(cleanedText::contains)
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
        if (normalized.length() <= CleaningMetricsConstants.SNIPPET_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, CleaningMetricsConstants.SNIPPET_LENGTH);
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
