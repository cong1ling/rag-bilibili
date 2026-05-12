package com.example.ragcsdn.cleaning.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CleaningSampleEvaluatorTest {

    @Test
    void evaluateSample_shouldRetainCodeBlocksAndMeasureNoiseLeakage() {
        CleaningSampleEvaluator evaluator = new CleaningSampleEvaluator();
        EvaluationArticleSample sample = new EvaluationArticleSample(
                "a-1",
                "https://blog.csdn.net/test_author/article/details/147000001",
                "标题",
                "<div id=\"content_views\"><h2>安装</h2><pre><code>return a;</code></pre><p>正文</p><p>点赞数 1</p></div>",
                "blog"
        );
        CleaningRunArtifact artifact = new CleaningRunArtifact(
                "a-1",
                sample.sourceUrl(),
                sample.title(),
                sample.sourceType(),
                "## 安装\nreturn a;\n正文",
                List.of("## 安装", "return a;", "正文")
        );

        CleaningMetrics metrics = evaluator.evaluateSingle(sample, artifact, List.of(artifact));

        assertThat(metrics.codeIntegrity()).isGreaterThan(0.0d);
        assertThat(metrics.invalidContentRatio()).isLessThan(1.0d);
    }
}
