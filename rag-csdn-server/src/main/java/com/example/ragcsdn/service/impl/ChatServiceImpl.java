package com.example.ragcsdn.service.impl;

import com.alibaba.cloud.ai.vectorstore.dashvector.DashVectorStore;
import com.example.ragcsdn.config.ChatOptimizationProperties;
import com.example.ragcsdn.config.DashVectorProperties;
import com.example.ragcsdn.dto.sse.SseErrorEvent;
import com.example.ragcsdn.entity.Message;
import com.example.ragcsdn.entity.Session;
import com.example.ragcsdn.entity.Article;
import com.example.ragcsdn.enums.MessageRole;
import com.example.ragcsdn.enums.SessionType;
import com.example.ragcsdn.exception.BusinessException;
import com.example.ragcsdn.exception.ErrorCode;
import com.example.ragcsdn.mapper.ChunkMapper;
import com.example.ragcsdn.mapper.MessageMapper;
import com.example.ragcsdn.mapper.SessionMapper;
import com.example.ragcsdn.mapper.ArticleMapper;
import com.example.ragcsdn.service.ChatService;
import com.example.ragcsdn.service.chat.ChatStreamingOrchestrator;
import com.example.ragcsdn.service.chat.ChatMetadataHelper;
import com.example.ragcsdn.service.chat.ChatPromptBuilder;
import com.example.ragcsdn.service.chat.ChatRoutingPolicy;
import com.example.ragcsdn.service.chat.ConversationMemoryService;
import com.example.ragcsdn.service.chat.ConversationSummaryService;
import com.example.ragcsdn.service.chat.DocumentRerankService;
import com.example.ragcsdn.service.chat.QueryUnderstandingService;
import com.example.ragcsdn.service.chat.QueryExpansionService;
import com.example.ragcsdn.service.chat.QueryRewriteService;
import com.example.ragcsdn.service.chat.RetrievalPipelineService;
import com.example.ragcsdn.service.chat.ResponseConfidenceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ChatServiceImpl implements ChatService {
    private static final Logger log = LoggerFactory.getLogger(ChatServiceImpl.class);
    private static final int HYBRID_RRF_K = 60;
    private static final int DEFAULT_RECENT_MEMORY_MESSAGES = 6;

    @Autowired
    private SessionMapper sessionMapper;

    @Autowired
    private MessageMapper messageMapper;

    @Autowired
    private ArticleMapper articleMapper;

    @Autowired
    private ChunkMapper chunkMapper;

    @Autowired
    private DashVectorStore dashVectorStore;

    @Autowired
    private ChatClient.Builder chatClientBuilder;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    @Qualifier("taskExecutor")
    private TaskExecutor taskExecutor;

    private static final long SSE_TIMEOUT = 60000L;

    @Autowired
    private DashVectorProperties dashVectorProperties;

    @Autowired
    private ChatOptimizationProperties chatOptimizationProperties;

    @Autowired
    private QueryComplexityAnalyzer queryComplexityAnalyzer;

    @Autowired
    private ChatPromptBuilder chatPromptBuilder;

    @Autowired
    private ResponseConfidenceService responseConfidenceService;

    @Autowired
    private ConversationMemoryService conversationMemoryService;

    @Autowired
    private QueryUnderstandingService queryUnderstandingService;

    @Autowired
    private QueryRewriteService queryRewriteService;

    @Autowired
    private QueryExpansionService queryExpansionService;

    @Autowired
    private ConversationSummaryService conversationSummaryService;

    @Autowired
    private ChatStreamingOrchestrator chatStreamingOrchestrator;

    @Autowired
    private ChatMetadataHelper chatMetadataHelper;

    @Autowired
    private RetrievalPipelineService retrievalPipelineService;

    @Autowired
    private DocumentRerankService documentRerankService;

    private enum QueryIntent {
        DIRECT,
        AMBIGUOUS,
        BROAD
    }

    private record ConversationMemory(
            List<org.springframework.ai.chat.messages.Message> recentMessages,
            String summary,
            boolean summaryUsed
    ) {
    }

    private record RetrievalQuery(
            String vectorQuery,
            String keywordQuery,
            String source
    ) {
    }

    private record QueryPlan(
            QueryIntent intent,
            String originalQuery,
            String rewrittenQuery,
            List<RetrievalQuery> retrievalQueries
    ) {
    }

    private record RoutingAnalysis(
            QueryIntent suggestedIntent,
            double ambiguityScore,
            double breadthScore,
            double complexityScore,
            double decisionConfidence,
            boolean conversationDependent
    ) {
    }

    private record QueryUnderstandingDecision(
            QueryPlan queryPlan,
            RoutingAnalysis routingAnalysis,
            boolean usedLlmFallback,
            boolean usedHyde,
            boolean usedDecomposition
    ) {
    }

    @Override
    public SseEmitter streamMessage(Long sessionId, String content, Long userId) {
        // 1. 验证会话
        Session session = sessionMapper.selectById(sessionId);
        if (session == null || !session.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.SESSION_NOT_FOUND);
        }

        // 2. 保存用户消息
        Message userMessage = new Message();
        userMessage.setSessionId(sessionId);
        userMessage.setRole(MessageRole.USER.getCode());
        userMessage.setContent(content);
        userMessage.setCreateTime(LocalDateTime.now());
        messageMapper.insert(userMessage);

        // 3. 创建 SSE Emitter
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);

        // 4. 使用TaskExecutor异步处理
        taskExecutor.execute(() -> {
            try {
                // 5. 获取历史消息并构建记忆上下文
                List<Message> historyMessages = messageMapper.selectBySessionId(sessionId);
                ConversationMemory memory = buildConversationMemory(session, historyMessages, userMessage.getId());

                // 6. Query 理解与检索路由
                QueryUnderstandingDecision decision = understandQuery(content, memory);
                QueryPlan queryPlan = decision.queryPlan();
                List<Document> relevantDocs = retrieveRelevantDocuments(session, queryPlan, userId);
                ResponseConfidenceService.ResponseConfidence confidence = evaluateConfidence(relevantDocs);
                int finalTopK = determineTopK(session, queryPlan.rewrittenQuery());
                logRoutingDecision(
                        queryPlan.originalQuery(),
                        queryPlan.rewrittenQuery(),
                        decision.routingAnalysis(),
                        queryPlan.intent(),
                        decision.usedLlmFallback(),
                        decision.usedHyde(),
                        decision.usedDecomposition(),
                        finalTopK,
                        relevantDocs.size()
                );

                // 7. 构建上下文
                String context = buildContext(relevantDocs);

                // 8. 构建提示词
                String systemPrompt = buildSystemPrompt(context, memory.summary(), confidence);

                chatStreamingOrchestrator.stream(new ChatStreamingOrchestrator.StreamRequest(
                        emitter,
                        sessionId,
                        userId,
                        userMessage.getId(),
                        content,
                        systemPrompt,
                        memory.recentMessages(),
                        mapQueryPlan(queryPlan),
                        confidence,
                        relevantDocs,
                        memory.summaryUsed()
                ));

            } catch (Exception e) {
                log.error("对话处理失败: sessionId={}, userId={}", sessionId, userId, e);
                try {
                    SseErrorEvent errorEvent = new SseErrorEvent(
                            e.getMessage() != null ? e.getMessage() : "处理失败");
                    emitter.send(SseEmitter.event()
                            .name("error")
                            .data(objectMapper.writeValueAsString(errorEvent)));
                } catch (Exception ex) {
                    log.error("发送错误事件失败", ex);
                }
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    /**
     * 检索相关文档
     */
    private List<Document> retrieveRelevantDocuments(Session session, QueryPlan queryPlan, Long userId) {
        int topK = determineTopK(session, queryPlan.rewrittenQuery());
        int candidateTopK = getCandidateTopK(topK);
        String scopedSourceId = resolveScopedSourceId(session);
        if (SessionType.isSingleArticle(session.getSessionType()) && scopedSourceId == null) {
            return List.of();
        }

        List<List<Document>> rankedLists = queryPlan.retrievalQueries().stream()
                .map(retrievalQuery -> retrieveCandidatesForQuery(
                        retrievalQuery, userId, scopedSourceId, candidateTopK))
                .filter(list -> !list.isEmpty())
                .collect(Collectors.toList());

        List<Document> candidates = mergeQueryCandidates(rankedLists, candidateTopK);

        if (!isRerankEnabled()) {
            return candidates.stream().limit(topK).collect(Collectors.toList());
        }
        return rerankDocuments(queryPlan.rewrittenQuery(), candidates, topK);
    }

    /**
     * 构建上下文
     */
    private String buildContext(List<Document> documents) {
        return chatPromptBuilder.buildContext(documents);
    }

    private ConversationMemory buildConversationMemory(Session session, List<Message> messages, Long excludeId) {
        ConversationMemoryService.ConversationMemory memory = conversationMemoryService.buildConversationMemory(
                session,
                messages,
                excludeId,
                getSummaryTriggerMessages(),
                getSummaryRecentMessages(),
                getMaxHistory(),
                isSummaryEnabled(),
                conversationSummaryService::summarize
        );
        return new ConversationMemory(memory.recentMessages(), memory.summary(), memory.summaryUsed());
    }

    private QueryUnderstandingDecision understandQuery(String query, ConversationMemory memory) {
        String rewrittenQuery = queryRewriteService.rewrite(query, memory.recentMessages(), memory.summary());
        QueryExpansionService.QueryExpansionDecision decision = queryExpansionService.expand(
                query,
                rewrittenQuery,
                new ConversationMemoryService.ConversationMemory(
                        memory.recentMessages(),
                        memory.summary(),
                        memory.summaryUsed()
                )
        );
        return new QueryUnderstandingDecision(
                mapQueryPlan(decision.queryPlan()),
                mapRoutingAnalysis(decision.routingAnalysis()),
                decision.usedLlmFallback(),
                decision.usedHyde(),
                decision.usedDecomposition()
        );
    }

    private QueryPlan mapQueryPlan(QueryExpansionService.QueryPlan queryPlan) {
        return new QueryPlan(
                mapIntent(queryPlan.intent()),
                queryPlan.originalQuery(),
                queryPlan.rewrittenQuery(),
                queryPlan.retrievalQueries().stream()
                        .map(retrievalQuery -> new RetrievalQuery(
                                retrievalQuery.vectorQuery(),
                                retrievalQuery.keywordQuery(),
                                retrievalQuery.source()))
                        .collect(Collectors.toList())
        );
    }

    private QueryExpansionService.QueryPlan mapQueryPlan(QueryPlan queryPlan) {
        return new QueryExpansionService.QueryPlan(
                mapIntent(queryPlan.intent()),
                queryPlan.originalQuery(),
                queryPlan.rewrittenQuery(),
                queryPlan.retrievalQueries().stream()
                        .map(retrievalQuery -> new QueryExpansionService.RetrievalQuery(
                                retrievalQuery.vectorQuery(),
                                retrievalQuery.keywordQuery(),
                                retrievalQuery.source()))
                        .collect(Collectors.toList())
        );
    }

    private RoutingAnalysis mapRoutingAnalysis(QueryExpansionService.RoutingAnalysis routingAnalysis) {
        return new RoutingAnalysis(
                mapIntent(routingAnalysis.suggestedIntent()),
                routingAnalysis.ambiguityScore(),
                routingAnalysis.breadthScore(),
                routingAnalysis.complexityScore(),
                routingAnalysis.decisionConfidence(),
                routingAnalysis.conversationDependent()
        );
    }

    private QueryIntent mapIntent(QueryExpansionService.QueryIntent intent) {
        return switch (intent) {
            case DIRECT -> QueryIntent.DIRECT;
            case AMBIGUOUS -> QueryIntent.AMBIGUOUS;
            case BROAD -> QueryIntent.BROAD;
        };
    }

    private QueryExpansionService.QueryIntent mapIntent(QueryIntent intent) {
        return switch (intent) {
            case DIRECT -> QueryExpansionService.QueryIntent.DIRECT;
            case AMBIGUOUS -> QueryExpansionService.QueryIntent.AMBIGUOUS;
            case BROAD -> QueryExpansionService.QueryIntent.BROAD;
        };
    }

    /**
     * 构建聊天历史（滑动窗口，转换为 Spring AI Message 类型）
     */
    private List<org.springframework.ai.chat.messages.Message> buildMessageHistory(
            List<Message> messages, Long excludeId) {
        return conversationMemoryService.buildMessageHistory(messages, excludeId, getMaxHistory());
    }

    /**
     * 构建系统提示词
     */
    private String buildSystemPrompt(String context) {
        return chatPromptBuilder.buildSystemPrompt(context);
    }

    private String buildSystemPrompt(String context, String memorySummary,
                                     ResponseConfidenceService.ResponseConfidence confidence) {
        return chatPromptBuilder.buildSystemPrompt(context, memorySummary, confidence, isConfidenceAwareEnabled());
    }

    private QueryIntent inferQueryIntentHeuristically(String query) {
        return switch (queryUnderstandingService.inferQueryIntentHeuristically(query, false)) {
            case AMBIGUOUS -> QueryIntent.AMBIGUOUS;
            case BROAD -> QueryIntent.BROAD;
            case DIRECT -> QueryIntent.DIRECT;
        };
    }

    private List<String> normalizeDecomposedQueries(String raw, String fallbackQuery) {
        return queryUnderstandingService.normalizeDecomposedQueries(raw, fallbackQuery, getMaxDecomposedQueries());
    }

    private String normalizeRewrittenQuery(String originalQuery, String rewritten) {
        return queryUnderstandingService.normalizeRewrittenQuery(originalQuery, rewritten);
    }

    private String buildSourceHeader(Document document, int fallbackIndex) {
        String title = getMetadataString(document, "title", "未知文章");
        String sourceId = getMetadataString(document, "sourceId", "未知标识");
        int chunkIndex = getMetadataInt(document, "chunkIndex", fallbackIndex - 1) + 1;
        int totalChunks = getMetadataInt(document, "totalChunks", 0);
        double score = getMetadataDouble(document, "score", 0.0d);
        String scoreLabel = getMetadataString(document, "scoreLabel", "相似度");

        if (totalChunks > 0) {
            return String.format(Locale.ROOT,
                    "[文章: %s, 标识: %s, 片段 %d/%d, %s: %.3f]",
                    title, sourceId, chunkIndex, totalChunks, scoreLabel, score);
        }

        return String.format(Locale.ROOT,
                "[文章: %s, 标识: %s, 片段 %d, %s: %.3f]",
                title, sourceId, chunkIndex, scoreLabel, score);
    }

    private String resolveScopedSourceId(Session session) {
        if (!SessionType.isSingleArticle(session.getSessionType())) {
            return null;
        }

        Article article = articleMapper.selectById(session.getArticleId());
        return article == null ? null : article.getSourceId();
    }

    private List<Document> retrieveVectorDocuments(String query, Long userId, String scopedSourceId, int topK) {
        FilterExpressionBuilder filterExpressionBuilder = new FilterExpressionBuilder();
        SearchRequest.Builder builder = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(getSimilarityThreshold());

        if (scopedSourceId != null) {
            builder.filterExpression(
                    filterExpressionBuilder.and(
                            filterExpressionBuilder.eq("userId", userId),
                            filterExpressionBuilder.eq("sourceId", scopedSourceId)
                    ).build()
            );
        } else {
            builder.filterExpression(filterExpressionBuilder.eq("userId", userId).build());
        }

        return dashVectorStore.similaritySearch(builder.build()).stream()
                .map(document -> document.mutate()
                        .metadata("scoreLabel", "相似度")
                        .metadata("retrievalSource", "vector")
                        .build())
                .collect(Collectors.toList());
    }

    private List<Document> retrieveCandidatesForQuery(RetrievalQuery retrievalQuery,
                                                      Long userId,
                                                      String scopedSourceId,
                                                      int candidateTopK) {
        List<Document> vectorResults = retrieveVectorDocuments(
                retrievalQuery.vectorQuery(), userId, scopedSourceId, candidateTopK);

        if (!isHybridSearchEnabled() || retrievalQuery.keywordQuery() == null || retrievalQuery.keywordQuery().isBlank()) {
            return vectorResults.stream().limit(candidateTopK).collect(Collectors.toList());
        }

        String keywordSearchText = buildKeywordSearchText(extractKeywords(retrievalQuery.keywordQuery()));
        if (keywordSearchText.isBlank()) {
            return vectorResults.stream().limit(candidateTopK).collect(Collectors.toList());
        }

        List<Document> keywordResults = retrieveKeywordDocuments(
                userId, scopedSourceId, keywordSearchText, Math.max(getKeywordTopK(), candidateTopK));
        return mergeHybridResults(vectorResults, keywordResults, candidateTopK);
    }

    private List<Document> retrieveKeywordDocuments(Long userId, String scopedSourceId, String searchText, int limit) {
        return chunkMapper.searchByKeywords(userId, scopedSourceId, searchText, limit).stream()
                .map(this::toKeywordDocument)
                .collect(Collectors.toList());
    }

    private Document toKeywordDocument(com.example.ragcsdn.entity.Chunk chunk) {
        return Document.builder()
                .id(buildChunkKey(chunk.getSourceId(), chunk.getChunkIndex(), chunk.getChunkText()))
                .text(chunk.getChunkText())
                .metadata("title", chunk.getTitle())
                .metadata("sourceId", chunk.getSourceId())
                .metadata("chunkIndex", chunk.getChunkIndex())
                .metadata("totalChunks", chunk.getTotalChunks())
                .metadata("score", chunk.getKeywordScore() == null ? 0.0d : chunk.getKeywordScore())
                .metadata("scoreLabel", "关键词得分")
                .metadata("retrievalSource", "keyword")
                .build();
    }

    private List<String> extractKeywords(String query) {
        return retrievalPipelineService.extractKeywords(query);
    }

    private String buildKeywordSearchText(List<String> keywords) {
        return retrievalPipelineService.buildKeywordSearchText(keywords);
    }

    private String sanitizeFullTextTerm(String input) {
        return retrievalPipelineService.sanitizeFullTextTerm(input);
    }

    private List<Document> mergeHybridResults(List<Document> vectorResults, List<Document> keywordResults, int limit) {
        return retrievalPipelineService.mergeHybridResults(vectorResults, keywordResults, limit);
    }

    private List<Document> mergeQueryCandidates(List<List<Document>> rankedLists, int limit) {
        return retrievalPipelineService.mergeQueryCandidates(rankedLists, limit);
    }

    private List<Document> rerankDocuments(String query, List<Document> candidates, int finalTopK) {
        return documentRerankService.rerankDocuments(
                query,
                candidates,
                finalTopK,
                getCandidateTopK(finalTopK),
                isModelRerankEnabled(),
                getModelRerankTopK(),
                chatClientBuilder
        );
    }

    private ResponseConfidenceService.ResponseConfidence evaluateConfidence(List<Document> documents) {
        return responseConfidenceService.evaluateConfidence(documents, isConfidenceAwareEnabled());
    }

    private int determineTopK(Session session, String query) {
        return currentRoutingPolicy().determineTopK(session, query, getConfiguredTopK());
    }

    private RoutingAnalysis analyzeRouting(String rewrittenQuery, ConversationMemory memory) {
        boolean conversationDependent = rewrittenQuery != null
                && rewrittenQuery.length() <= 24
                && memory != null
                && (!memory.recentMessages().isEmpty() || (memory.summary() != null && !memory.summary().isBlank()));

        QueryComplexityAnalyzer.Analysis analysis =
                queryComplexityAnalyzer.analyze(rewrittenQuery, conversationDependent);

        QueryIntent suggestedIntent = switch (analysis.suggestedIntent()) {
            case AMBIGUOUS -> QueryIntent.AMBIGUOUS;
            case BROAD -> QueryIntent.BROAD;
            case DIRECT -> QueryIntent.DIRECT;
        };

        return new RoutingAnalysis(
                suggestedIntent,
                analysis.ambiguityScore(),
                analysis.breadthScore(),
                analysis.complexityScore(),
                analysis.decisionConfidence(),
                conversationDependent
        );
    }

    private void logRoutingDecision(String originalQuery, String rewrittenQuery, RoutingAnalysis routingAnalysis,
                                    QueryIntent finalIntent, boolean usedLlmFallback,
                                    boolean usedHyde, boolean usedDecomposition, int finalTopK, int retrievedDocCount) {
        log.info(
                "routing decision: originalQuery={}, rewrittenQuery={}, suggestedIntent={}, finalIntent={}, "
                        + "ambiguityScore={}, breadthScore={}, complexityScore={}, decisionConfidence={}, "
                        + "usedLlmFallback={}, usedHyde={}, usedDecomposition={}, finalTopK={}, retrievedDocCount={}, "
                        + "ruleRoutingEnabled={}, routingObservationOnly={}",
                originalQuery,
                rewrittenQuery,
                routingAnalysis.suggestedIntent(),
                finalIntent,
                routingAnalysis.ambiguityScore(),
                routingAnalysis.breadthScore(),
                routingAnalysis.complexityScore(),
                routingAnalysis.decisionConfidence(),
                usedLlmFallback,
                usedHyde,
                usedDecomposition,
                finalTopK,
                retrievedDocCount,
                isRuleRoutingEnabled(),
                isRoutingObservationOnly()
        );
    }

    private String buildChunkKey(String sourceId, int chunkIndex, String text) {
        return sourceId + "#" + chunkIndex + "#" + Objects.hashCode(text);
    }

    private String getMetadataString(Document document, String key, String defaultValue) {
        Map<String, Object> metadata = document.getMetadata();
        if (metadata == null) {
            return defaultValue;
        }
        Object value = metadata.get(key);
        if (value == null) {
            return defaultValue;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? defaultValue : text;
    }

    private int getMetadataInt(Document document, String key, int defaultValue) {
        Map<String, Object> metadata = document.getMetadata();
        if (metadata == null) {
            return defaultValue;
        }
        Object value = metadata.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private double getMetadataDouble(Document document, String key, double defaultValue) {
        Map<String, Object> metadata = document.getMetadata();
        if (metadata == null) {
            return defaultValue;
        }
        Object value = metadata.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private int getConfiguredTopK() {
        if (dashVectorProperties == null || dashVectorProperties.getDefaultTopK() == null
                || dashVectorProperties.getDefaultTopK() <= 0) {
            return 5;
        }
        return dashVectorProperties.getDefaultTopK();
    }

    private double getSimilarityThreshold() {
        if (dashVectorProperties == null || dashVectorProperties.getSimilarityThreshold() == null) {
            return 0.0d;
        }
        return dashVectorProperties.getSimilarityThreshold();
    }

    private int getMaxHistory() {
        if (chatOptimizationProperties == null || chatOptimizationProperties.getMaxHistory() == null
                || chatOptimizationProperties.getMaxHistory() <= 0) {
            return 10;
        }
        return chatOptimizationProperties.getMaxHistory();
    }

    private boolean isQueryRewriteEnabled() {
        return chatOptimizationProperties == null || !Boolean.FALSE.equals(chatOptimizationProperties.getQueryRewriteEnabled());
    }

    private boolean isQueryUnderstandingEnabled() {
        return chatOptimizationProperties == null || !Boolean.FALSE.equals(chatOptimizationProperties.getQueryUnderstandingEnabled());
    }

    private boolean isHydeEnabled() {
        return chatOptimizationProperties == null || !Boolean.FALSE.equals(chatOptimizationProperties.getHydeEnabled());
    }

    private boolean isDecompositionEnabled() {
        return chatOptimizationProperties == null || !Boolean.FALSE.equals(chatOptimizationProperties.getDecompositionEnabled());
    }

    private boolean isHybridSearchEnabled() {
        return chatOptimizationProperties == null || !Boolean.FALSE.equals(chatOptimizationProperties.getHybridSearchEnabled());
    }

    private int getKeywordTopK() {
        if (chatOptimizationProperties == null || chatOptimizationProperties.getKeywordTopK() == null
                || chatOptimizationProperties.getKeywordTopK() <= 0) {
            return Math.max(getConfiguredTopK(), 8);
        }
        return chatOptimizationProperties.getKeywordTopK();
    }

    private boolean isRerankEnabled() {
        return chatOptimizationProperties == null || !Boolean.FALSE.equals(chatOptimizationProperties.getRerankEnabled());
    }

    private boolean isModelRerankEnabled() {
        return chatOptimizationProperties != null && Boolean.TRUE.equals(chatOptimizationProperties.getModelRerankEnabled());
    }

    private int getCandidateTopK(int finalTopK) {
        if (!isRerankEnabled()) {
            return finalTopK;
        }
        if (chatOptimizationProperties == null || chatOptimizationProperties.getRerankCandidateTopK() == null
                || chatOptimizationProperties.getRerankCandidateTopK() <= 0) {
            return Math.max(finalTopK, 20);
        }
        return Math.max(finalTopK, chatOptimizationProperties.getRerankCandidateTopK());
    }

    private int getModelRerankTopK() {
        if (chatOptimizationProperties == null || chatOptimizationProperties.getModelRerankTopK() == null
                || chatOptimizationProperties.getModelRerankTopK() <= 1) {
            return 8;
        }
        return Math.min(chatOptimizationProperties.getModelRerankTopK(), getCandidateTopK(getConfiguredTopK()));
    }

    private boolean isDynamicTopKEnabled() {
        return chatOptimizationProperties == null || !Boolean.FALSE.equals(chatOptimizationProperties.getDynamicTopKEnabled());
    }

    private ChatRoutingPolicy currentRoutingPolicy() {
        return new ChatRoutingPolicy(chatOptimizationProperties, queryComplexityAnalyzer, retrievalPipelineService, chatMetadataHelper);
    }

    private boolean isRuleRoutingEnabled() {
        return chatOptimizationProperties == null
                || !Boolean.FALSE.equals(chatOptimizationProperties.getRuleRoutingEnabled());
    }

    private boolean isRoutingObservationOnly() {
        return chatOptimizationProperties != null
                && Boolean.TRUE.equals(chatOptimizationProperties.getRoutingObservationOnly());
    }

    private double getComplexityThreshold() {
        if (chatOptimizationProperties == null || chatOptimizationProperties.getComplexityThreshold() == null) {
            return 0.55d;
        }
        return chatOptimizationProperties.getComplexityThreshold();
    }

    private boolean isRuleRoutingLlmFallbackEnabled() {
        return chatOptimizationProperties == null
                || !Boolean.FALSE.equals(chatOptimizationProperties.getRuleRoutingLlmFallbackEnabled());
    }

    private double getLlmFallbackConfidenceThreshold() {
        if (chatOptimizationProperties == null || chatOptimizationProperties.getLlmFallbackConfidenceThreshold() == null) {
            return 0.52d;
        }
        return chatOptimizationProperties.getLlmFallbackConfidenceThreshold();
    }

    private double getHydeTriggerThreshold() {
        if (chatOptimizationProperties == null || chatOptimizationProperties.getHydeTriggerThreshold() == null) {
            return 0.72d;
        }
        return chatOptimizationProperties.getHydeTriggerThreshold();
    }

    private double getDecompositionTriggerThreshold() {
        if (chatOptimizationProperties == null || chatOptimizationProperties.getDecompositionTriggerThreshold() == null) {
            return 0.68d;
        }
        return chatOptimizationProperties.getDecompositionTriggerThreshold();
    }

    private int getSimpleTopK(int fallback) {
        if (chatOptimizationProperties == null || chatOptimizationProperties.getSimpleTopK() == null
                || chatOptimizationProperties.getSimpleTopK() <= 0) {
            return Math.min(fallback, 3);
        }
        return chatOptimizationProperties.getSimpleTopK();
    }

    private int getNormalTopK(int fallback) {
        if (chatOptimizationProperties == null || chatOptimizationProperties.getNormalTopK() == null
                || chatOptimizationProperties.getNormalTopK() <= 0) {
            return fallback;
        }
        return chatOptimizationProperties.getNormalTopK();
    }

    private int getComplexTopK(int fallback) {
        if (chatOptimizationProperties == null || chatOptimizationProperties.getComplexTopK() == null
                || chatOptimizationProperties.getComplexTopK() <= 0) {
            return Math.max(fallback, 8);
        }
        return chatOptimizationProperties.getComplexTopK();
    }

    private boolean isSummaryEnabled() {
        return chatOptimizationProperties == null || !Boolean.FALSE.equals(chatOptimizationProperties.getSummaryEnabled());
    }

    private int getSummaryTriggerMessages() {
        if (chatOptimizationProperties == null || chatOptimizationProperties.getSummaryTriggerMessages() == null
                || chatOptimizationProperties.getSummaryTriggerMessages() <= 0) {
            return getMaxHistory();
        }
        return chatOptimizationProperties.getSummaryTriggerMessages();
    }

    private int getSummaryRecentMessages() {
        if (chatOptimizationProperties == null || chatOptimizationProperties.getSummaryRecentMessages() == null
                || chatOptimizationProperties.getSummaryRecentMessages() <= 0) {
            return DEFAULT_RECENT_MEMORY_MESSAGES;
        }
        return chatOptimizationProperties.getSummaryRecentMessages();
    }

    private int getSummaryMaxLength() {
        if (chatOptimizationProperties == null || chatOptimizationProperties.getSummaryMaxLength() == null
                || chatOptimizationProperties.getSummaryMaxLength() <= 0) {
            return 150;
        }
        return chatOptimizationProperties.getSummaryMaxLength();
    }

    private int getMaxDecomposedQueries() {
        if (chatOptimizationProperties == null || chatOptimizationProperties.getMaxDecomposedQueries() == null
                || chatOptimizationProperties.getMaxDecomposedQueries() <= 0) {
            return 4;
        }
        return chatOptimizationProperties.getMaxDecomposedQueries();
    }

    private boolean isConfidenceAwareEnabled() {
        return chatOptimizationProperties == null || !Boolean.FALSE.equals(chatOptimizationProperties.getConfidenceAwareEnabled());
    }
}
