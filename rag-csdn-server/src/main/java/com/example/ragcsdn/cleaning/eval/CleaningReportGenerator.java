package com.example.ragcsdn.cleaning.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class CleaningReportGenerator {

    private final ObjectMapper objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public void writeBatchMarkdown(Path output, String variant, CleaningBatchSummary summary) throws IOException {
        Files.createDirectories(output.getParent());
        String markdown = """
                # Cleaning Batch Summary
                variant: %s
                articleCount: %d
                averageCodeIntegrity: %s
                averageStructureRetention: %s
                averageInvalidContentRatio: %s
                top1HitRate: %s
                top3HitRate: %s
                top5HitRate: %s
                """.formatted(
                variant,
                summary.articleCount(),
                summary.averageCodeIntegrity(),
                summary.averageStructureRetention(),
                summary.averageInvalidContentRatio(),
                summary.top1HitRate(),
                summary.top3HitRate(),
                summary.top5HitRate()
        );
        Files.writeString(output, markdown);
    }

    public void writeComparisonMarkdown(
            Path output,
            CleaningBatchSummary baseline,
            CleaningBatchSummary optimized,
            int existingSamples,
            int fetchedSamples) throws IOException {
        Files.createDirectories(output.getParent());
        String markdown = """
                # Batch Comparison
                articleCount: %d
                existingSamples: %d
                fetchedSamples: %d
                baselineAverageCodeIntegrity: %s
                optimizedAverageCodeIntegrity: %s
                codeIntegrityDelta: %s
                baselineAverageStructureRetention: %s
                optimizedAverageStructureRetention: %s
                structureRetentionDelta: %s
                baselineAverageInvalidContentRatio: %s
                optimizedAverageInvalidContentRatio: %s
                invalidContentRatioDelta: %s
                baselineTop1HitRate: %s
                optimizedTop1HitRate: %s
                baselineTop3HitRate: %s
                optimizedTop3HitRate: %s
                baselineTop5HitRate: %s
                optimizedTop5HitRate: %s
                """.formatted(
                optimized.articleCount(),
                existingSamples,
                fetchedSamples,
                baseline.averageCodeIntegrity(),
                optimized.averageCodeIntegrity(),
                optimized.averageCodeIntegrity() - baseline.averageCodeIntegrity(),
                baseline.averageStructureRetention(),
                optimized.averageStructureRetention(),
                optimized.averageStructureRetention() - baseline.averageStructureRetention(),
                baseline.averageInvalidContentRatio(),
                optimized.averageInvalidContentRatio(),
                optimized.averageInvalidContentRatio() - baseline.averageInvalidContentRatio(),
                baseline.top1HitRate(),
                optimized.top1HitRate(),
                baseline.top3HitRate(),
                optimized.top3HitRate(),
                baseline.top5HitRate(),
                optimized.top5HitRate()
        );
        Files.writeString(output, markdown);
    }

    public void writePerArticleMarkdown(Path output, List<CleaningArticleComparison> comparisons) throws IOException {
        Files.createDirectories(output.getParent());
        StringBuilder builder = new StringBuilder("# Per Article Cleaning Report\n");
        for (CleaningArticleComparison comparison : comparisons) {
            builder.append("\n## ")
                    .append(comparison.articleId())
                    .append(" - ")
                    .append(comparison.title())
                    .append("\n")
                    .append("sourceType: ").append(comparison.sourceType()).append("\n")
                    .append("baselineCodeIntegrity: ").append(comparison.baselineMetrics().codeIntegrity()).append("\n")
                    .append("optimizedCodeIntegrity: ").append(comparison.optimizedMetrics().codeIntegrity()).append("\n")
                    .append("baselineStructureRetention: ").append(comparison.baselineMetrics().structureRetention()).append("\n")
                    .append("optimizedStructureRetention: ").append(comparison.optimizedMetrics().structureRetention()).append("\n")
                    .append("baselineInvalidContentRatio: ").append(comparison.baselineMetrics().invalidContentRatio()).append("\n")
                    .append("optimizedInvalidContentRatio: ").append(comparison.optimizedMetrics().invalidContentRatio()).append("\n")
                    .append("baselineChunks: ").append(comparison.baselineChunkCount()).append("\n")
                    .append("optimizedChunks: ").append(comparison.optimizedChunkCount()).append("\n")
                    .append("retainedNoiseCharsBaseline: ").append(comparison.retainedNoiseCharsBaseline()).append("\n")
                    .append("retainedNoiseCharsOptimized: ").append(comparison.retainedNoiseCharsOptimized()).append("\n");
        }
        Files.writeString(output, builder.toString());
    }

    public void writeJson(Path output, Object value) throws IOException {
        Files.createDirectories(output.getParent());
        objectMapper.writeValue(output.toFile(), value);
    }
}
