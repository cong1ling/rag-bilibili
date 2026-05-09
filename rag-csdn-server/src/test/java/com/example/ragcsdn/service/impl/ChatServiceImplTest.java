package com.example.ragcsdn.service.impl;

import com.example.ragcsdn.config.ChatOptimizationProperties;
import com.example.ragcsdn.entity.Message;
import com.example.ragcsdn.entity.Session;
import com.example.ragcsdn.enums.MessageRole;
import com.example.ragcsdn.enums.SessionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ChatServiceImpl.buildMessageHistory() 的单元测试。
 *
 * <p>由于 buildMessageHistory() 是私有方法（private），无法直接调用。
 * 这里使用 Java 反射（Reflection）机制绕过访问限制，直接测试该私有方法的逻辑。
 *
 * <p>反射调用步骤：
 *   1. 通过 Class.getDeclaredMethod() 获取私有方法对象
 *   2. 调用 setAccessible(true) 解除访问限制
 *   3. 通过 Method.invoke(实例, 参数...) 执行方法并获取返回值
 *
 * <p>测试覆盖场景：
 *   - 空消息列表
 *   - 当前用户消息被正确排除（excludeId 过滤）
 *   - 消息角色正确映射为 Spring AI 的 UserMessage / AssistantMessage
 *   - 滑动窗口截取最近 MAX_HISTORY(10) 条
 *   - 消息按 createTime 升序排列
 */
class ChatServiceImplTest {

    /** 被测对象，直接 new 即可，不需要 Spring 容器（无需 @SpringBootTest） */
    private ChatServiceImpl chatService;

    /**
     * 通过反射持有 buildMessageHistory 方法引用，在所有测试中复用。
     * 声明为字段是为了避免每个测试方法重复获取。
     */
    private Method buildMessageHistory;
    private Method buildContext;
    private Method buildSystemPrompt;
    private Method normalizeRewrittenQuery;
    private Method extractKeywords;
    private Method buildKeywordSearchText;
    private Method normalizeDecomposedQueries;
    private Method inferQueryIntentHeuristically;
    private Method mergeHybridResults;
    private Method rerankDocuments;
    private Method determineTopK;
    private Method shouldUseLlmFallback;
    private Method shouldUseHyde;
    private Method shouldUseDecomposition;

    /**
     * 每个测试方法执行前初始化：
     * 1. 创建 ChatServiceImpl 实例
     * 2. 获取私有方法 buildMessageHistory 的反射句柄并解除访问限制
     */
    @BeforeEach
    void setUp() throws Exception {
        chatService = new ChatServiceImpl();
        // getDeclaredMethod 可获取私有方法，参数为方法名 + 参数类型列表
        buildMessageHistory = ChatServiceImpl.class.getDeclaredMethod(
                "buildMessageHistory", List.class, Long.class);
        buildContext = ChatServiceImpl.class.getDeclaredMethod("buildContext", List.class);
        buildSystemPrompt = ChatServiceImpl.class.getDeclaredMethod("buildSystemPrompt", String.class);
        normalizeRewrittenQuery = ChatServiceImpl.class.getDeclaredMethod(
                "normalizeRewrittenQuery", String.class, String.class);
        extractKeywords = ChatServiceImpl.class.getDeclaredMethod("extractKeywords", String.class);
        buildKeywordSearchText = ChatServiceImpl.class.getDeclaredMethod("buildKeywordSearchText", List.class);
        normalizeDecomposedQueries = ChatServiceImpl.class.getDeclaredMethod(
                "normalizeDecomposedQueries", String.class, String.class);
        inferQueryIntentHeuristically = ChatServiceImpl.class.getDeclaredMethod(
                "inferQueryIntentHeuristically", String.class);
        mergeHybridResults = ChatServiceImpl.class.getDeclaredMethod(
                "mergeHybridResults", List.class, List.class, int.class);
        rerankDocuments = ChatServiceImpl.class.getDeclaredMethod(
                "rerankDocuments", String.class, List.class, int.class);
        determineTopK = ChatServiceImpl.class.getDeclaredMethod(
                "determineTopK", Session.class, String.class);
        shouldUseLlmFallback = ChatServiceImpl.class.getDeclaredMethod(
                "shouldUseLlmFallback", double.class);
        shouldUseHyde = ChatServiceImpl.class.getDeclaredMethod(
                "shouldUseHyde", String.class, double.class);
        shouldUseDecomposition = ChatServiceImpl.class.getDeclaredMethod(
                "shouldUseDecomposition", String.class, double.class);
        // setAccessible(true) 允许在类外部调用私有方法
        buildMessageHistory.setAccessible(true);
        buildContext.setAccessible(true);
        buildSystemPrompt.setAccessible(true);
        normalizeRewrittenQuery.setAccessible(true);
        extractKeywords.setAccessible(true);
        buildKeywordSearchText.setAccessible(true);
        normalizeDecomposedQueries.setAccessible(true);
        inferQueryIntentHeuristically.setAccessible(true);
        mergeHybridResults.setAccessible(true);
        rerankDocuments.setAccessible(true);
        determineTopK.setAccessible(true);
        shouldUseLlmFallback.setAccessible(true);
        shouldUseHyde.setAccessible(true);
        shouldUseDecomposition.setAccessible(true);
    }

