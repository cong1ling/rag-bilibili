package com.example.ragcsdn.service.impl;

import com.alibaba.cloud.ai.vectorstore.dashvector.DashVectorStore;
import com.example.ragcsdn.config.ChatOptimizationProperties;
import com.example.ragcsdn.config.DashVectorProperties;
import com.example.ragcsdn.dto.sse.SseContentEvent;
import com.example.ragcsdn.dto.sse.SseEndEvent;
import com.example.ragcsdn.dto.sse.SseErrorEvent;
import com.example.ragcsdn.dto.sse.SseStartEvent;
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
import com.example.ragcsdn.service.chat.ChatMetadataHelper;
import com.example.ragcsdn.service.chat.ChatPromptTemplates;
import com.example.ragcsdn.service.chat.ChatPromptBuilder;
import com.example.ragcsdn.service.chat.ChatRoutingPolicy;
import com.example.ragcsdn.service.chat.ConversationMemoryService;
import com.example.ragcsdn.service.chat.DocumentRerankService;
import com.example.ragcsdn.service.chat.QueryUnderstandingService;
import com.example.ragcsdn.service.chat.QueryRewriteService;
import com.example.ragcsdn.service.chat.RetrievalPipelineService;
import com.example.ragcsdn.service.chat.ResponseConfidenceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

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
    private ChatMetadataHelper chatMetadataHelper;

    @Autowired
    private RetrievalPipelineService retrievalPipelineService;

    @Autowired
    private DocumentRerankService documentRerankService;

    @Autowired
    private ChatRoutingPolicy chatRoutingPolicy;

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
                // 5. 发送start事件
                SseStartEvent startEvent = new SseStartEvent(userMessage.getId());
                emitter.send(SseEmitter.event()
                        .name("start")
                        .data(objectMapper.writeValueAsString(startEvent)));

                // 6. 获取历史消息并构建记忆上下文
                List<Message> historyMessages = messageMapper.selectBySessionId(sessionId);
                ConversationMemory memory = buildConversationMemory(session, historyMessages, userMessage.getId());

                // 7. Query 理解与检索路由
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

                // 8. 构建上下文
                String context = buildContext(relevantDocs);

                // 9. 构建提示词
                String systemPrompt = buildSystemPrompt(context, memory.summary(), confidence);

                // 10. 流式调用 LLM
                ChatClient chatClient = chatClientBuilder.build();
                Flux<ChatResponse> responseFlux = chatClient.prompt()
                        .system(systemPrompt)
                        .messages(memory.recentMessages())
                        .user(content)
                        .stream()
                        .chatResponse();

                // 11. 收集完整响应
                StringBuilder fullResponse = new StringBuilder();

                // 12. 流式发送
                responseFlux.subscribe(
                        response -> {
                            String chunk = response.getResult().getOutput().getText();
                            if (chunk != null && !chunk.isEmpty()) {
                                fullResponse.append(chunk);
                                try {
                                    SseContentEvent contentEvent = new SseContentEvent(chunk);
                                    emitter.send(SseEmitter.event()
                                            .name("content")
                                            .data(objectMapper.writeValueAsString(contentEvent)));
                                } catch (Exception e) {
                                    log.error("SSE发送失败", e);
                                    emitter.completeWithError(e);
                                }
                            }
                        },
                        error -> {
                            log.error("LLM调用失败", error);
                            try {
                                SseErrorEvent errorEvent = new SseErrorEvent(
                                        error.getMessage() != null ? error.getMessage() : "未知错误");
                                emitter.send(SseEmitter.event()
                                        .name("error")
                                        .data(objectMapper.writeValueAsString(errorEvent)));
                            } catch (Exception e) {
                                log.error("发送错误事件失败", e);
                            }
                            emitter.completeWithError(error);
                        },
                        () -> {
                            try {
                                // 13. 保存助手消息
                                Message assistantMessage = new Message();
                                assistantMessage.setSessionId(sessionId);
                                assistantMessage.setRole(MessageRole.ASSISTANT.getCode());
                                assistantMessage.setContent(fullResponse.toString());
                                assistantMessage.setCreateTime(LocalDateTime.now());
                                messageMapper.insert(assistantMessage);

                                refreshAndPersistConversationSummary(sessionId);

                                // 14. 发送end事件
                                SseEndEvent endEvent = new SseEndEvent(
                                        assistantMessage.getId(),
                                        fullResponse.toString());
                                endEvent.setQueryIntent(queryPlan.intent().name());
                                endEvent.setRewrittenQuery(queryPlan.rewrittenQuery());
                                endEvent.setConfidenceLabel(confidence.label());
                                endEvent.setConfidenceScore(confidence.score());
                                endEvent.setSourceCount(relevantDocs.size());
                                endEvent.setKnowledgeGap(confidence.knowledgeGap());
                                endEvent.setSummaryUsed(memory.summaryUsed());
                                emitter.send(SseEmitter.event()
                                        .name("end")
                                        .data(objectMapper.writeValueAsString(endEvent)));

                                // 15. 完成 SSE
                                emitter.complete();
                                log.info("对话完成: sessionId={}, userId={}", sessionId, userId);
                            } catch (Exception e) {
                                log.error("发送end事件失败", e);
                                emitter.completeWithError(e);
                            }
                        }
                );

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
                this::summarizeConversation
        );
        return new ConversationMemory(memory.recentMessages(), memory.summary(), memory.summaryUsed());
    }

    private void refreshAndPersistConversationSummary(Long sessionId) {
        if (!isSummaryEnabled()) {
            return;
        }

        List<Message> messages = messageMapper.selectBySessionId(sessionId).stream()
                .sorted(Comparator.comparing(Message::getCreateTime))
                .collect(Collectors.toList());

        if (messages.size() <= getSummaryTriggerMessages()) {
            sessionMapper.updateSummary(sessionId, null, null);
            return;
        }

        int recentCount = Math.min(getSummaryRecentMessages(), messages.size());
        List<Message> olderMessages = messages.subList(0, messages.size() - recentCount);
        String summary = summarizeConversation(olderMessages);
        sessionMapper.updateSummary(sessionId, summary, LocalDateTime.now());
    }

    private QueryUnderstandingDecision understandQuery(String query, ConversationMemory memory) {
        String rewrittenQuery = rewriteQuery(query, memory.recentMessages(), memory.summary());
        if (!isQueryUnderstandingEnabled()) {
            QueryPlan directPlan = new QueryPlan(
                    QueryIntent.DIRECT,
                    query,
                    rewrittenQuery,
                    List.of(new RetrievalQuery(rewrittenQuery, rewrittenQuery, "direct"))
            );
            return new QueryUnderstandingDecision(
                    directPlan,
                    new RoutingAnalysis(QueryIntent.DIRECT, 0.0d, 0.0d, 0.0d, 1.0d, false),
                    false,
                    false,
                    false
            );
        }

        RoutingAnalysis routingAnalysis = analyzeRouting(rewrittenQuery, memory);
        QueryIntent intent = routingAnalysis.suggestedIntent();
        boolean usedLlmFallback = shouldUseLlmFallback(routingAnalysis.decisionConfidence());
        if (usedLlmFallback) {
            intent = classifyQuery(rewrittenQuery, memory);
        }

        boolean usedHyde = false;
        boolean usedDecomposition = false;
        QueryPlan queryPlan;

        if (shouldUseHyde(intent.name(), routingAnalysis.ambiguityScore())) {
            usedHyde = true;
            String hydeDocument = generateHydeDocument(rewrittenQuery, memory);
            queryPlan = new QueryPlan(
                    intent,
                    query,
                    rewrittenQuery,
                    List.of(
                            new RetrievalQuery(rewrittenQuery, rewrittenQuery, "direct"),
                            new RetrievalQuery(hydeDocument, null, "hyde")
                    )
            );
        } else if (shouldUseDecomposition(intent.name(), routingAnalysis.breadthScore())) {
            List<String> subQueries = decomposeQuery(rewrittenQuery, memory);
            if (subQueries.size() > 1) {
                usedDecomposition = true;
                queryPlan = new QueryPlan(
                        intent,
                        query,
                        rewrittenQuery,
                        subQueries.stream()
                                .map(subQuery -> new RetrievalQuery(subQuery, subQuery, "subquery"))
                                .collect(Collectors.toList())
                );
            } else {
                queryPlan = new QueryPlan(
                        intent,
                        query,
                        rewrittenQuery,
                        List.of(new RetrievalQuery(rewrittenQuery, rewrittenQuery, "direct"))
                );
            }
        } else {
            queryPlan = new QueryPlan(
                    intent,
                    query,
                    rewrittenQuery,
                    List.of(new RetrievalQuery(rewrittenQuery, rewrittenQuery, "direct"))
            );
        }

        return new QueryUnderstandingDecision(
                queryPlan,
                routingAnalysis,
                usedLlmFallback,
                usedHyde,
                usedDecomposition
        );
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

    private String rewriteQuery(String query, List<org.springframework.ai.chat.messages.Message> historyMessages) {
        return rewriteQuery(query, historyMessages, null);
    }

    private String rewriteQuery(String query, List<org.springframework.ai.chat.messages.Message> historyMessages, String memorySummary) {
        return queryRewriteService.rewrite(query, historyMessages, memorySummary);
    }

    private QueryIntent classifyQuery(String query, ConversationMemory memory) {
        try {
            String result = chatClientBuilder.build().prompt()
                    .system(ChatPromptTemplates.QUERY_INTENT_SYSTEM_PROMPT + buildSummaryPrompt(memory.summary()))
                    .messages(memory.recentMessages())
                    .user(query)
                    .call()
                    .content();

            return normalizeQueryIntent(result, query);
        } catch (Exception e) {
            log.warn("Query 分类失败，回退到启发式规则: query={}", query, e);
            return inferQueryIntentHeuristically(query);
        }
    }

    private QueryIntent normalizeQueryIntent(String raw, String fallbackQuery) {
        return switch (queryUnderstandingService.normalizeQueryIntent(raw, fallbackQuery, false)) {
            case AMBIGUOUS -> QueryIntent.AMBIGUOUS;
            case BROAD -> QueryIntent.BROAD;
            case DIRECT -> QueryIntent.DIRECT;
        };
    }

    private QueryIntent inferQueryIntentHeuristically(String query) {
        return switch (queryUnderstandingService.inferQueryIntentHeuristically(query, false)) {
            case AMBIGUOUS -> QueryIntent.AMBIGUOUS;
            case BROAD -> QueryIntent.BROAD;
            case DIRECT -> QueryIntent.DIRECT;
        };
    }

    private String generateHydeDocument(String query, ConversationMemory memory) {
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

    private List<String> decomposeQuery(String query, ConversationMemory memory) {
        try {
            String result = chatClientBuilder.build().prompt()
                    .system(ChatPromptTemplates.DECOMPOSITION_SYSTEM_PROMPT)
                    .messages(memory.recentMessages())
                    .user(query)
                    .call()
                    .content();

            return normalizeDecomposedQueries(result, query);
        } catch (Exception e) {
            log.warn("Query 拆解失败，回退到单查询: query={}", query, e);
            return List.of(query);
        }
    }

    private List<String> normalizeDecomposedQueries(String raw, String fallbackQuery) {
        return queryUnderstandingService.normalizeDecomposedQueries(raw, fallbackQuery, getMaxDecomposedQueries());
    }

    private String summarizeConversation(List<Message> messages) {
        if (messages.isEmpty()) {
            return null;
        }

        try {
            String transcript = messages.stream()
                    .map(message -> message.getRole() + "：" + message.getContent())
                    .collect(Collectors.joining("\n"));

            String summary = chatClientBuilder.build().prompt()
                    .system(ChatPromptTemplates.SUMMARY_SYSTEM_PROMPT)
                    .user(transcript)
                    .call()
                    .content();

            return normalizeConversationSummary(summary, messages);
        } catch (Exception e) {
            log.warn("对话摘要生成失败，回退到规则摘要", e);
            return normalizeConversationSummary(null, messages);
        }
    }

    private String normalizeConversationSummary(String summary, List<Message> messages) {
        return conversationMemoryService.normalizeConversationSummary(summary, messages, getSummaryMaxLength());
    }

    private String buildSummaryPrompt(String memorySummary) {
        return queryUnderstandingService.buildSummaryPrompt(memorySummary);
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

    private List<Document> rerankDocumentsWithModel(String query, List<Document> ruleRanked, int finalTopK) {
        int modelWindowSize = Math.min(ruleRanked.size(), getModelRerankTopK());
        if (modelWindowSize <= 1) {
            return ruleRanked;
        }

        List<Document> modelWindow = new ArrayList<>(ruleRanked.subList(0, modelWindowSize));
        try {
            String result = chatClientBuilder.build().prompt()
                    .system("""
                            你是RAG检索重排器。
                            你的任务是根据用户问题，对候选片段按“最有助于回答问题”的顺序重排。
                            评估标准：
                            1. 与问题直接相关
                            2. 能提供更完整、更精确的事实
                            3. 来源信息明确
                            4. 避免重复语义
                            只输出候选编号，使用英文逗号分隔，例如：2,1,3
                            不要输出解释，不要输出编号之外的内容。
                            """)
                    .user(buildModelRerankPrompt(query, modelWindow, finalTopK))
                    .call()
                    .content();

            return applyModelRerankResult(modelWindow, ruleRanked, result, finalTopK);
        } catch (Exception e) {
            log.warn("模型式 Rerank 失败，回退到规则重排: query={}", query, e);
            return ruleRanked;
        }
    }

    private String buildModelRerankPrompt(String query, List<Document> candidates, int finalTopK) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("用户问题：").append(query).append("\n");
        prompt.append("请从以下候选片段中选出最相关的前")
                .append(Math.min(finalTopK, candidates.size()))
                .append("个，并按相关性从高到低排序。\n\n");

        for (int i = 0; i < candidates.size(); i++) {
            Document document = candidates.get(i);
            prompt.append("候选").append(i + 1).append("：\n")
                    .append("标题：").append(getMetadataString(document, "title", "未知文章")).append("\n")
                    .append("标识：").append(getMetadataString(document, "sourceId", "未知标识")).append("\n")
                    .append("片段：").append(truncateForModelRerank(document.getText())).append("\n\n");
        }

        prompt.append("只输出编号列表。");
        return prompt.toString();
    }

    private String truncateForModelRerank(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 220) {
            return normalized;
        }
        return normalized.substring(0, 220) + "...";
    }

    private List<Document> applyModelRerankResult(List<Document> modelWindow,
                                                  List<Document> ruleRanked,
                                                  String rawOrder,
                                                  int finalTopK) {
        List<Integer> order = parseModelRerankOrder(rawOrder, modelWindow.size());
        if (order.isEmpty()) {
            return ruleRanked;
        }

        List<Document> ordered = new ArrayList<>();
        Set<String> consumedKeys = new LinkedHashSet<>();
        int scoreSeed = modelWindow.size();

        for (Integer index : order) {
            Document document = modelWindow.get(index);
            ordered.add(document.mutate()
                    .metadata("score", (double) scoreSeed--)
                    .metadata("scoreLabel", "模型重排得分")
                    .metadata("retrievalSource", "model-rerank")
                    .build());
            consumedKeys.add(buildDocumentKey(document));
        }

        for (Document document : modelWindow) {
            String key = buildDocumentKey(document);
            if (consumedKeys.add(key)) {
                ordered.add(document);
            }
        }

        for (int i = modelWindow.size(); i < ruleRanked.size(); i++) {
            Document document = ruleRanked.get(i);
            String key = buildDocumentKey(document);
            if (consumedKeys.add(key)) {
                ordered.add(document);
            }
        }

        return ordered.stream().limit(finalTopK).collect(Collectors.toList());
    }

    private List<Integer> parseModelRerankOrder(String rawOrder, int candidateSize) {
        if (rawOrder == null || rawOrder.isBlank()) {
            return List.of();
        }

        Set<Integer> orderedIndexes = new LinkedHashSet<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\d+").matcher(rawOrder);
        while (matcher.find()) {
            int oneBasedIndex = Integer.parseInt(matcher.group());
            if (oneBasedIndex >= 1 && oneBasedIndex <= candidateSize) {
                orderedIndexes.add(oneBasedIndex - 1);
            }
        }
        return new ArrayList<>(orderedIndexes);
    }

    private double computeRerankScore(Document document, String normalizedQuery, List<String> keywords) {
        String title = normalizeForMatch(getMetadataString(document, "title", ""));
        String text = normalizeForMatch(document.getText());
        double baseScore = getMetadataDouble(document, "score", 0.0d);
        String retrievalSource = getMetadataString(document, "retrievalSource", "");

        double score = baseScore * 4.0d;
        if (!normalizedQuery.isBlank()) {
            if (!title.isBlank() && title.contains(normalizedQuery)) {
                score += 4.0d;
            }
            if (!text.isBlank() && text.contains(normalizedQuery)) {
                score += 3.0d;
            }
        }

        int matchedKeywords = 0;
        int effectiveKeywords = 0;
        for (String keyword : keywords) {
            String normalizedKeyword = normalizeForMatch(keyword);
            if (normalizedKeyword.isBlank() || normalizedKeyword.equals(normalizedQuery)) {
                continue;
            }
            effectiveKeywords++;
            if (!title.isBlank() && title.contains(normalizedKeyword)) {
                matchedKeywords++;
                score += 1.8d;
                continue;
            }
            if (!text.isBlank() && text.contains(normalizedKeyword)) {
                matchedKeywords++;
                score += 1.1d;
            }
        }

        if (effectiveKeywords > 0) {
            score += ((double) matchedKeywords / effectiveKeywords) * 2.5d;
        }
        if ("hybrid".equals(retrievalSource)) {
            score += 0.5d;
        } else if ("keyword".equals(retrievalSource)) {
            score += 0.2d;
        }
        return score;
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

    private boolean isSimpleFactQuery(String normalizedQuery, List<String> keywords) {
        if (normalizedQuery.isBlank()) {
            return false;
        }

        int atomicKeywordCount = (int) keywords.stream()
                .map(this::normalizeForMatch)
                .filter(keyword -> !keyword.isBlank())
                .filter(keyword -> !keyword.equals(normalizedQuery))
                .count();
        boolean shortQuery = normalizedQuery.length() <= 24;
        boolean smallKeywordSet = atomicKeywordCount <= 3;
        boolean hasFactCue = normalizedQuery.matches(".*(多少|几|谁|哪(个|位|一)|什么|何时|什么时候|多大|多久|默认端口|default|port|where|when|who|what).*");
        boolean hasComplexCue = normalizedQuery.matches(".*(为什么|原理|流程|步骤|区别|对比|比较|优缺点|总结|分析|实现|怎么|如何).*");
        return shortQuery && smallKeywordSet && hasFactCue && !hasComplexCue;
    }

    private boolean isComplexQuery(Session session, String normalizedQuery, List<String> keywords) {
        if (normalizedQuery.isBlank()) {
            return false;
        }

        boolean hasComplexCue = normalizedQuery.matches(".*(为什么|原理|流程|步骤|区别|对比|比较|优缺点|总结|分析|实现|怎么|如何|review|tradeoff|architecture).*");
        boolean longQuery = normalizedQuery.length() >= 24;
        boolean manyKeywords = keywords.size() >= 5;
        boolean allArticlesSession = session != null && SessionType.isAllArticles(session.getSessionType());
        return hasComplexCue || longQuery || manyKeywords || allArticlesSession;
    }

    private void accumulateHybridScores(List<Document> documents,
                                        String source,
                                        double weight,
                                        Map<String, Document> documentByKey,
                                        Map<String, Double> fusedScores,
                                        Map<String, Set<String>> sourceByKey) {
        for (int i = 0; i < documents.size(); i++) {
            Document document = documents.get(i);
            String key = buildDocumentKey(document);
            documentByKey.putIfAbsent(key, document);
            fusedScores.merge(key, weight / (HYBRID_RRF_K + i + 1), Double::sum);
            sourceByKey.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(source);
        }
    }

    private String buildDocumentKey(Document document) {
        return buildChunkKey(
                getMetadataString(document, "sourceId", ""),
                getMetadataInt(document, "chunkIndex", -1),
                document.getText()
        );
    }

    private String buildChunkKey(String sourceId, int chunkIndex, String text) {
        return sourceId + "#" + chunkIndex + "#" + Objects.hashCode(text);
    }

    private String normalizeForMatch(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .trim();
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

    private boolean shouldUseLlmFallback(double decisionConfidence) {
        return currentRoutingPolicy().shouldUseLlmFallback(decisionConfidence);
    }

    private boolean shouldUseHyde(String intentName, double ambiguityScore) {
        return currentRoutingPolicy().shouldUseHyde(intentName, ambiguityScore);
    }

    private boolean shouldUseDecomposition(String intentName, double breadthScore) {
        return currentRoutingPolicy().shouldUseDecomposition(intentName, breadthScore);
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
