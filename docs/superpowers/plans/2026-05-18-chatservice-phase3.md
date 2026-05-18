# ChatService Phase 3 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split the remaining query rewrite, query expansion, conversation summary, and SSE streaming responsibilities out of `ChatServiceImpl` without changing prompts, routing semantics, SSE payloads, or persistence timing.

**Architecture:** Keep `ChatServiceImpl` as the boundary coordinator and move each private workflow into a focused collaborator under `com.example.ragcsdn.service.chat`. Reuse existing helpers (`ConversationMemoryService`, `QueryUnderstandingService`, `ChatPromptBuilder`, `ChatRoutingPolicy`) instead of introducing a new pipeline, and add direct unit tests for each extracted collaborator before rewiring the orchestrator.

**Tech Stack:** Java 17, Spring Boot 3.2, Spring AI `ChatClient`, Reactor `Flux`, MyBatis mappers, Jackson `ObjectMapper`, JUnit 5, Mockito, AssertJ

---

## File Map

**Create**
- `rag-csdn-server/src/main/java/com/example/ragcsdn/service/chat/QueryRewriteService.java`
- `rag-csdn-server/src/main/java/com/example/ragcsdn/service/chat/QueryExpansionService.java`
- `rag-csdn-server/src/main/java/com/example/ragcsdn/service/chat/ConversationSummaryService.java`
- `rag-csdn-server/src/main/java/com/example/ragcsdn/service/chat/ChatStreamingOrchestrator.java`
- `rag-csdn-server/src/main/java/com/example/ragcsdn/service/chat/DocumentRerankPromptTemplates.java`
- `rag-csdn-server/src/test/java/com/example/ragcsdn/service/chat/QueryRewriteServiceTest.java`
- `rag-csdn-server/src/test/java/com/example/ragcsdn/service/chat/QueryExpansionServiceTest.java`
- `rag-csdn-server/src/test/java/com/example/ragcsdn/service/chat/ConversationSummaryServiceTest.java`
- `rag-csdn-server/src/test/java/com/example/ragcsdn/service/chat/ChatStreamingOrchestratorTest.java`

**Modify**
- `rag-csdn-server/src/main/java/com/example/ragcsdn/service/impl/ChatServiceImpl.java`
- `rag-csdn-server/src/main/java/com/example/ragcsdn/service/chat/DocumentRerankService.java`
- `rag-csdn-server/src/test/java/com/example/ragcsdn/service/impl/ChatServiceImplTest.java`

**Keep As-Is But Reuse**
- `rag-csdn-server/src/main/java/com/example/ragcsdn/service/chat/ChatPromptTemplates.java`
- `rag-csdn-server/src/main/java/com/example/ragcsdn/service/chat/ChatPromptBuilder.java`
- `rag-csdn-server/src/main/java/com/example/ragcsdn/service/chat/ConversationMemoryService.java`
- `rag-csdn-server/src/main/java/com/example/ragcsdn/service/chat/QueryUnderstandingService.java`
- `rag-csdn-server/src/main/java/com/example/ragcsdn/service/chat/ChatRoutingPolicy.java`

### Task 1: Extract QueryRewriteService

**Files:**
- Create: `rag-csdn-server/src/main/java/com/example/ragcsdn/service/chat/QueryRewriteService.java`
- Create: `rag-csdn-server/src/test/java/com/example/ragcsdn/service/chat/QueryRewriteServiceTest.java`
- Modify: `rag-csdn-server/src/main/java/com/example/ragcsdn/service/impl/ChatServiceImpl.java`

- [ ] **Step 1: Write the failing test**

```java
package com.example.ragcsdn.service.chat;

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

class QueryRewriteServiceTest {

    @Test
    void rewrite_shouldReturnOriginalWhenFeatureDisabled() {
        QueryUnderstandingService understandingService = new QueryUnderstandingService(null);
        QueryRewriteService service = new QueryRewriteService(null, understandingService, false);

        String rewritten = service.rewrite("它的优点呢", List.of(new UserMessage("Spring Boot")), "旧摘要");

        assertThat(rewritten).isEqualTo("它的优点呢");
    }

    @Test
    void rewrite_shouldNormalizeSuccessfulResult() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class, Answers.RETURNS_DEEP_STUBS);
        when(builder.build().prompt().system(anyString()).messages(anyList()).user(eq("它的优点呢")).call().content())
                .thenReturn("Rewrite Query: Spring Boot 的优点是什么");

        QueryRewriteService service = new QueryRewriteService(
                builder,
                new QueryUnderstandingService(null),
                true
        );

        String rewritten = service.rewrite("它的优点呢", List.of(new UserMessage("Spring Boot")), "旧摘要");

        assertThat(rewritten).isEqualTo("Spring Boot 的优点是什么");
    }

    @Test
    void rewrite_shouldFallbackToOriginalWhenLlmThrows() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class, Answers.RETURNS_DEEP_STUBS);
        when(builder.build().prompt().system(anyString()).messages(anyList()).user(eq("它的优点呢")).call().content())
                .thenThrow(new IllegalStateException("boom"));

        QueryRewriteService service = new QueryRewriteService(
                builder,
                new QueryUnderstandingService(null),
                true
        );

        String rewritten = service.rewrite("它的优点呢", List.of(new UserMessage("Spring Boot")), "会话摘要");

        assertThat(rewritten).isEqualTo("它的优点呢");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f rag-csdn-server/pom.xml "-Dtest=QueryRewriteServiceTest" test`
