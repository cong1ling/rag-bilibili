package com.example.ragcsdn.service.chat;

import com.example.ragcsdn.config.ChatOptimizationProperties;
import com.example.ragcsdn.service.impl.QueryComplexityAnalyzer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class QueryExpansionService {

    private static final Logger log = LoggerFactory.getLogger(QueryExpansionService.class);

    public enum QueryIntent {
        DIRECT,
        AMBIGUOUS,
        BROAD
    }

    public record RetrievalQuery(String vectorQuery, String keywordQuery, String source) {
    }

    public record QueryPlan(
            QueryIntent intent,
            String originalQuery,
            String rewrittenQuery,
            List<RetrievalQuery> retrievalQueries
    ) {
    }

    public record RoutingAnalysis(
            QueryIntent suggestedIntent,
            double ambiguityScore,
            double breadthScore,
            double complexityScore,
            double decisionConfidence,
            boolean conversationDependent
    ) {
    }

    public record QueryExpansionDecision(
            QueryPlan queryPlan,
            RoutingAnalysis routingAnalysis,
            boolean usedLlmFallback,
            boolean usedHyde,
            boolean usedDecomposition
    ) {
    }

    private final ChatClient.Builder chatClientBuilder;
    private final QueryComplexityAnalyzer queryComplexityAnalyzer;
    private final QueryUnderstandingService queryUnderstandingService;
    private final ChatRoutingPolicy chatRoutingPolicy;
    private final ChatOptimizationProperties properties;
    private final boolean queryUnderstandingEnabled;

    public QueryExpansionService(ChatClient.Builder chatClientBuilder,
                                 QueryComplexityAnalyzer queryComplexityAnalyzer,
                                 QueryUnderstandingService queryUnderstandingService,
                                 ChatRoutingPolicy chatRoutingPolicy,
                                 ChatOptimizationProperties properties) {
        this(chatClientBuilder, queryComplexityAnalyzer, queryUnderstandingService, chatRoutingPolicy, properties,
                properties == null || !Boolean.FALSE.equals(properties.getQueryUnderstandingEnabled()));
    }

    QueryExpansionService(ChatClient.Builder chatClientBuilder,
                          QueryComplexityAnalyzer queryComplexityAnalyzer,
                          QueryUnderstandingService queryUnderstandingService,
                          ChatRoutingPolicy chatRoutingPolicy,
                          ChatOptimizationProperties properties,
                          boolean queryUnderstandingEnabled) {
        this.chatClientBuilder = chatClientBuilder;
        this.queryComplexityAnalyzer = queryComplexityAnalyzer;
        this.queryUnderstandingService = queryUnderstandingService;
        this.chatRoutingPolicy = chatRoutingPolicy;
        this.properties = properties;
        this.queryUnderstandingEnabled = queryUnderstandingEnabled;
    }

    public QueryExpansionDecision expand(String originalQuery,
                                         String rewrittenQuery,
                                         ConversationMemoryService.ConversationMemory memory) {
        if (!queryUnderstandingEnabled) {
            return new QueryExpansionDecision(
                    directPlan(originalQuery, rewrittenQuery),
                    new RoutingAnalysis(QueryIntent.DIRECT, 0.0d, 0.0d, 0.0d, 1.0d, false),
                    false,
                    false,
                    false
            );
        }

        RoutingAnalysis routingAnalysis = analyzeRouting(rewrittenQuery, memory);
        QueryIntent intent = routingAnalysis.suggestedIntent();
        boolean usedLlmFallback = chatRoutingPolicy.shouldUseLlmFallback(routingAnalysis.decisionConfidence());
        if (usedLlmFallback) {
            intent = classifyQuery(rewrittenQuery, memory);
        }

        if (chatRoutingPolicy.shouldUseHyde(intent.name(), routingAnalysis.ambiguityScore())) {
            return new QueryExpansionDecision(
                    new QueryPlan(
                            intent,
                            originalQuery,
                            rewrittenQuery,
                            List.of(
                                    new RetrievalQuery(rewrittenQuery, rewrittenQuery, "direct"),
                                    new RetrievalQuery(generateHydeDocument(rewrittenQuery, memory), null, "hyde")
                            )
                    ),
                    routingAnalysis,
                    usedLlmFallback,
                    true,
                    false
            );
        }

        if (chatRoutingPolicy.shouldUseDecomposition(intent.name(), routingAnalysis.breadthScore())) {
            List<String> subQueries = decomposeQuery(rewrittenQuery, memory);
            if (subQueries.size() > 1) {
                return new QueryExpansionDecision(
                        new QueryPlan(
                                intent,
                                originalQuery,
                                rewrittenQuery,
                                subQueries.stream()
                                        .map(subQuery -> new RetrievalQuery(subQuery, subQuery, "subquery"))
                                        .toList()
                        ),
                        routingAnalysis,
                        usedLlmFallback,
                        false,
                        true
                );
            }
        }

        return new QueryExpansionDecision(
                directPlan(originalQuery, rewrittenQuery, intent),
                routingAnalysis,
                usedLlmFallback,
                false,
                false
        );
    }

    private QueryPlan directPlan(String originalQuery, String rewrittenQuery) {
        return directPlan(originalQuery, rewrittenQuery, QueryIntent.DIRECT);
    }

    private QueryPlan directPlan(String originalQuery, String rewrittenQuery, QueryIntent intent) {
        return new QueryPlan(
                intent,
                originalQuery,
                rewrittenQuery,
                List.of(new RetrievalQuery(rewrittenQuery, rewrittenQuery, "direct"))
        );
    }

    private RoutingAnalysis analyzeRouting(String rewrittenQuery, ConversationMemoryService.ConversationMemory memory) {
        boolean conversationDependent = rewrittenQuery != null
                && rewrittenQuery.length() <= 24
                && memory != null
                && (!memory.recentMessages().isEmpty() || (memory.summary() != null && !memory.summary().isBlank()));

        QueryComplexityAnalyzer.Analysis analysis = queryComplexityAnalyzer.analyze(rewrittenQuery, conversationDependent);
        return new RoutingAnalysis(
                switch (analysis.suggestedIntent()) {
                    case AMBIGUOUS -> QueryIntent.AMBIGUOUS;
                    case BROAD -> QueryIntent.BROAD;
                    case DIRECT -> QueryIntent.DIRECT;
                },
                analysis.ambiguityScore(),
                analysis.breadthScore(),
                analysis.complexityScore(),
                analysis.decisionConfidence(),
                conversationDependent
        );
    }

    private QueryIntent classifyQuery(String query, ConversationMemoryService.ConversationMemory memory) {
        try {
            String result = chatClientBuilder.build().prompt()
                    .system(ChatPromptTemplates.QUERY_INTENT_SYSTEM_PROMPT
                            + queryUnderstandingService.buildSummaryPrompt(memory.summary()))
                    .messages(memory.recentMessages())
                    .user(query)
                    .call()
                    .content();
            return switch (queryUnderstandingService.normalizeQueryIntent(result, query, false)) {
                case AMBIGUOUS -> QueryIntent.AMBIGUOUS;
                case BROAD -> QueryIntent.BROAD;
                case DIRECT -> QueryIntent.DIRECT;
            };
        } catch (Exception e) {
            log.warn("Query 分类失败，回退到启发式规则: query={}", query, e);
            return switch (queryUnderstandingService.inferQueryIntentHeuristically(query, false)) {
                case AMBIGUOUS -> QueryIntent.AMBIGUOUS;
                case BROAD -> QueryIntent.BROAD;
                case DIRECT -> QueryIntent.DIRECT;
            };
        }
    }

    private String generateHydeDocument(String query, ConversationMemoryService.ConversationMemory memory) {
        try {
            String hyde = chatClientBuilder.build().prompt()
                    .system(ChatPromptTemplates.HYDE_SYSTEM_PROMPT)
                    .messages(memory.recentMessages())
                    .user(query)
                    .call()
                    .content();
            return (hyde == null || hyde.isBlank()) ? query : hyde.trim();
        } catch (Exception e) {
            log.warn("HyDE 生成失败，回退到原始检索查询: query={}", query, e);
            return query;
        }
    }

    private List<String> decomposeQuery(String query, ConversationMemoryService.ConversationMemory memory) {
        try {
            String result = chatClientBuilder.build().prompt()
                    .system(ChatPromptTemplates.DECOMPOSITION_SYSTEM_PROMPT)
                    .messages(memory.recentMessages())
                    .user(query)
                    .call()
                    .content();
            return queryUnderstandingService.normalizeDecomposedQueries(result, query, getMaxDecomposedQueries());
        } catch (Exception e) {
            log.warn("Query 拆解失败，回退到单查询: query={}", query, e);
            return List.of(query);
        }
    }

    private int getMaxDecomposedQueries() {
        if (properties == null || properties.getMaxDecomposedQueries() == null
                || properties.getMaxDecomposedQueries() <= 0) {
            return 4;
        }
        return properties.getMaxDecomposedQueries();
    }
}