    /**
     * 通过反射调用 buildMessageHistory 的便捷封装，避免每个测试都写 try-catch 和强转。
     *
     * @param messages  传入的消息列表
     * @param excludeId 需要排除的消息 ID（当前用户刚发送的消息）
     * @return Spring AI Message 列表
     */
    @SuppressWarnings("unchecked")
    private List<org.springframework.ai.chat.messages.Message> invoke(
            List<Message> messages, Long excludeId) throws Exception {
        return (List<org.springframework.ai.chat.messages.Message>)
                buildMessageHistory.invoke(chatService, messages, excludeId);
    }

    private String invokeBuildContext(List<Document> documents) throws Exception {
        return (String) buildContext.invoke(chatService, documents);
    }

    private String invokeBuildSystemPrompt(String context) throws Exception {
        return (String) buildSystemPrompt.invoke(chatService, context);
    }

    private String invokeNormalizeRewrittenQuery(String originalQuery, String rewritten) throws Exception {
        return (String) normalizeRewrittenQuery.invoke(chatService, originalQuery, rewritten);
    }

    @SuppressWarnings("unchecked")
    private List<String> invokeExtractKeywords(String query) throws Exception {
        return (List<String>) extractKeywords.invoke(chatService, query);
    }

    private String invokeBuildKeywordSearchText(List<String> keywords) throws Exception {
        return (String) buildKeywordSearchText.invoke(chatService, keywords);
    }

    @SuppressWarnings("unchecked")
    private List<String> invokeNormalizeDecomposedQueries(String raw, String fallbackQuery) throws Exception {
        return (List<String>) normalizeDecomposedQueries.invoke(chatService, raw, fallbackQuery);
    }

    private String invokeInferQueryIntentHeuristically(String query) throws Exception {
        return String.valueOf(inferQueryIntentHeuristically.invoke(chatService, query));
    }

    @SuppressWarnings("unchecked")
    private List<Document> invokeMergeHybridResults(
            List<Document> vectorResults, List<Document> keywordResults, int limit) throws Exception {
        return (List<Document>) mergeHybridResults.invoke(chatService, vectorResults, keywordResults, limit);
    }

    @SuppressWarnings("unchecked")
    private List<Document> invokeRerankDocuments(String query, List<Document> candidates, int finalTopK)
            throws Exception {
        return (List<Document>) rerankDocuments.invoke(chatService, query, candidates, finalTopK);
    }

    private int invokeDetermineTopK(Session session, String query) throws Exception {
        return (int) determineTopK.invoke(chatService, session, query);
    }

    private void setField(String fieldName, Object value) throws Exception {
        Field field = ChatServiceImpl.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(chatService, value);
    }

