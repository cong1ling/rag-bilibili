package com.example.ragcsdn.cleaning.eval;

import java.nio.file.Path;
import java.util.List;

public class DataCleaningEvaluationCli {

    public static void main(String[] args) throws Exception {
        Path manifestPath = args.length > 0 ? Path.of(args[0]) : null;
        Path outputDir = args.length > 1 ? Path.of(args[1]) : Path.of("target", "cleaning-eval");
        run(manifestPath, outputDir);
    }

    static void run(Path manifestPath, Path outputDir) throws Exception {
        SampleSetBuilder builder = new SampleSetBuilder();
        CleaningReportGenerator reportGenerator = new CleaningReportGenerator();
        CleanerBaselineRunner baselineRunner = new CleanerBaselineRunner();
        CleaningMetricsEngine metricsEngine = new CleaningMetricsEngine();

        List<EvaluationArticleSample> samples = manifestPath != null
                ? builder.readManifest(manifestPath)
                : builder.buildMixedManifest(defaultJdbcUrl(), defaultUsername(), defaultPassword(), 100, System.getenv("CSDN_COOKIE"));
        samples = builder.buildManifest(samples);

        List<CleaningRunArtifact> baselineArtifacts = baselineRunner.runBaseline(samples);
        List<CleaningRunArtifact> optimizedArtifacts = baselineRunner.runOptimized(samples);

        List<CleaningMetrics> baselineMetrics = metricsEngine.evaluate(samples, baselineArtifacts);
        List<CleaningMetrics> optimizedMetrics = metricsEngine.evaluate(samples, optimizedArtifacts);

        CleaningBatchSummary baselineSummary = metricsEngine.summarize(baselineMetrics);
        CleaningBatchSummary optimizedSummary = metricsEngine.summarize(optimizedMetrics);
        List<CleaningArticleComparison> comparisons = metricsEngine.compare(
                samples,
                baselineArtifacts,
                optimizedArtifacts,
                baselineMetrics,
                optimizedMetrics
        );

        int existingSamples = (int) samples.stream().filter(sample -> "existing".equals(sample.sourceType())).count();
        int fetchedSamples = samples.size() - existingSamples;

        reportGenerator.writeJson(outputDir.resolve("cleaning-sample-manifest.json"), samples);
        reportGenerator.writeJson(outputDir.resolve("baseline-metrics.json"), baselineMetrics);
        reportGenerator.writeJson(outputDir.resolve("optimized-metrics.json"), optimizedMetrics);
        reportGenerator.writeBatchMarkdown(outputDir.resolve("cleaning-baseline-summary.md"), "baseline", baselineSummary);
        reportGenerator.writeBatchMarkdown(outputDir.resolve("cleaning-optimized-summary.md"), "optimized", optimizedSummary);
        reportGenerator.writeComparisonMarkdown(
                outputDir.resolve("cleaning-comparison-summary.md"),
                baselineSummary,
                optimizedSummary,
                existingSamples,
                fetchedSamples
        );
        reportGenerator.writePerArticleMarkdown(outputDir.resolve("cleaning-per-article.md"), comparisons);
    }

    private static String defaultJdbcUrl() {
        return System.getProperty(
                "cleaning.jdbcUrl",
                System.getenv().getOrDefault(
                        "CLEANING_JDBC_URL",
                        "jdbc:mysql://127.0.0.1:3306/rag_csdn?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai"
                ));
    }

    private static String defaultUsername() {
        return System.getProperty("cleaning.dbUser", System.getenv().getOrDefault("DB_USERNAME", "root"));
    }

    private static String defaultPassword() {
        return System.getProperty("cleaning.dbPassword", System.getenv().getOrDefault("DB_PASSWORD", "123456"));
    }
}
