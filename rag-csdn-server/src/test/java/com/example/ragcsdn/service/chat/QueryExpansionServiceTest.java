package com.example.ragcsdn.service.chat;

import com.example.ragcsdn.config.ChatOptimizationProperties;
import com.example.ragcsdn.service.impl.QueryComplexityAnalyzer;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QueryExpansionServiceTest {

    @Test
    void expand_shouldFallbackToDirectWhenUnderstandingDisabled() {
        QueryComplexityAnalyzer analyzer = analyzer();
        QueryExpansionService service = new QueryExpansionService(
                null,
                analyzer,
                new QueryUnderstandingService(analyzer),
                new ChatRoutingPolicy(properties(), analyzer, null, new ChatMetadataHelper()),
                properties(),
                false
        );

        QueryExpansionService.QueryExpansionDecision decision = service.expand(
                "原始问题",
                "改写后问题",
                new ConversationMemoryService.ConversationMemory(List.of(), null, false)
        );

        assertThat(decision.queryPlan().intent()).isEqualTo(QueryExpansionService.QueryIntent.DIRECT);
        assertThat(decision.queryPlan().retrievalQueries())
                .containsExactly(new QueryExpansionService.RetrievalQuery("改写后问题", "改写后问题", "direct"));
        assertThat(decision.usedLlmFallback()).isFalse();
        assertThat(decision.usedHyde()).isFalse();
        assertThat(decision.usedDecomposition()).isFalse();
    }

    @Test
    void expand_shouldUseHydeForAmbiguousIntent() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class, Answers.RETURNS_DEEP_STUBS);
        when(builder.build().prompt().system(anyString()).messages(anyList()).user(eq("它的优点呢")).call().content())
                .thenReturn("AMBIGUOUS", "这是一段 HyDE 文档");

        QueryComplexityAnalyzer analyzer = analyzer();
        QueryExpansionService service = new QueryExpansionService(
                builder,
                analyzer,
                new QueryUnderstandingService(analyzer),
                new ChatRoutingPolicy(properties(), analyzer, null, new ChatMetadataHelper()),
                properties(),
                true
        );

        QueryExpansionService.QueryExpansionDecision decision = service.expand(
                "原始问题",
                "它的优点呢",
                new ConversationMemoryService.ConversationMemory(List.of(new UserMessage("Spring Boot")), "旧摘要", true)
        );

        assertThat(decision.queryPlan().intent()).isEqualTo(QueryExpansionService.QueryIntent.AMBIGUOUS);
        assertThat(decision.usedLlmFallback()).isTrue();
        assertThat(decision.usedHyde()).isTrue();
        assertThat(decision.usedDecomposition()).isFalse();
        assertThat(decision.queryPlan().retrievalQueries()).hasSize(2);
        assertThat(decision.queryPlan().retrievalQueries().get(1).source()).isEqualTo("hyde");
        assertThat(decision.queryPlan().retrievalQueries().get(1).vectorQuery()).isEqualTo("这是一段 HyDE 文档");
    }

    @Test
    void expand_shouldUseDecompositionWhenBroadQueryReturnsMultipleSubQueries() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class, Answers.RETURNS_DEEP_STUBS);
        when(builder.build().prompt().system(anyString()).messages(anyList()).user(eq("对比 Spring Boot 和 Tomcat 的架构差异与部署流程")).call().content())
                .thenReturn("BROAD", "部署模型\n自动配置\n运行方式");

        QueryComplexityAnalyzer analyzer = analyzer();
        QueryExpansionService service = new QueryExpansionService(
                builder,
                analyzer,
                new QueryUnderstandingService(analyzer),
                new ChatRoutingPolicy(properties(), analyzer, null, new ChatMetadataHelper()),
                properties(),
                true
        );

        QueryExpansionService.QueryExpansionDecision decision = service.expand(
                "原始问题",
                "对比 Spring Boot 和 Tomcat 的架构差异与部署流程",
                new ConversationMemoryService.ConversationMemory(List.of(), null, false)
        );

        assertThat(decision.queryPlan().intent()).isEqualTo(QueryExpansionService.QueryIntent.BROAD);
        assertThat(decision.usedLlmFallback()).isTrue();
        assertThat(decision.usedHyde()).isFalse();
        assertThat(decision.usedDecomposition()).isTrue();
        assertThat(decision.queryPlan().retrievalQueries()).extracting(QueryExpansionService.RetrievalQuery::source)
                .containsOnly("subquery");
    }

    private ChatOptimizationProperties properties() {
        ChatOptimizationProperties properties = new ChatOptimizationProperties();
        properties.setQueryUnderstandingEnabled(true);
        properties.setHydeEnabled(true);
        properties.setDecompositionEnabled(true);
        properties.setRuleRoutingEnabled(true);
        properties.setRoutingObservationOnly(true);
        properties.setRuleRoutingLlmFallbackEnabled(true);
        properties.setAmbiguityThreshold(0.62d);
        properties.setBreadthThreshold(0.58d);
        properties.setComplexityThreshold(0.55d);
        properties.setLlmFallbackConfidenceThreshold(0.52d);
        properties.setHydeTriggerThreshold(0.72d);
        properties.setDecompositionTriggerThreshold(0.68d);
        properties.setMaxDecomposedQueries(4);
        return properties;
    }

    private QueryComplexityAnalyzer analyzer() {
        return new QueryComplexityAnalyzer(properties());
    }
}
