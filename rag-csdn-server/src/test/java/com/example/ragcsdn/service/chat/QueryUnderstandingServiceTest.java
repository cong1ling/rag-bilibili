package com.example.ragcsdn.service.chat;

import com.example.ragcsdn.config.ChatOptimizationProperties;
import com.example.ragcsdn.service.impl.QueryComplexityAnalyzer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QueryUnderstandingServiceTest {

    @Test
    void normalizeRewrittenQuery_shouldStripPrefixAndQuotes() {
        QueryUnderstandingService service = new QueryUnderstandingService(analyzer());

        String normalized = service.normalizeRewrittenQuery(
                "原始问题",
                "Rewrite Query: \"Spring Boot 的优点是什么\""
        );

        assertThat(normalized).isEqualTo("Spring Boot 的优点是什么");
    }

    @Test
    void normalizeDecomposedQueries_shouldFallbackToOriginalWhenResultIsBlank() {
        QueryUnderstandingService service = new QueryUnderstandingService(analyzer());

        List<String> queries = service.normalizeDecomposedQueries(" \n \n ", "原始问题", 3);

        assertThat(queries).containsExactly("原始问题");
    }

    @Test
    void inferQueryIntentHeuristically_shouldMarkPronounQuestionAsAmbiguous() {
        QueryUnderstandingService service = new QueryUnderstandingService(analyzer());

        QueryComplexityAnalyzer.QueryIntentHint intent = service.inferQueryIntentHeuristically("它的优点呢", false);

        assertThat(intent).isEqualTo(QueryComplexityAnalyzer.QueryIntentHint.AMBIGUOUS);
    }

    @Test
    void inferQueryIntentHeuristically_shouldMarkBroadQuestionAsBroad() {
        QueryUnderstandingService service = new QueryUnderstandingService(analyzer());

        QueryComplexityAnalyzer.QueryIntentHint intent =
                service.inferQueryIntentHeuristically("对比分析 Spring Boot 和 Tomcat 的架构差异", false);

        assertThat(intent).isEqualTo(QueryComplexityAnalyzer.QueryIntentHint.BROAD);
    }

    private QueryComplexityAnalyzer analyzer() {
        ChatOptimizationProperties properties = new ChatOptimizationProperties();
        properties.setAmbiguityThreshold(0.62d);
        properties.setBreadthThreshold(0.58d);
        properties.setComplexityThreshold(0.55d);
        properties.setLlmFallbackConfidenceThreshold(0.52d);
        return new QueryComplexityAnalyzer(properties);
    }
}
