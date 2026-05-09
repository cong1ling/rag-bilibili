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

    private record ResponseConfidence(
            String label,
            double score,
            boolean knowledgeGap
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
                QueryPlan queryPlan = understandQuery(content, memory);
                List<Document> relevantDocs = retrieveRelevantDocuments(session, queryPlan, userId);
                ResponseConfidence confidence = evaluateConfidence(relevantDocs);

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
        if (documents.isEmpty()) {
            return "没有找到相关的文章内容。";
        }

        StringBuilder context = new StringBuilder();
        context.append("以下是检索到的相关文章片段，请优先依据片段头部的来源信息进行回答：\n\n");

        for (int i = 0; i < documents.size(); i++) {
            Document doc = documents.get(i);
            context.append(buildSourceHeader(doc, i + 1)).append("\n");
            context.append(doc.getText());
            context.append("\n\n");
        }

        return context.toString();
    }

    private ConversationMemory buildConversationMemory(Session session, List<Message> messages, Long excludeId) {
        List<Message> filtered = messages.stream()
                .filter(m -> !m.getId().equals(excludeId))
                .sorted(Comparator.comparing(Message::getCreateTime))
                .collect(Collectors.toList());

        if (!isSummaryEnabled() || filtered.size() <= getSummaryTriggerMessages()) {
            return new ConversationMemory(
                    buildMessageHistory(filtered, null),
                    null,
                    false
            );
        }

        int recentCount = Math.min(getSummaryRecentMessages(), filtered.size());
        List<Message> olderMessages = filtered.subList(0, filtered.size() - recentCount);
        List<Message> recentMessages = filtered.subList(filtered.size() - recentCount, filtered.size());
        String summary = session == null ? null : session.getConversationSummary();
        if (summary == null || summary.isBlank()) {
            summary = summarizeConversation(olderMessages);
        }

        return new ConversationMemory(
                buildMessageHistory(recentMessages, null),
                summary,
                summary != null && !summary.isBlank()
        );
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

    private QueryPlan understandQuery(String query, ConversationMemory memory) {
        String rewrittenQuery = rewriteQuery(query, memory.recentMessages(), memory.summary());
        if (!isQueryUnderstandingEnabled()) {
            return new QueryPlan(
                    QueryIntent.DIRECT,
                    query,
                    rewrittenQuery,
                    List.of(new RetrievalQuery(rewrittenQuery, rewrittenQuery, "direct"))
            );
        }

        QueryIntent intent = classifyQuery(rewrittenQuery, memory);
        if (intent == QueryIntent.AMBIGUOUS && isHydeEnabled()) {
            String hydeDocument = generateHydeDocument(rewrittenQuery, memory);
            return new QueryPlan(
                    intent,
                    query,
                    rewrittenQuery,
                    List.of(
                            new RetrievalQuery(rewrittenQuery, rewrittenQuery, "direct"),
                            new RetrievalQuery(hydeDocument, null, "hyde")
                    )
            );
        }

        if (intent == QueryIntent.BROAD && isDecompositionEnabled()) {
            List<String> subQueries = decomposeQuery(rewrittenQuery, memory);
            if (subQueries.size() > 1) {
                return new QueryPlan(
                        intent,
                        query,
                        rewrittenQuery,
                        subQueries.stream()
                                .map(subQuery -> new RetrievalQuery(subQuery, subQuery, "subquery"))
                                .collect(Collectors.toList())
                );
            }
        }

        return new QueryPlan(
                QueryIntent.DIRECT,
                query,
                rewrittenQuery,
                List.of(new RetrievalQuery(rewrittenQuery, rewrittenQuery, "direct"))
        );
    }

    /**
     * 构建聊天历史（滑动窗口，转换为 Spring AI Message 类型）
     */
    private List<org.springframework.ai.chat.messages.Message> buildMessageHistory(
            List<Message> messages, Long excludeId) {
        List<Message> filtered = messages.stream()
                .filter(m -> !m.getId().equals(excludeId))
                .sorted(Comparator.comparing(Message::getCreateTime))
                .collect(Collectors.toList());

        int start = Math.max(0, filtered.size() - getMaxHistory());
        return filtered.subList(start, filtered.size()).stream()
                .map(m -> m.getRole().equals(MessageRole.USER.getCode())
                        ? new UserMessage(m.getContent())
                        : new AssistantMessage(m.getContent()))
                .collect(Collectors.toList());
    }

    /**
     * 构建系统提示词
     */
    private String buildSystemPrompt(String context) {
        return buildSystemPrompt(context, null, new ResponseConfidence("MEDIUM", 0.5d, false));
    }

    private String buildSystemPrompt(String context, String memorySummary, ResponseConfidence confidence) {
        String memorySection = (memorySummary == null || memorySummary.isBlank())
                ? ""
                : "\n对话摘要（优先作为历史背景，不可覆盖检索事实）：\n" + memorySummary + "\n";
        String confidenceSection = (confidence == null || !isConfidenceAwareEnabled())
                ? ""
                : switch (confidence.label()) {
                    case "HIGH" -> "\n当前检索置信度：高。回答时直接给出结论并附来源。\n";
                    case "LOW" -> "\n当前检索置信度：低。回答时必须明确标注“仅供参考”，并说明信息可能不足。\n";
                    default -> "\n当前检索置信度：中。回答时保持审慎，关键结论必须带来源。\n";
                };
        return String.format(
                "你是一个基于CSDN文章内容的智能问答助手。\n\n" +
                "你的任务是根据提供的文章内容片段，准确、克制地回答用户的问题。\n\n" +
                "注意事项：\n" +
                "1. 仅基于提供的文章内容片段回答，不要补充片段之外的事实\n" +
                "2. 回答中的关键结论后要附上来源，优先使用“(来源: 文章名 片段x/y)”格式\n" +
                "3. 如果多个片段存在冲突，单独列出“不一致信息”并说明各自来源\n" +
                "4. 如果信息不足以完整回答，请明确说明“根据当前检索片段，无法完整回答该问题”，再给出已知部分\n" +
                "5. 回答要准确、简洁、有条理，避免编造\n" +
                "%s" +
                "%s\n" +
                "%s",
                memorySection,
                confidenceSection,
                context
        );
    }

    private String rewriteQuery(String query, List<org.springframework.ai.chat.messages.Message> historyMessages) {
        return rewriteQuery(query, historyMessages, null);
    }

    private String rewriteQuery(String query, List<org.springframework.ai.chat.messages.Message> historyMessages, String memorySummary) {
        if (!isQueryRewriteEnabled() || historyMessages.isEmpty()) {
            return query;
        }

        try {
            String rewritten = chatClientBuilder.build().prompt()
                    .system("""
                            你是一个RAG检索查询改写助手。
                            你的任务是结合对话历史，将用户当前问题改写为适合向量检索的独立查询。
                            要求：
                            1. 保留原问题意图，不要扩写无关信息
                            2. 补全代词、省略和上下文指代
                            3. 如果原问题已经独立完整，则原样返回
                            4. 只输出最终查询，不要解释
                            """
                            + buildSummaryPrompt(memorySummary))
                    .messages(historyMessages)
                    .user(query)
                    .call()
                    .content();

            String normalized = normalizeRewrittenQuery(query, rewritten);
            if (!normalized.equals(query)) {
                log.info("查询改写完成: original={}, rewritten={}", query, normalized);
            }
            return normalized;
        } catch (Exception e) {
            log.warn("查询改写失败，回退到原始问题: query={}", query, e);
            return query;
        }
    }

    private QueryIntent classifyQuery(String query, ConversationMemory memory) {
        try {
            String result = chatClientBuilder.build().prompt()
                    .system("""
                            你是RAG查询路由器。
                            请将用户问题只分类为以下三类之一：
                            DIRECT：问题清晰明确，可直接检索。
                            AMBIGUOUS：问题较短、语义不完整、存在指代或语义间隙，适合先做 HyDE。
                            BROAD：问题范围宽，需要拆成3到5个互补子问题分别检索。
                            只输出 DIRECT、AMBIGUOUS、BROAD 之一，不要解释。
                            """
                            + buildSummaryPrompt(memory.summary()))
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
        if (raw == null || raw.isBlank()) {
            return inferQueryIntentHeuristically(fallbackQuery);
        }

        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        if (normalized.contains("AMBIGUOUS")) {
            return QueryIntent.AMBIGUOUS;
        }
        if (normalized.contains("BROAD")) {
            return QueryIntent.BROAD;
        }
        if (normalized.contains("DIRECT")) {
            return QueryIntent.DIRECT;
        }
        return inferQueryIntentHeuristically(fallbackQuery);
    }

    private QueryIntent inferQueryIntentHeuristically(String query) {
        String normalized = normalizeForMatch(query);
        if (normalized.isBlank()) {
            return QueryIntent.DIRECT;
        }

        boolean ambiguousCue = normalized.matches(".*(它|这个|那个|这部分|这里|那里|上面|前面|后面|其|该|this|that|it|they).*");
        boolean shortQuery = normalized.length() <= 18;
        if (ambiguousCue && shortQuery) {
            return QueryIntent.AMBIGUOUS;
        }
        if (isComplexQuery(null, normalized, extractKeywords(query))) {
            return QueryIntent.BROAD;
        }
        return QueryIntent.DIRECT;
    }

    private String generateHydeDocument(String query, ConversationMemory memory) {
        try {
            String hyde = chatClientBuilder.build().prompt()
                    .system("""
                            你是 HyDE 假设文档生成助手。
                            请基于用户问题，生成一段适合用于向量检索的“理想答案式说明文”。
                            要求：
                            1. 120到200字
                            2. 只写可能相关的知识描述，不要出现“假设”“可能”“我认为”
                            3. 不要输出列表，不要解释任务
                            """)
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
                    .system("""
                            你是复杂问题拆解助手。
                            请将用户问题拆成3到5个互补、去重、可检索的子问题。
                            要求：
                            1. 每行一个子问题
                            2. 子问题之间不要重复
                            3. 只输出子问题列表，不要解释
                            """)
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
        if (raw == null || raw.isBlank()) {
            return List.of(fallbackQuery);
        }

        List<String> subQueries = raw.lines()
                .map(String::trim)
                .map(line -> line.replaceFirst("^[-*\\d.、)）\\s]+", ""))
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .distinct()
                .limit(getMaxDecomposedQueries())
                .collect(Collectors.toList());

        return subQueries.isEmpty() ? List.of(fallbackQuery) : subQueries;
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
                    .system("""
                            你是对话记忆压缩助手。
                            请将旧对话压缩为不超过150字的摘要，保留：
                            1. 关键实体
                            2. 已确认事实
                            3. 仍在追问的主题
                            只输出摘要，不要解释。
                            """)
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
        if (summary != null && !summary.isBlank()) {
            String normalized = summary.trim().replaceAll("\\s+", " ");
            return normalized.length() <= getSummaryMaxLength()
                    ? normalized
                    : normalized.substring(0, getSummaryMaxLength());
        }

        String fallback = messages.stream()
                .skip(Math.max(0, messages.size() - 4))
                .map(message -> message.getRole() + ":" + message.getContent())
                .collect(Collectors.joining("；"));
        if (fallback.length() <= getSummaryMaxLength()) {
            return fallback;
        }
        return fallback.substring(0, getSummaryMaxLength());
    }

    private String buildSummaryPrompt(String memorySummary) {
        if (memorySummary == null || memorySummary.isBlank()) {
            return "";
        }
        return "\n历史摘要如下，请在理解当前问题时参考，但不要把它当成检索证据：\n" + memorySummary + "\n";
    }

    private String normalizeRewrittenQuery(String originalQuery, String rewritten) {
        if (rewritten == null || rewritten.isBlank()) {
            return originalQuery;
        }

        String normalized = rewritten.trim()
                .replaceFirst("^(改写后的查询|改写后的问题|检索查询|重写后的问题)[:：]\\s*", "")
                .trim();

        if ((normalized.startsWith("\"") && normalized.endsWith("\""))
                || (normalized.startsWith("“") && normalized.endsWith("”"))) {
            normalized = normalized.substring(1, normalized.length() - 1).trim();
        }

        return normalized.isBlank() ? originalQuery : normalized;
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
        if (query == null || query.isBlank()) {
            return List.of();
        }

        String normalized = query.trim();
        Set<String> keywords = new LinkedHashSet<>();
        if (normalized.length() >= 2) {
            keywords.add(normalized);
        }

        for (String part : normalized.split("[^\\p{L}\\p{N}\\u4E00-\\u9FFF]+")) {
            String token = part.trim();
            if (token.length() >= 2) {
                keywords.add(token);
            }
            if (keywords.size() >= 6) {
                break;
            }
        }

        return new ArrayList<>(keywords);
    }

    private String buildKeywordSearchText(List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return "";
        }

        return keywords.stream()
                .map(this::sanitizeFullTextTerm)
                .filter(term -> !term.isBlank())
                .distinct()
                .limit(6)
                .collect(Collectors.joining(" "));
    }

    private String sanitizeFullTextTerm(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }

        String sanitized = input
                .replaceAll("[^\\p{L}\\p{N}\\u4E00-\\u9FFF]+", " ")
                .replaceAll("\\s+", " ")
                .trim();

        if (sanitized.length() < 2) {
            return "";
        }
        return sanitized;
    }

    private List<Document> mergeHybridResults(List<Document> vectorResults, List<Document> keywordResults, int limit) {
        if (keywordResults.isEmpty()) {
            return vectorResults.stream().limit(limit).collect(Collectors.toList());
        }
        if (vectorResults.isEmpty()) {
            return keywordResults.stream()
                    .limit(limit)
                    .map(document -> document.mutate().metadata("score", 1.0d).build())
                    .collect(Collectors.toList());
        }

        Map<String, Document> documentByKey = new LinkedHashMap<>();
        Map<String, Double> fusedScores = new LinkedHashMap<>();
        Map<String, Set<String>> sourceByKey = new LinkedHashMap<>();

        accumulateHybridScores(vectorResults, "vector", 1.0d, documentByKey, fusedScores, sourceByKey);
        accumulateHybridScores(keywordResults, "keyword", 0.7d, documentByKey, fusedScores, sourceByKey);

        return fusedScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(limit)
                .map(entry -> {
                    Document base = documentByKey.get(entry.getKey());
                    Set<String> sources = sourceByKey.getOrDefault(entry.getKey(), Set.of());
                    String label = sources.size() > 1 ? "融合得分" : sources.contains("keyword") ? "关键词得分" : "相似度";
                    String retrievalSource = sources.size() > 1 ? "hybrid" : sources.stream().findFirst().orElse("vector");
                    return base.mutate()
                            .metadata("score", entry.getValue())
                            .metadata("scoreLabel", label)
                            .metadata("retrievalSource", retrievalSource)
                            .build();
                })
                .collect(Collectors.toList());
    }

    private List<Document> mergeQueryCandidates(List<List<Document>> rankedLists, int limit) {
        if (rankedLists.isEmpty()) {
            return List.of();
        }
        if (rankedLists.size() == 1) {
            return rankedLists.get(0).stream().limit(limit).collect(Collectors.toList());
        }

        Map<String, Document> documentByKey = new LinkedHashMap<>();
        Map<String, Double> fusedScores = new LinkedHashMap<>();
        Map<String, Set<String>> sourceByKey = new LinkedHashMap<>();

        for (int i = 0; i < rankedLists.size(); i++) {
            accumulateHybridScores(
                    rankedLists.get(i),
                    "query-" + i,
                    1.0d,
                    documentByKey,
                    fusedScores,
                    sourceByKey
            );
        }

        return fusedScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(limit)
                .map(entry -> documentByKey.get(entry.getKey()).mutate()
                        .metadata("score", entry.getValue())
                        .metadata("scoreLabel", "多查询融合得分")
                        .metadata("retrievalSource", "multi-query")
                        .build())
                .collect(Collectors.toList());
    }

    private List<Document> rerankDocuments(String query, List<Document> candidates, int finalTopK) {
        if (candidates.isEmpty()) {
            return candidates;
        }

        List<String> keywords = extractKeywords(query);
        String normalizedQuery = normalizeForMatch(query);

        List<Document> ruleRanked = candidates.stream()
                .limit(getCandidateTopK(finalTopK))
                .map(document -> {
                    double rerankScore = computeRerankScore(document, normalizedQuery, keywords);
                    return document.mutate()
                            .metadata("score", rerankScore)
                            .metadata("scoreLabel", "重排得分")
                            .metadata("retrievalSource", "rerank")
                            .build();
                })
                .sorted(Comparator
                        .comparingDouble((Document document) -> getMetadataDouble(document, "score", 0.0d))
                        .reversed()
                        .thenComparing(document -> getMetadataString(document, "title", ""), Comparator.reverseOrder()))
                .limit(finalTopK)
                .collect(Collectors.toList());

        if (!isModelRerankEnabled() || ruleRanked.size() <= 1) {
            return ruleRanked;
        }
        return rerankDocumentsWithModel(query, ruleRanked, finalTopK);
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

    private ResponseConfidence evaluateConfidence(List<Document> documents) {
        if (!isConfidenceAwareEnabled()) {
            return new ResponseConfidence("MEDIUM", 0.5d, false);
        }
        if (documents == null || documents.isEmpty()) {
            return new ResponseConfidence("LOW", 0.0d, true);
        }

        double topScore = getMetadataDouble(documents.get(0), "score", 0.0d);
        double normalizedScore = Math.min(1.0d, topScore / 10.0d);
        if (documents.size() >= 3 && topScore >= 8.0d) {
            return new ResponseConfidence("HIGH", normalizedScore, false);
        }
        if (topScore >= 5.0d || documents.size() >= 2) {
            return new ResponseConfidence("MEDIUM", Math.max(0.5d, normalizedScore), false);
        }
        return new ResponseConfidence("LOW", Math.max(0.2d, normalizedScore), true);
    }

    private int determineTopK(Session session, String query) {
        int defaultTopK = getConfiguredTopK();
        if (!isDynamicTopKEnabled()) {
            return defaultTopK;
        }

        RoutingAnalysis routingAnalysis = analyzeRouting(
                query,
                new ConversationMemory(List.of(), null, false)
        );

        if (routingAnalysis.complexityScore() >= getComplexityThreshold()
                || routingAnalysis.suggestedIntent() != QueryIntent.DIRECT
                || (session != null && SessionType.isAllArticles(session.getSessionType()))) {
            return getComplexTopK(defaultTopK);
        }
        if (isSimpleFactQuery(normalizeForMatch(query), extractKeywords(query))
                && routingAnalysis.complexityScore() < getComplexityThreshold()) {
            return getSimpleTopK(defaultTopK);
        }
        return getNormalTopK(defaultTopK);
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