    private ChatOptimizationProperties defaultProperties() {
        ChatOptimizationProperties properties = new ChatOptimizationProperties();
        properties.setRuleRoutingEnabled(true);
        properties.setRoutingObservationOnly(true);
        properties.setRuleRoutingLlmFallbackEnabled(true);
        properties.setAmbiguityThreshold(0.62d);
        properties.setBreadthThreshold(0.58d);
        properties.setComplexityThreshold(0.55d);
        properties.setLlmFallbackConfidenceThreshold(0.52d);
        properties.setSimpleTopK(3);
        properties.setNormalTopK(5);
        properties.setComplexTopK(8);
        return properties;
    }

    /**
     * 快速构造测试用 Message 实体的工厂方法，减少测试代码冗余。
     */
    private Message msg(Long id, String role, String content, LocalDateTime time) {
        Message m = new Message();
        m.setId(id);
        m.setRole(role);
        m.setContent(content);
        m.setCreateTime(time);
        return m;
    }

    private Document doc(String text, String title, String sourceId, int chunkIndex,
                         int totalChunks, double score) {
        HashMap<String, Object> metadata = new HashMap<>();
        metadata.put("title", title);
        metadata.put("sourceId", sourceId);
        metadata.put("chunkIndex", chunkIndex);
        metadata.put("totalChunks", totalChunks);
        metadata.put("score", score);
        return Document.builder()
                .text(text)
                .metadata(metadata)
                .build();
    }

    private Document doc(String text, String title, String sourceId, int chunkIndex,
                         int totalChunks, double score, String scoreLabel) {
        HashMap<String, Object> metadata = new HashMap<>();
        metadata.put("title", title);
        metadata.put("sourceId", sourceId);
        metadata.put("chunkIndex", chunkIndex);
        metadata.put("totalChunks", totalChunks);
        metadata.put("score", score);
        metadata.put("scoreLabel", scoreLabel);
        return Document.builder()
                .text(text)
                .metadata(metadata)
                .build();
    }

    // =========================================================================
    // 测试用例
    // =========================================================================

    /**
     * 场景：传入空消息列表时，应返回空列表，不抛异常。
     */
    @Test
    void buildMessageHistory_emptyInput_returnsEmptyList() throws Exception {
        List<org.springframework.ai.chat.messages.Message> result = invoke(List.of(), 99L);
        assertThat(result).isEmpty();
    }

    /**
     * 场景：excludeId 指向的消息（当前用户刚发送的消息）应从历史中排除，
     * 避免将尚未得到回答的问题混入上下文历史。
     */
    @Test
    void buildMessageHistory_excludesCurrentUserMessage_byId() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        List<Message> messages = List.of(
                msg(1L, MessageRole.USER.getCode(), "历史问题", now.minusSeconds(10)),
                msg(2L, MessageRole.ASSISTANT.getCode(), "历史回答", now.minusSeconds(5)),
                msg(3L, MessageRole.USER.getCode(), "当前问题", now)  // 应被排除
        );

