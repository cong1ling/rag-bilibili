package com.example.ragcsdn.service.chat;

import com.example.ragcsdn.config.ChatOptimizationProperties;
import com.example.ragcsdn.service.impl.QueryComplexityAnalyzer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QueryUnderstandingServiceTest {

    @Test
    void normalizeDecomposedQueries_shouldFallbackToOriginalWhenResultIsBlank() {
        QueryUnderstandingService service = new QueryUnderstandingService(analyzer());

        List<String> queries = service.normalizeDecomposedQueries(" \n \n ", "原始问题", 3);

        assertThat(queries).containsExactly("原始问题");
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