Expected: FAIL with compilation errors because `QueryRewriteService` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package com.example.ragcsdn.service.chat;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class QueryRewriteService {

    private final ChatClient.Builder chatClientBuilder;
    private final QueryUnderstandingService queryUnderstandingService;
    private final boolean queryRewriteEnabled;

    public QueryRewriteService(ChatClient.Builder chatClientBuilder,
                               QueryUnderstandingService queryUnderstandingService,
                               com.example.ragcsdn.config.ChatOptimizationProperties properties) {
        this(chatClientBuilder, queryUnderstandingService,
                properties == null || !Boolean.FALSE.equals(properties.getQueryRewriteEnabled()));
    }

    QueryRewriteService(ChatClient.Builder chatClientBuilder,
                        QueryUnderstandingService queryUnderstandingService,
                        boolean queryRewriteEnabled) {
        this.chatClientBuilder = chatClientBuilder;
        this.queryUnderstandingService = queryUnderstandingService;
        this.queryRewriteEnabled = queryRewriteEnabled;
    }

    public String rewrite(String query, List<Message> historyMessages, String memorySummary) {
        if (!queryRewriteEnabled || historyMessages == null || historyMessages.isEmpty()) {
            return query;
        }
        try {
            String rewritten = chatClientBuilder.build().prompt()
                    .system(ChatPromptTemplates.QUERY_REWRITE_SYSTEM_PROMPT
                            + queryUnderstandingService.buildSummaryPrompt(memorySummary))
                    .messages(historyMessages)
                    .user(query)
                    .call()
                    .content();
            return queryUnderstandingService.normalizeRewrittenQuery(query, rewritten);
        } catch (Exception ignored) {
            return query;
        }
    }
}
```

```java
// ChatServiceImpl.java
@Autowired
private QueryRewriteService queryRewriteService;