        List<org.springframework.ai.chat.messages.Message> result = invoke(messages, 3L);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getText()).isEqualTo("历史问题");
        assertThat(result.get(1).getText()).isEqualTo("历史回答");
    }

    /**
     * 场景：消息角色应正确映射为 Spring AI 的消息类型：
     * - USER   → org.springframework.ai.chat.messages.UserMessage
     * - ASSISTANT → org.springframework.ai.chat.messages.AssistantMessage
     * 类型正确是 LLM 能区分对话轮次的前提。
     */
    @Test
    void buildMessageHistory_mapsRoles_toCorrectSpringAiMessageTypes() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        List<Message> messages = List.of(
                msg(1L, MessageRole.USER.getCode(), "用户说", now.minusSeconds(2)),
                msg(2L, MessageRole.ASSISTANT.getCode(), "助手说", now.minusSeconds(1))
        );

        List<org.springframework.ai.chat.messages.Message> result = invoke(messages, 99L);

        assertThat(result).hasSize(2);
        assertThat(result.get(0)).isInstanceOf(UserMessage.class);
        assertThat(result.get(0).getText()).isEqualTo("用户说");
        assertThat(result.get(1)).isInstanceOf(AssistantMessage.class);
        assertThat(result.get(1).getText()).isEqualTo("助手说");
    }

    /**
     * 场景：当历史消息超过 MAX_HISTORY(10) 条时，应只保留最近的 10 条，
     * 即滑动窗口策略——丢弃最早的消息，防止 prompt 过长超出 LLM token 限制。
     */
    @Test
    void buildMessageHistory_slidingWindow_keepsLatest10Messages() throws Exception {
        LocalDateTime base = LocalDateTime.now().minusSeconds(20);
        List<Message> messages = new ArrayList<>();
        // 构造 15 条消息，createTime 依次递增
        for (int i = 1; i <= 15; i++) {
            messages.add(msg((long) i, MessageRole.USER.getCode(),
                    "消息" + i, base.plusSeconds(i)));
        }

        List<org.springframework.ai.chat.messages.Message> result = invoke(messages, 99L);

        // 只保留最近 10 条（第 6-15 条）
        assertThat(result).hasSize(10);
        assertThat(result.get(0).getText()).isEqualTo("消息6");
        assertThat(result.get(9).getText()).isEqualTo("消息15");
    }

    /**
     * 场景：无论调用方传入的 List 顺序如何，返回结果都应按 createTime 升序排列，
     * 确保 LLM 收到的对话历史时序正确。
     */
    @Test
    void buildMessageHistory_sortsMessages_byCreateTimeAscending() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        // 故意乱序传入
        List<Message> messages = List.of(
                msg(3L, MessageRole.ASSISTANT.getCode(), "第三条", now),
                msg(1L, MessageRole.USER.getCode(), "第一条", now.minusSeconds(2)),
                msg(2L, MessageRole.USER.getCode(), "第二条", now.minusSeconds(1))
        );

        List<org.springframework.ai.chat.messages.Message> result = invoke(messages, 99L);

        assertThat(result).hasSize(3);
        assertThat(result.get(0).getText()).isEqualTo("第一条");
        assertThat(result.get(1).getText()).isEqualTo("第二条");
        assertThat(result.get(2).getText()).isEqualTo("第三条");
    }

    @Test
    void buildContext_includesSourceMetadataForEachChunk() throws Exception {
        List<Document> documents = List.of(
                doc("Spring Boot 提升了开发效率。", "Spring 实战", "BV1xx411c7mu", 1, 8, 0.9123d)
        );

        String context = invokeBuildContext(documents);

        assertThat(context).contains("文章: Spring 实战");
        assertThat(context).contains("标识: BV1xx411c7mu");
        assertThat(context).contains("片段 2/8");
        assertThat(context).contains("相似度: 0.912");
        assertThat(context).contains("Spring Boot 提升了开发效率。");
    }

    @Test
    void buildContext_usesCustomScoreLabelWhenPresent() throws Exception {
        List<Document> documents = List.of(
                doc("MySQL LIKE 能补充关键词召回。", "检索优化", "BV1hybrid", 0, 4, 0.1234d, "融合得分")
        );

        String context = invokeBuildContext(documents);

        assertThat(context).contains("融合得分: 0.123");
    }

    @Test
    void buildSystemPrompt_requiresCitationConflictAndInsufficientHandling() throws Exception {
        String prompt = invokeBuildSystemPrompt("测试上下文");

        assertThat(prompt).contains("关键结论后要附上来源");
        assertThat(prompt).contains("不一致信息");
        assertThat(prompt).contains("无法完整回答该问题");
        assertThat(prompt).contains("测试上下文");
    }

    @Test
    void normalizeRewrittenQuery_stripsPrefixAndQuotes() throws Exception {
        String normalized = invokeNormalizeRewrittenQuery(
                "它的优点呢",
                "改写后的查询: \"Spring Boot 的优点是什么\""
        );

        assertThat(normalized).isEqualTo("Spring Boot 的优点是什么");
    }

    @Test
    void extractKeywords_keepsWholeQueryAndDistinctTokens() throws Exception {
        List<String> keywords = invokeExtractKeywords("Spring Boot 默认端口 是多少?");

        assertThat(keywords).contains("Spring Boot 默认端口 是多少?");
        assertThat(keywords).contains("Spring", "Boot", "默认端口", "是多少");
        assertThat(keywords).doesNotHaveDuplicates();
    }

    @Test
    void buildKeywordSearchText_removesWildcardLikeCharacters() throws Exception {
        String searchText = invokeBuildKeywordSearchText(List.of("user_id", "100%", "__proto__", "a"));

        assertThat(searchText).doesNotContain("%");
        assertThat(searchText).doesNotContain("_");
        assertThat(searchText).contains("user id");
        assertThat(searchText).contains("100");
        assertThat(searchText).doesNotContain(" a ");
    }

    @Test
    void normalizeDecomposedQueries_extractsDistinctBulletLines() throws Exception {
        List<String> queries = invokeNormalizeDecomposedQueries("""
                1. Spring Boot 的定位是什么
                2. Spring Boot 的核心特性有哪些
                3. Spring Boot 的自动配置如何工作
                """, "fallback");

        assertThat(queries).containsExactly(
                "Spring Boot 的定位是什么",
                "Spring Boot 的核心特性有哪些",
                "Spring Boot 的自动配置如何工作"
        );
    }

    @Test
    void inferQueryIntentHeuristically_marksPronounQuestionAsAmbiguous() throws Exception {
        assertThat(invokeInferQueryIntentHeuristically("它的优点呢")).isEqualTo("AMBIGUOUS");
    }

    @Test
    void inferQueryIntentHeuristically_marksBroadQuestionAsBroad() throws Exception {
        assertThat(invokeInferQueryIntentHeuristically("对比分析 Spring Boot 和 Tomcat 的架构差异")).isEqualTo("BROAD");
    }

    @Test
    void mergeHybridResults_deduplicatesByChunkAndAddsHybridScoreLabel() throws Exception {
        List<Document> vectorResults = List.of(
                doc("片段A", "视频A", "BV1A", 0, 3, 0.9d),
                doc("片段B", "视频A", "BV1A", 1, 3, 0.8d)
        );
        List<Document> keywordResults = List.of(
                doc("片段A", "视频A", "BV1A", 0, 3, 0.5d, "关键词得分"),
                doc("片段C", "视频B", "BV1B", 0, 2, 0.4d, "关键词得分")
        );

        List<Document> merged = invokeMergeHybridResults(vectorResults, keywordResults, 3);

        assertThat(merged).hasSize(3);
        assertThat(merged.get(0).getText()).isEqualTo("片段A");
        assertThat(merged.get(0).getMetadata()).containsEntry("scoreLabel", "融合得分");
        assertThat(merged.get(0).getMetadata()).containsEntry("retrievalSource", "hybrid");
        assertThat(merged.get(0).getMetadata()).containsKey("score");
        assertThat(merged).extracting(Document::getText).containsExactly("片段A", "片段B", "片段C");
    }

    @Test
    void rerankDocuments_prioritizesExactTitleAndTextMatches() throws Exception {
        List<Document> candidates = List.of(
                doc("这里主要讨论 JVM 调优。", "Java 性能优化", "BV1java", 0, 5, 0.95d, "融合得分"),
                doc("Spring Boot 默认端口是 8080，也可以通过 server.port 修改。", "Spring Boot 配置", "BV1boot", 2, 6, 0.40d, "融合得分"),
                doc("Tomcat 默认线程池参数说明。", "Tomcat 原理", "BV1tomcat", 1, 4, 0.80d, "融合得分")
        );

        List<Document> reranked = invokeRerankDocuments("Spring Boot 默认端口", candidates, 2);

        assertThat(reranked).hasSize(2);
        assertThat(reranked.get(0).getText()).contains("8080");
        assertThat(reranked.get(0).getMetadata()).containsEntry("scoreLabel", "重排得分");
        assertThat(reranked.get(0).getMetadata()).containsEntry("retrievalSource", "rerank");
    }

    @Test
    void determineTopK_returnsSimpleTopKForShortFactQuestion() throws Exception {
        Session session = new Session();
        session.setSessionType(SessionType.SINGLE_ARTICLE.getCode());

        int topK = invokeDetermineTopK(session, "Spring Boot 默认端口是多少");

        assertThat(topK).isEqualTo(3);
    }

    @Test
    void determineTopK_returnsComplexTopKForAnalysisQuestion() throws Exception {
        Session session = new Session();
        session.setSessionType(SessionType.ALL_ARTICLES.getCode());

        int topK = invokeDetermineTopK(session, "对比分析 Spring Boot 和 Tomcat 的架构差异以及适用场景");

        assertThat(topK).isEqualTo(8);
    }

    @Test
    void determineTopK_returnsNormalTopKForRegularQuestion() throws Exception {
        Session session = new Session();
        session.setSessionType(SessionType.SINGLE_ARTICLE.getCode());

        int topK = invokeDetermineTopK(session, "Spring Boot 的自动配置机制介绍一下");

        assertThat(topK).isEqualTo(5);
    }

    @Test
    void determineTopK_usesAnalyzerForSimpleQueries() throws Exception {
        setField("chatOptimizationProperties", defaultProperties());
        setField("queryComplexityAnalyzer", new QueryComplexityAnalyzer(defaultProperties()));

        int topK = invokeDetermineTopK(null, "mysql 默认端口是多少");

        assertThat(topK).isEqualTo(3);
    }

    @Test
    void determineTopK_usesAnalyzerForBroadQueries() throws Exception {
        setField("chatOptimizationProperties", defaultProperties());
        setField("queryComplexityAnalyzer", new QueryComplexityAnalyzer(defaultProperties()));

        int topK = invokeDetermineTopK(null, "分析 Spring AI 检索链路的流程、取舍与优化方式");

        assertThat(topK).isEqualTo(8);
    }

    @Test
    void ruleRouting_highConfidenceDirectQuery_skipsFallback() throws Exception {
        ChatOptimizationProperties properties = defaultProperties();
        properties.setRoutingObservationOnly(false);
        setField("chatOptimizationProperties", properties);

        boolean usedFallback = (boolean) shouldUseLlmFallback.invoke(chatService, 0.88d);

        assertThat(usedFallback).isFalse();
    }

    @Test
    void ruleRouting_lowConfidenceQuery_usesFallback() throws Exception {
        ChatOptimizationProperties properties = defaultProperties();
        properties.setRoutingObservationOnly(false);
        setField("chatOptimizationProperties", properties);

        boolean usedFallback = (boolean) shouldUseLlmFallback.invoke(chatService, 0.41d);

        assertThat(usedFallback).isTrue();
    }

    @Test
    void routeGuards_requireMatchingIntentAndThreshold() throws Exception {
        ChatOptimizationProperties properties = defaultProperties();
        properties.setRoutingObservationOnly(false);
        properties.setHydeTriggerThreshold(0.72d);
        properties.setDecompositionTriggerThreshold(0.68d);
        setField("chatOptimizationProperties", properties);

        boolean useHyde = (boolean) shouldUseHyde.invoke(chatService, "AMBIGUOUS", 0.80d);
        boolean skipHyde = (boolean) shouldUseHyde.invoke(chatService, "DIRECT", 0.80d);
        boolean useDecomposition = (boolean) shouldUseDecomposition.invoke(chatService, "BROAD", 0.78d);
        boolean skipDecomposition = (boolean) shouldUseDecomposition.invoke(chatService, "DIRECT", 0.78d);

        assertThat(useHyde).isTrue();
        assertThat(skipHyde).isFalse();
        assertThat(useDecomposition).isTrue();
        assertThat(skipDecomposition).isFalse();
    }
}

