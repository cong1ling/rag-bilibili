package com.example.ragcsdn.service.impl;

import com.example.ragcsdn.config.ChatOptimizationProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QueryComplexityAnalyzerTest {

    private QueryComplexityAnalyzer newAnalyzer() {
        ChatOptimizationProperties properties = new ChatOptimizationProperties();
        properties.setAmbiguityThreshold(0.62d);
        properties.setBreadthThreshold(0.58d);
        properties.setComplexityThreshold(0.55d);
        properties.setLlmFallbackConfidenceThreshold(0.52d);
        return new QueryComplexityAnalyzer(properties);
    }

    @Test
    void analyze_shortFactQuery_prefersDirect() {
        QueryComplexityAnalyzer.Analysis analysis =
                newAnalyzer().analyze("mysql 默认端口是多少", false);

        assertThat(analysis.suggestedIntent()).isEqualTo(QueryComplexityAnalyzer.QueryIntentHint.DIRECT);
        assertThat(analysis.decisionConfidence()).isGreaterThan(0.52d);
        assertThat(analysis.complexityScore()).isLessThan(0.55d);
    }

    @Test
    void analyze_shortReferentialQuery_prefersAmbiguous() {
        QueryComplexityAnalyzer.Analysis analysis =
                newAnalyzer().analyze("这个怎么配", true);

        assertThat(analysis.suggestedIntent()).isEqualTo(QueryComplexityAnalyzer.QueryIntentHint.AMBIGUOUS);
        assertThat(analysis.ambiguityScore()).isGreaterThan(0.62d);
    }

    @Test
    void analyze_broadQuery_prefersBroad() {
        QueryComplexityAnalyzer.Analysis analysis =
                newAnalyzer().analyze("对比 Spring AI 和 LangChain 的架构取舍与适用场景", false);

        assertThat(analysis.suggestedIntent()).isEqualTo(QueryComplexityAnalyzer.QueryIntentHint.BROAD);
        assertThat(analysis.breadthScore()).isGreaterThan(0.58d);
        assertThat(analysis.complexityScore()).isGreaterThan(0.55d);
    }

    @Test
    void analyze_mixedSignals_returnsLowConfidence() {
        QueryComplexityAnalyzer.Analysis analysis =
                newAnalyzer().analyze("这个流程和默认端口有什么关系", true);

        assertThat(analysis.decisionConfidence()).isLessThan(0.52d);
    }
}