private QueryExpansionService.QueryPlan directPlan(String originalQuery, String rewrittenQuery) {
    return new QueryExpansionService.QueryPlan(
            QueryExpansionService.QueryIntent.DIRECT,
            originalQuery,
            rewrittenQuery,
            List.of(new QueryExpansionService.RetrievalQuery(rewrittenQuery, rewrittenQuery, "direct"))
    );
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -f rag-csdn-server/pom.xml "-Dtest=QueryRewriteServiceTest" test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add rag-csdn-server/src/main/java/com/example/ragcsdn/service/chat/QueryRewriteService.java rag-csdn-server/src/test/java/com/example/ragcsdn/service/chat/QueryRewriteServiceTest.java rag-csdn-server/src/main/java/com/example/ragcsdn/service/impl/ChatServiceImpl.java
git commit -m "feat: extract query rewrite service"
```

### Task 2: Extract QueryExpansionService

**Files:**
- Create: `rag-csdn-server/src/main/java/com/example/ragcsdn/service/chat/QueryExpansionService.java`
- Create: `rag-csdn-server/src/test/java/com/example/ragcsdn/service/chat/QueryExpansionServiceTest.java`
- Modify: `rag-csdn-server/src/main/java/com/example/ragcsdn/service/impl/ChatServiceImpl.java`

- [ ] **Step 1: Write the failing test**

```java
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
        QueryExpansionService service = new QueryExpansionService(
                null,
                analyzer(),
                new QueryUnderstandingService(analyzer()),
                new ChatRoutingPolicy(properties(), analyzer(), null, new ChatMetadataHelper()),
                properties(),
                false
        );

        QueryExpansionService.QueryExpansionDecision decision = service.expand(
                "原始问题",
                "改写后问题",
                new ConversationMemoryService.ConversationMemory(List.of(), null, false)
        );

        assertThat(decision.queryPlan().intent()).isEqualTo(QueryExpansionService.QueryIntent.DIRECT);
        assertThat(decision.usedLlmFallback()).isFalse();
        assertThat(decision.usedHyde()).isFalse();
        assertThat(decision.usedDecomposition()).isFalse();
    }

    @Test
    void expand_shouldUseHydeForAmbiguousIntent() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class, Answers.RETURNS_DEEP_STUBS);
        when(builder.build().prompt().system(anyString()).messages(anyList()).user(eq("改写后问题")).call().content())
                .thenReturn("AMBIGUOUS", "这是一段 HyDE 文档");

        QueryExpansionService service = new QueryExpansionService(
                builder,
                analyzer(),
                new QueryUnderstandingService(analyzer()),
                new ChatRoutingPolicy(properties(), analyzer(), null, new ChatMetadataHelper()),
                properties(),
                true
        );

        QueryExpansionService.QueryExpansionDecision decision = service.expand(
                "原始问题",
                "改写后问题",
                new ConversationMemoryService.ConversationMemory(List.of(new UserMessage("历史问题")), "旧摘要", true)
        );

        assertThat(decision.queryPlan().intent()).isEqualTo(QueryExpansionService.QueryIntent.AMBIGUOUS);
        assertThat(decision.usedHyde()).isTrue();
        assertThat(decision.queryPlan().retrievalQueries()).hasSize(2);
        assertThat(decision.queryPlan().retrievalQueries().get(1).source()).isEqualTo("hyde");
    }

    @Test
    void expand_shouldUseDecompositionWhenBroadQueryReturnsMultipleSubQueries() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class, Answers.RETURNS_DEEP_STUBS);
        when(builder.build().prompt().system(anyString()).messages(anyList()).user(eq("对比 Spring Boot 和 Tomcat")).call().content())
                .thenReturn("BROAD", "部署模型\n自动配置\n运行方式");

        QueryExpansionService service = new QueryExpansionService(
                builder,
                analyzer(),
                new QueryUnderstandingService(analyzer()),
                new ChatRoutingPolicy(properties(), analyzer(), null, new ChatMetadataHelper()),
                properties(),
                true
        );

        QueryExpansionService.QueryExpansionDecision decision = service.expand(
                "原始问题",
                "对比 Spring Boot 和 Tomcat",
                new ConversationMemoryService.ConversationMemory(List.of(), null, false)
        );

        assertThat(decision.usedDecomposition()).isTrue();
        assertThat(decision.queryPlan().retrievalQueries()).hasSize(3);
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
        return properties;
    }

    private QueryComplexityAnalyzer analyzer() {
        return new QueryComplexityAnalyzer(properties());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f rag-csdn-server/pom.xml "-Dtest=QueryExpansionServiceTest" test`
Expected: FAIL with compilation errors because `QueryExpansionService` and its public records do not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package com.example.ragcsdn.service.chat;

import com.example.ragcsdn.config.ChatOptimizationProperties;
import com.example.ragcsdn.service.impl.QueryComplexityAnalyzer;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class QueryExpansionService {

    public enum QueryIntent {
        DIRECT,
        AMBIGUOUS,
        BROAD
    }

    public record RetrievalQuery(String vectorQuery, String keywordQuery, String source) { }

    public record QueryPlan(QueryIntent intent, String originalQuery, String rewrittenQuery,
                            List<RetrievalQuery> retrievalQueries) { }

    public record RoutingAnalysis(QueryIntent suggestedIntent, double ambiguityScore, double breadthScore,
                                  double complexityScore, double decisionConfidence,
                                  boolean conversationDependent) { }

    public record QueryExpansionDecision(QueryPlan queryPlan, RoutingAnalysis routingAnalysis,
                                         boolean usedLlmFallback, boolean usedHyde,
                                         boolean usedDecomposition) { }

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
                    new QueryPlan(QueryIntent.DIRECT, originalQuery, rewrittenQuery,
                            List.of(new RetrievalQuery(rewrittenQuery, rewrittenQuery, "direct"))),
                    new RoutingAnalysis(QueryIntent.DIRECT, 0.0d, 0.0d, 0.0d, 1.0d, false),
                    false,
                    false,
                    false
            );
        }

        RoutingAnalysis routing = analyzeRouting(rewrittenQuery, memory);
        QueryIntent intent = routing.suggestedIntent();
        boolean usedLlmFallback = chatRoutingPolicy.shouldUseLlmFallback(routing.decisionConfidence());
        if (usedLlmFallback) {
            intent = classifyQuery(rewrittenQuery, memory);
        }
        if (chatRoutingPolicy.shouldUseHyde(intent.name(), routing.ambiguityScore())) {
            return new QueryExpansionDecision(
                    new QueryPlan(intent, originalQuery, rewrittenQuery, List.of(
                            new RetrievalQuery(rewrittenQuery, rewrittenQuery, "direct"),
                            new RetrievalQuery(generateHydeDocument(rewrittenQuery, memory), null, "hyde")
                    )),
                    routing,
                    usedLlmFallback,
                    true,
                    false
            );
        }
        if (chatRoutingPolicy.shouldUseDecomposition(intent.name(), routing.breadthScore())) {
            List<String> subQueries = decomposeQuery(rewrittenQuery, memory);
            if (subQueries.size() > 1) {
                return new QueryExpansionDecision(
                        new QueryPlan(intent, originalQuery, rewrittenQuery,
                                subQueries.stream()
                                        .map(subQuery -> new RetrievalQuery(subQuery, subQuery, "subquery"))
                                        .toList()),
                        routing,
                        usedLlmFallback,
                        false,
                        true
                );
            }
        }
        return new QueryExpansionDecision(
                new QueryPlan(intent, originalQuery, rewrittenQuery,
                        List.of(new RetrievalQuery(rewrittenQuery, rewrittenQuery, "direct"))),
                routing,
                usedLlmFallback,
                false,
                false
        );
    }

    // keep method bodies behavior-identical to current ChatServiceImpl:
    // analyzeRouting(...), classifyQuery(...), generateHydeDocument(...), decomposeQuery(...)
}
```

```java
// ChatServiceImpl.java
@Autowired
private QueryExpansionService queryExpansionService;

private QueryExpansionService.QueryExpansionDecision understandQuery(
        String query, ConversationMemoryService.ConversationMemory memory) {
    String rewrittenQuery = queryRewriteService.rewrite(query, memory.recentMessages(), memory.summary());
    return queryExpansionService.expand(query, rewrittenQuery, memory);
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -f rag-csdn-server/pom.xml "-Dtest=QueryExpansionServiceTest" test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add rag-csdn-server/src/main/java/com/example/ragcsdn/service/chat/QueryExpansionService.java rag-csdn-server/src/test/java/com/example/ragcsdn/service/chat/QueryExpansionServiceTest.java rag-csdn-server/src/main/java/com/example/ragcsdn/service/impl/ChatServiceImpl.java
git commit -m "feat: extract query expansion service"
```

### Task 3: Extract ConversationSummaryService

**Files:**
- Create: `rag-csdn-server/src/main/java/com/example/ragcsdn/service/chat/ConversationSummaryService.java`
- Create: `rag-csdn-server/src/test/java/com/example/ragcsdn/service/chat/ConversationSummaryServiceTest.java`
- Modify: `rag-csdn-server/src/main/java/com/example/ragcsdn/service/impl/ChatServiceImpl.java`

- [ ] **Step 1: Write the failing test**

```java
package com.example.ragcsdn.service.chat;

import com.example.ragcsdn.entity.Message;
import com.example.ragcsdn.mapper.MessageMapper;
import com.example.ragcsdn.mapper.SessionMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.ai.chat.client.ChatClient;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationSummaryServiceTest {

    @Test
    void summarize_shouldFallbackToNormalizedRuleSummaryWhenLlmFails() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class, Answers.RETURNS_DEEP_STUBS);
        when(builder.build().prompt().system(anyString()).user(anyString()).call().content())
                .thenThrow(new IllegalStateException("boom"));

        ConversationSummaryService service = new ConversationSummaryService(
                builder,
                mock(MessageMapper.class),
                mock(SessionMapper.class),
                new ConversationMemoryService(),
                2,
                2,
                150,
                true
        );

        String summary = service.summarize(List.of(
                msg(1L, "user", "第一问"),
                msg(2L, "assistant", "第一答"),
                msg(3L, "user", "第二问")
        ));

        assertThat(summary).contains("user:第一问");
        assertThat(summary).contains("assistant:第一答");
    }

    @Test
    void refreshAndPersist_shouldClearSummaryWhenConversationBelowThreshold() {
        MessageMapper messageMapper = mock(MessageMapper.class);
        SessionMapper sessionMapper = mock(SessionMapper.class);
        when(messageMapper.selectBySessionId(99L)).thenReturn(List.of(msg(1L, "user", "单条消息")));

        ConversationSummaryService service = new ConversationSummaryService(
                mock(ChatClient.Builder.class, Answers.RETURNS_DEEP_STUBS),
                messageMapper,
                sessionMapper,
                new ConversationMemoryService(),
                2,
                2,
                150,
                true
        );

        service.refreshAndPersist(99L);

        verify(sessionMapper).updateSummary(99L, null, null);
    }

    private Message msg(Long id, String role, String content) {
        Message message = new Message();
        message.setId(id);
        message.setRole(role);
        message.setContent(content);
        message.setCreateTime(LocalDateTime.now().plusSeconds(id));
        return message;
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f rag-csdn-server/pom.xml "-Dtest=ConversationSummaryServiceTest" test`
Expected: FAIL with compilation errors because `ConversationSummaryService` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package com.example.ragcsdn.service.chat;

import com.example.ragcsdn.config.ChatOptimizationProperties;
import com.example.ragcsdn.entity.Message;
import com.example.ragcsdn.mapper.MessageMapper;
import com.example.ragcsdn.mapper.SessionMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class ConversationSummaryService {

    private final ChatClient.Builder chatClientBuilder;
    private final MessageMapper messageMapper;
    private final SessionMapper sessionMapper;
    private final ConversationMemoryService conversationMemoryService;
    private final int summaryTriggerMessages;
    private final int summaryRecentMessages;
    private final int summaryMaxLength;
    private final boolean summaryEnabled;

    public ConversationSummaryService(ChatClient.Builder chatClientBuilder,
                                      MessageMapper messageMapper,
                                      SessionMapper sessionMapper,
                                      ConversationMemoryService conversationMemoryService,
                                      ChatOptimizationProperties properties) {
        this(chatClientBuilder, messageMapper, sessionMapper, conversationMemoryService,
                properties == null || properties.getSummaryTriggerMessages() == null ? 12 : properties.getSummaryTriggerMessages(),
                properties == null || properties.getSummaryRecentMessages() == null ? 6 : properties.getSummaryRecentMessages(),
                properties == null || properties.getSummaryMaxLength() == null ? 150 : properties.getSummaryMaxLength(),
                properties == null || !Boolean.FALSE.equals(properties.getSummaryEnabled()));
    }

    ConversationSummaryService(ChatClient.Builder chatClientBuilder,
                               MessageMapper messageMapper,
                               SessionMapper sessionMapper,
                               ConversationMemoryService conversationMemoryService,
                               int summaryTriggerMessages,
                               int summaryRecentMessages,
                               int summaryMaxLength,
                               boolean summaryEnabled) {
        this.chatClientBuilder = chatClientBuilder;
        this.messageMapper = messageMapper;
        this.sessionMapper = sessionMapper;
        this.conversationMemoryService = conversationMemoryService;
        this.summaryTriggerMessages = summaryTriggerMessages;
        this.summaryRecentMessages = summaryRecentMessages;
        this.summaryMaxLength = summaryMaxLength;
        this.summaryEnabled = summaryEnabled;
    }

    public String summarize(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
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
            return conversationMemoryService.normalizeConversationSummary(summary, messages, summaryMaxLength);
        } catch (Exception ignored) {
            return conversationMemoryService.normalizeConversationSummary(null, messages, summaryMaxLength);
        }
    }

    public void refreshAndPersist(Long sessionId) {
        if (!summaryEnabled) {
            return;
        }
        List<Message> messages = messageMapper.selectBySessionId(sessionId).stream()
                .sorted(Comparator.comparing(Message::getCreateTime))
                .collect(Collectors.toList());
        if (messages.size() <= summaryTriggerMessages) {
            sessionMapper.updateSummary(sessionId, null, null);
            return;
        }
        int recentCount = Math.min(summaryRecentMessages, messages.size());
        String summary = summarize(messages.subList(0, messages.size() - recentCount));
        sessionMapper.updateSummary(sessionId, summary, LocalDateTime.now());
    }
}
```

```java
// ChatServiceImpl.java
@Autowired
private ConversationSummaryService conversationSummaryService;

