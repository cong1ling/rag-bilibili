package com.example.ragcsdn.cleaning.eval;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CleaningReportGeneratorTest {

    @Test
    void writeBatchMarkdown_createsReadableSummary() throws Exception {
        Path tempFile = Files.createTempFile("cleaning-summary", ".md");
        CleaningReportGenerator generator = new CleaningReportGenerator();

        generator.writeBatchMarkdown(
                tempFile,
                "baseline",
                new CleaningBatchSummary(2, 0.70d, 0.60d, 0.10d, 0.50d, 1.0d, 1.0d)
        );

        String markdown = Files.readString(tempFile);
        assertThat(markdown).contains("Cleaning Batch Summary");
        assertThat(markdown).contains("variant: baseline");
        assertThat(markdown).contains("articleCount: 2");
        assertThat(markdown).contains("averageCodeIntegrity: 0.7");
        assertThat(markdown).contains("top3HitRate: 1.0");
    }

    @Test
    void cliWritesBatchSummaryIntoProvidedOutputDirectory() throws Exception {
        Path tempDir = Files.createTempDirectory("cleaning-eval");
        Path manifest = tempDir.resolve("manifest.json");
        Files.writeString(manifest, """
                [
                  {
                    "articleId": "a-1",
                    "sourceUrl": "https://blog.csdn.net/test_author/article/details/147000001",
                    "title": "结构测试",
                    "rawHtml": "<html><body><div id=\\\"content_views\\\"><h2>一、安装</h2><p>安装说明</p><pre><code>if (a &lt; b) {\\n    return a;\\n}</code></pre><p>点赞数 10</p></div></body></html>",
                    "sourceType": "existing"
                  }
                ]
                """);

        DataCleaningEvaluationCli.main(new String[]{manifest.toString(), tempDir.toString()});

        Path comparison = tempDir.resolve("cleaning-comparison-summary.md");
        Path perArticle = tempDir.resolve("cleaning-per-article.md");
        assertThat(Files.exists(comparison)).isTrue();
        assertThat(Files.readString(comparison)).contains("Batch Comparison");
        assertThat(Files.readString(comparison)).contains("existingSamples: 1");
        assertThat(Files.exists(perArticle)).isTrue();
        assertThat(Files.readString(perArticle)).contains("a-1");
    }
}