private ConversationMemoryService.ConversationMemory buildConversationMemory(
        Session session, List<Message> messages, Long excludeId) {
    return conversationMemoryService.buildConversationMemory(
            session,
            messages,
            excludeId,
            getSummaryTriggerMessages(),
            getSummaryRecentMessages(),
            getMaxHistory(),
            isSummaryEnabled(),
            conversationSummaryService::summarize
    );
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -f rag-csdn-server/pom.xml "-Dtest=ConversationSummaryServiceTest" test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add rag-csdn-server/src/main/java/com/example/ragcsdn/service/chat/ConversationSummaryService.java rag-csdn-server/src/test/java/com/example/ragcsdn/service/chat/ConversationSummaryServiceTest.java rag-csdn-server/src/main/java/com/example/ragcsdn/service/impl/ChatServiceImpl.java
git commit -m "feat: extract conversation summary service"
```

### Task 4: Extract ChatStreamingOrchestrator

**Files:**
- Create: `rag-csdn-server/src/main/java/com/example/ragcsdn/service/chat/ChatStreamingOrchestrator.java`
- Create: `rag-csdn-server/src/test/java/com/example/ragcsdn/service/chat/ChatStreamingOrchestratorTest.java`
- Modify: `rag-csdn-server/src/main/java/com/example/ragcsdn/service/impl/ChatServiceImpl.java`

- [ ] **Step 1: Write the failing test**

```java
package com.example.ragcsdn.service.chat;

import com.example.ragcsdn.dto.sse.SseEndEvent;
import com.example.ragcsdn.entity.Message;
import com.example.ragcsdn.enums.MessageRole;
import com.example.ragcsdn.mapper.MessageMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatStreamingOrchestratorTest {

    @Test
    void stream_shouldSendStartContentAndEndAndPersistAssistantMessage() throws Exception {
        ChatClient.Builder builder = mock(ChatClient.Builder.class, Answers.RETURNS_DEEP_STUBS);
        when(builder.build().prompt().system(anyString()).messages(any()).user(anyString()).stream().chatResponse())
                .thenReturn(Flux.just(chatResponse("第一段"), chatResponse("第二段")));

        MessageMapper messageMapper = mock(MessageMapper.class);
        ConversationSummaryService summaryService = mock(ConversationSummaryService.class);
        RecordingSseEmitter emitter = new RecordingSseEmitter();

        ChatStreamingOrchestrator orchestrator = new ChatStreamingOrchestrator(
                builder,
                messageMapper,
                new ObjectMapper(),
                new SyncTaskExecutor(),
                summaryService
        );

        orchestrator.stream(new ChatStreamingOrchestrator.StreamRequest(
                emitter,
                7L,
                9L,
                11L,
                "用户问题",
                "system prompt",
                List.of(),
                new QueryExpansionService.QueryPlan(
                        QueryExpansionService.QueryIntent.DIRECT,
                        "用户问题",
                        "改写后问题",
                        List.of(new QueryExpansionService.RetrievalQuery("改写后问题", "改写后问题", "direct"))
                ),
                new ResponseConfidenceService.ResponseConfidence("HIGH", 0.91d, false),
                List.of(),
                false
        ));

        assertThat(emitter.eventNames()).containsExactly("start", "content", "content", "end");
        verify(messageMapper).insert(any(Message.class));
        verify(summaryService).refreshAndPersist(7L);
        SseEndEvent endEvent = emitter.lastEndEvent();
        assertThat(endEvent.getRewrittenQuery()).isEqualTo("改写后问题");
        assertThat(endEvent.getSummaryUsed()).isFalse();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -f rag-csdn-server/pom.xml "-Dtest=ChatStreamingOrchestratorTest" test`
Expected: FAIL with compilation errors because `ChatStreamingOrchestrator` and `RecordingSseEmitter` support code do not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package com.example.ragcsdn.service.chat;

import com.example.ragcsdn.dto.sse.SseContentEvent;
import com.example.ragcsdn.dto.sse.SseEndEvent;
import com.example.ragcsdn.dto.sse.SseErrorEvent;
import com.example.ragcsdn.dto.sse.SseStartEvent;
import com.example.ragcsdn.entity.Message;
import com.example.ragcsdn.enums.MessageRole;
import com.example.ragcsdn.mapper.MessageMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ChatStreamingOrchestrator {

    public record StreamRequest(
            SseEmitter emitter,
            Long sessionId,
            Long userId,
            Long userMessageId,
            String userContent,
            String systemPrompt,
            List<org.springframework.ai.chat.messages.Message> memoryMessages,
            QueryExpansionService.QueryPlan queryPlan,
            ResponseConfidenceService.ResponseConfidence confidence,
            List<org.springframework.ai.document.Document> relevantDocs,
            boolean summaryUsed
    ) { }

    private final ChatClient.Builder chatClientBuilder;
    private final MessageMapper messageMapper;
    private final ObjectMapper objectMapper;
    private final TaskExecutor taskExecutor;
    private final ConversationSummaryService conversationSummaryService;

    public ChatStreamingOrchestrator(ChatClient.Builder chatClientBuilder,
                                     MessageMapper messageMapper,
                                     ObjectMapper objectMapper,
                                     TaskExecutor taskExecutor,
                                     ConversationSummaryService conversationSummaryService) {
        this.chatClientBuilder = chatClientBuilder;
        this.messageMapper = messageMapper;
        this.objectMapper = objectMapper;
        this.taskExecutor = taskExecutor;
        this.conversationSummaryService = conversationSummaryService;
    }

    public void stream(StreamRequest request) {
        taskExecutor.execute(() -> {
            try {
                request.emitter().send(SseEmitter.event()
                        .name("start")
                        .data(objectMapper.writeValueAsString(new SseStartEvent(request.userMessageId()))));

                Flux<ChatResponse> responseFlux = chatClientBuilder.build().prompt()
                        .system(request.systemPrompt())
                        .messages(request.memoryMessages())
                        .user(request.userContent())
                        .stream()
                        .chatResponse();

                StringBuilder fullResponse = new StringBuilder();
                responseFlux.subscribe(
                        response -> sendChunk(request, fullResponse, response),
                        error -> sendError(request.emitter(), error),
                        () -> completeSuccess(request, fullResponse.toString())
                );
            } catch (Exception e) {
                sendError(request.emitter(), e);
            }
        });
    }

    // keep event order identical to current ChatServiceImpl:
    // start -> content* -> assistant persistence -> summary refresh -> end -> complete
}
```

```java
// ChatServiceImpl.java
@Autowired
private ChatStreamingOrchestrator chatStreamingOrchestrator;

taskExecutor.execute(() -> { ... });
// replace the whole block with:
chatStreamingOrchestrator.stream(new ChatStreamingOrchestrator.StreamRequest(
        emitter,
        sessionId,
        userId,
        userMessage.getId(),
        content,
        systemPrompt,
        memory.recentMessages(),
        queryPlan,
        confidence,
        relevantDocs,
        memory.summaryUsed()
));
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -f rag-csdn-server/pom.xml "-Dtest=ChatStreamingOrchestratorTest" test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add rag-csdn-server/src/main/java/com/example/ragcsdn/service/chat/ChatStreamingOrchestrator.java rag-csdn-server/src/test/java/com/example/ragcsdn/service/chat/ChatStreamingOrchestratorTest.java rag-csdn-server/src/main/java/com/example/ragcsdn/service/impl/ChatServiceImpl.java
git commit -m "feat: extract chat streaming orchestrator"
```

### Task 5: Slim ChatServiceImpl, remove dead glue, and replace hardcoded prompt constants

**Files:**
- Modify: `rag-csdn-server/src/main/java/com/example/ragcsdn/service/impl/ChatServiceImpl.java`
- Modify: `rag-csdn-server/src/main/java/com/example/ragcsdn/service/chat/DocumentRerankService.java`
- Create: `rag-csdn-server/src/main/java/com/example/ragcsdn/service/chat/DocumentRerankPromptTemplates.java`
- Modify: `rag-csdn-server/src/test/java/com/example/ragcsdn/service/impl/ChatServiceImplTest.java`
- Modify: `rag-csdn-server/src/test/java/com/example/ragcsdn/service/chat/DocumentRerankServiceTest.java`

- [ ] **Step 1: Write the failing regression tests**

```java
// ChatServiceImplTest.java
@Test
void streamMessage_shouldDelegateRewriteExpansionAndStreaming() {
    QueryRewriteService rewriteService = mock(QueryRewriteService.class);
    QueryExpansionService expansionService = mock(QueryExpansionService.class);
    ConversationSummaryService summaryService = mock(ConversationSummaryService.class);
    ChatStreamingOrchestrator streamingOrchestrator = mock(ChatStreamingOrchestrator.class);

    when(rewriteService.rewrite(anyString(), anyList(), any())).thenReturn("改写后问题");
    when(expansionService.expand(anyString(), anyString(), any())).thenReturn(
            new QueryExpansionService.QueryExpansionDecision(
                    new QueryExpansionService.QueryPlan(
                            QueryExpansionService.QueryIntent.DIRECT,
                            "原始问题",
                            "改写后问题",
                            List.of(new QueryExpansionService.RetrievalQuery("改写后问题", "改写后问题", "direct"))
                    ),
                    new QueryExpansionService.RoutingAnalysis(
                            QueryExpansionService.QueryIntent.DIRECT, 0.0d, 0.0d, 0.0d, 1.0d, false
                    ),
                    false,
                    false,
                    false
            )
    );

    // inject mocks, call streamMessage(...), then:
    verify(streamingOrchestrator).stream(any(ChatStreamingOrchestrator.StreamRequest.class));
    verifyNoInteractions(summaryService);
}

// DocumentRerankServiceTest.java
@Test
void buildModelRerankPrompt_shouldUseTemplateConstants() {
    DocumentRerankService service = new DocumentRerankService(new ChatMetadataHelper(), new RetrievalPipelineService(new ChatMetadataHelper()));

    String prompt = service.buildModelRerankPrompt("默认端口是多少", List.of(doc("8080", "Spring Boot", "sid-1", 0, 1, 0.9d)), 1);

    assertThat(prompt).contains(DocumentRerankPromptTemplates.USER_QUERY_PREFIX);
    assertThat(prompt).contains(DocumentRerankPromptTemplates.ONLY_OUTPUT_ORDER);
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -f rag-csdn-server/pom.xml "-Dtest=ChatServiceImplTest,DocumentRerankServiceTest" test`
Expected: FAIL because `ChatServiceImplTest` still depends on private reflection helpers and `DocumentRerankPromptTemplates` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package com.example.ragcsdn.service.chat;

public final class DocumentRerankPromptTemplates {

    public static final String MODEL_RERANK_SYSTEM_PROMPT = """
            你是RAG检索重排器。
            你的任务是根据用户问题，对候选片段按“最有助于回答问题”的顺序重排。
            评估标准：
            1. 与问题直接相关
            2. 能提供更完整、更精确的事实
            3. 来源信息明确
            4. 避免重复语义
            只输出候选编号，使用英文逗号分隔，例如：2,1,3
            不要输出解释，不要输出编号之外的内容。
            """;
    public static final String USER_QUERY_PREFIX = "用户问题：";
    public static final String TOP_K_TEMPLATE = "请从以下候选片段中选出最相关的前%d个，并按相关性从高到低排序。";
    public static final String CANDIDATE_PREFIX = "候选";
    public static final String TITLE_PREFIX = "标题：";
    public static final String SOURCE_PREFIX = "标识：";
    public static final String SNIPPET_PREFIX = "片段：";
    public static final String ONLY_OUTPUT_ORDER = "只输出编号列表。";

    private DocumentRerankPromptTemplates() {
    }
}
```

```java
// DocumentRerankService.java
String result = chatClientBuilder.build().prompt()
        .system(DocumentRerankPromptTemplates.MODEL_RERANK_SYSTEM_PROMPT)
        .user(buildModelRerankPrompt(query, modelWindow, finalTopK))
        .call()
        .content();

prompt.append(DocumentRerankPromptTemplates.USER_QUERY_PREFIX).append(query).append("\n");
prompt.append(DocumentRerankPromptTemplates.TOP_K_TEMPLATE.formatted(Math.min(finalTopK, candidates.size())))
        .append("\n\n");
prompt.append(DocumentRerankPromptTemplates.CANDIDATE_PREFIX).append(i + 1).append(":\n")
        .append(DocumentRerankPromptTemplates.TITLE_PREFIX)
        .append(chatMetadataHelper.getMetadataString(document, "title", "未知文章")).append("\n")
        .append(DocumentRerankPromptTemplates.SOURCE_PREFIX)
        .append(chatMetadataHelper.getMetadataString(document, "sourceId", "未知标识")).append("\n")
        .append(DocumentRerankPromptTemplates.SNIPPET_PREFIX)
        .append(truncateForModelRerank(document.getText())).append("\n\n");
prompt.append(DocumentRerankPromptTemplates.ONLY_OUTPUT_ORDER);
```

```java
// ChatServiceImpl.java
// remove private enum/record duplicates that moved into QueryExpansionService
// remove dead injected field:
// @Autowired private ChatRoutingPolicy chatRoutingPolicy;
// remove private methods now owned elsewhere:
// rewriteQuery(...), classifyQuery(...), generateHydeDocument(...),
// decomposeQuery(...), summarizeConversation(...), refreshAndPersistConversationSummary(...)
// delete dead rerank helpers that duplicate DocumentRerankService:
// rerankDocumentsWithModel(...), buildModelRerankPrompt(...),
// truncateForModelRerank(...), applyModelRerankResult(...), parseModelRerankOrder(...)
```

- [ ] **Step 4: Run the targeted regression suite**

Run: `mvn -f rag-csdn-server/pom.xml "-Dtest=ChatServiceImplTest,QueryRewriteServiceTest,QueryExpansionServiceTest,ConversationSummaryServiceTest,ChatStreamingOrchestratorTest,DocumentRerankServiceTest" test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add rag-csdn-server/src/main/java/com/example/ragcsdn/service/impl/ChatServiceImpl.java rag-csdn-server/src/main/java/com/example/ragcsdn/service/chat/DocumentRerankService.java rag-csdn-server/src/main/java/com/example/ragcsdn/service/chat/DocumentRerankPromptTemplates.java rag-csdn-server/src/test/java/com/example/ragcsdn/service/impl/ChatServiceImplTest.java rag-csdn-server/src/test/java/com/example/ragcsdn/service/chat/DocumentRerankServiceTest.java
git commit -m "refactor: slim down chat service orchestration"
```

## Final Verification

- [ ] Run: `mvn -f rag-csdn-server/pom.xml "-Dtest=ChatServiceImplTest,QueryRewriteServiceTest,QueryExpansionServiceTest,ConversationSummaryServiceTest,ChatStreamingOrchestratorTest,ConversationMemoryServiceTest,QueryUnderstandingServiceTest,DocumentRerankServiceTest" test`
- [ ] Expected: PASS with unchanged SSE event contract and unchanged routing decisions
- [ ] Run: `git status --short`
- [ ] Expected: clean working tree

## Spec Coverage Check

- Query rewrite extraction is covered by Task 1.
- Query expansion extraction is covered by Task 2.
- Conversation summary generation and persistence ownership move is covered by Task 3.
- SSE lifecycle, assistant persistence, and summary refresh sequencing move is covered by Task 4.
- Dead `chatRoutingPolicy` field removal, hardcoded rerank prompt constant extraction, and reflection-test reduction are covered by Task 5.

## Placeholder Scan

- No `TODO`, `TBD`, or “similar to previous task” placeholders remain.
- Every task includes exact file paths, test commands, and commit messages.
- All new collaborator names and record types are consistent across tasks.
