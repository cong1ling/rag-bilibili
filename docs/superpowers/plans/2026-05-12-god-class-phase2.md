# God Class Phase 2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split `ChatServiceImpl`, `ArticleServiceImpl`, `CsdnDocumentReader`, and `CleaningMetricsEngine` into focused collaborators while also centralizing behavior-defining constants and keeping runtime behavior stable.

**Architecture:** Execute the refactor in two batches. Batch 1 turns chat, article import, and CSDN reading into orchestration layers backed by focused services and helper classes; Batch 2 isolates cleaning evaluation into evaluator, aggregator, and finding collector components. Every extraction starts by locking current behavior with a focused test, then moving one responsibility at a time until the original class becomes a thin coordinator.

**Tech Stack:** Java 17, Spring Boot 3, Spring AI, JUnit 5, Mockito, Maven

---

### Task 1: Lock Current Chat / Article / Reader Behavior Before Extraction

**Files:**
- Modify: `rag-csdn-server/src/test/java/com/example/ragcsdn/service/impl/ChatServiceImplTest.java`
- Modify: `rag-csdn-server/src/test/java/com/example/ragcsdn/service/impl/ArticleServiceImplTest.java`
- Modify: `rag-csdn-server/src/test/java/com/alibaba/cloud/ai/reader/csdn/CsdnDocumentReaderTest.java`

- [ ] **Step 1: Write the failing tests**

Add extraction-safety tests that lock current helper behavior before moving code out of `ChatServiceImpl`, `ArticleServiceImpl`, and `CsdnDocumentReader`.

```java
@Test
void buildContext_shouldDelegatePromptFormattingWithoutChangingSourceHeaderDefaults() throws Exception {
    List<Document> documents = List.of(doc("正文", "标题A", "sid-1", 0, 2, 0.88d));

    String context = invokeBuildContext(documents);

    assertThat(context).contains("文章: 标题A");
    assertThat(context).contains("标识: sid-1");
    assertThat(context).contains("相似度: 0.880");
}

@Test
void importRecommendedArticles_shouldTranslateUnexpectedDiscoveryFailure() {
    ImportRecommendedArticlesRequest request = new ImportRecommendedArticlesRequest();
    request.setLimit(3);

    when(userService.getCsdnSessionCookie(1L)).thenReturn("cookie");
    // This test forces the implementation to expose a discovery seam instead of hard-wiring the constructor.
    assertThatThrownBy(() -> articleService.importRecommendedArticles(request, 1L))
            .isInstanceOf(BusinessException.class);
}

@Test
void parseDocuments_shouldKeepNoiseFilteringAndHeadingFormattingStable() {
    CsdnResource resource = new CsdnResource("https://blog.csdn.net/test_author/article/details/147000001");
    CsdnDocumentReader reader = new CsdnDocumentReader(resource);
    String html = """
            <html><body><main><div id="content_views">
              <h2>安装</h2><p>目录</p><p>保留正文</p>
            </div></main></body></html>
            """;

    List<Document> documents = reader.parseDocuments(resource, html);

    assertThat(documents.get(0).getText()).contains("## 安装");
    assertThat(documents.get(0).getText()).doesNotContain("目录");
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q "-Dtest=ChatServiceImplTest,ArticleServiceImplTest,CsdnDocumentReaderTest" test`

Expected: FAIL because at least one new test references behavior that is not yet injectable / explicitly covered.

- [ ] **Step 3: Write minimal implementation to make the tests pass without changing behavior**

Add only the smallest seams required for extraction: package-visible helper methods, collaborator setters/fields for tests, or constructor overloads that preserve default behavior.

```java
// Example seam in ArticleServiceImpl for extraction
CsdnDiscoveryReader newDiscoveryReader(String cookieHeader) {
    return new CsdnDiscoveryReader(cookieHeader);
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q "-Dtest=ChatServiceImplTest,ArticleServiceImplTest,CsdnDocumentReaderTest" test`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add rag-csdn-server/src/test/java/com/example/ragcsdn/service/impl/ChatServiceImplTest.java rag-csdn-server/src/test/java/com/example/ragcsdn/service/impl/ArticleServiceImplTest.java rag-csdn-server/src/test/java/com/alibaba/cloud/ai/reader/csdn/CsdnDocumentReaderTest.java rag-csdn-server/src/main/java/com/example/ragcsdn/service/impl/ArticleServiceImpl.java
git commit -m "test: lock baseline behavior before god class extraction"
```

### Task 2: Extract Chat Memory and Query Understanding Services

**Files:**
- Create: `rag-csdn-server/src/main/java/com/example/ragcsdn/service/chat/ConversationMemoryService.java`
- Create: `rag-csdn-server/src/main/java/com/example/ragcsdn/service/chat/QueryUnderstandingService.java`
- Create: `rag-csdn-server/src/main/java/com/example/ragcsdn/service/chat/ChatPromptTemplates.java`
- Modify: `rag-csdn-server/src/main/java/com/example/ragcsdn/service/impl/ChatServiceImpl.java`
- Test: `rag-csdn-server/src/test/java/com/example/ragcsdn/service/chat/ConversationMemoryServiceTest.java`
- Test: `rag-csdn-server/src/test/java/com/example/ragcsdn/service/chat/QueryUnderstandingServiceTest.java`

- [ ] **Step 1: Write the failing tests**

Create direct unit tests for conversation memory and query-understanding behavior that is currently hidden behind reflection.

```java
@Test
void buildConversationMemory_shouldSummarizeOlderMessagesAndKeepRecentWindow() {
    ConversationMemoryService service = new ConversationMemoryService();
    Session session = new Session();
    session.setConversationSummary("旧摘要");
    List<Message> messages = List.of(
            msg(1L, MessageRole.USER.getCode(), "问题一", LocalDateTime.now().minusMinutes(3)),
            msg(2L, MessageRole.ASSISTANT.getCode(), "回答一", LocalDateTime.now().minusMinutes(2)),
            msg(3L, MessageRole.USER.getCode(), "问题二", LocalDateTime.now().minusMinutes(1))
    );

    ConversationMemoryService.ConversationMemory memory =
            service.buildConversationMemory(session, messages, null, 2, 10, false);

    assertThat(memory.summary()).isEqualTo("旧摘要");
    assertThat(memory.recentMessages()).hasSize(2);
}

@Test
void normalizeDecomposedQueries_shouldFallbackToOriginalWhenResultIsBlank() {
    QueryUnderstandingService service = new QueryUnderstandingService(null);

    List<String> queries = service.normalizeDecomposedQueries(" \n \n ", "原始问题", 3);

    assertThat(queries).containsExactly("原始问题");
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q "-Dtest=ConversationMemoryServiceTest,QueryUnderstandingServiceTest" test`

Expected: FAIL with missing classes / methods.

- [ ] **Step 3: Write minimal implementation**

Move conversation windowing, summary normalization, query normalization, heuristic intent, and prompt-template constants out of `ChatServiceImpl`.

```java
@Service
public class ConversationMemoryService {

    public record ConversationMemory(
            List<org.springframework.ai.chat.messages.Message> recentMessages,
            String summary,
            boolean summaryUsed
    ) {}

    public ConversationMemory buildConversationMemory(
            Session session, List<Message> messages, Long excludeId,
            int summaryRecentMessages, int maxHistory, boolean summaryEnabled) {
        // move current ChatServiceImpl logic here verbatim first
    }
}
```

```java
final class ChatPromptTemplates {
    static final String QUERY_INTENT_SYSTEM_PROMPT = """
            你是RAG查询路由器。
            请将用户问题只分类为以下三类之一：
            DIRECT：问题清晰明确，可直接检索。
            AMBIGUOUS：问题较短、语义不完整、存在指代或语义间隙，适合先做 HyDE。
            BROAD：问题范围宽，需要拆成3到5个互补子问题分别检索。
            只输出 DIRECT、AMBIGUOUS、BROAD 之一，不要解释。
            """;

    private ChatPromptTemplates() {
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q "-Dtest=ConversationMemoryServiceTest,QueryUnderstandingServiceTest,ChatServiceImplTest" test`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add rag-csdn-server/src/main/java/com/example/ragcsdn/service/chat/ConversationMemoryService.java rag-csdn-server/src/main/java/com/example/ragcsdn/service/chat/QueryUnderstandingService.java rag-csdn-server/src/main/java/com/example/ragcsdn/service/chat/ChatPromptTemplates.java rag-csdn-server/src/main/java/com/example/ragcsdn/service/impl/ChatServiceImpl.java rag-csdn-server/src/test/java/com/example/ragcsdn/service/chat/ConversationMemoryServiceTest.java rag-csdn-server/src/test/java/com/example/ragcsdn/service/chat/QueryUnderstandingServiceTest.java rag-csdn-server/src/test/java/com/example/ragcsdn/service/impl/ChatServiceImplTest.java
git commit -m "refactor: extract chat memory and query understanding services"
```

### Task 3: Extract Chat Retrieval, Routing, and Rerank Pipeline

**Files:**
- Create: `rag-csdn-server/src/main/java/com/example/ragcsdn/service/chat/ChatMetadataHelper.java`
- Create: `rag-csdn-server/src/main/java/com/example/ragcsdn/service/chat/ChatRoutingPolicy.java`
- Create: `rag-csdn-server/src/main/java/com/example/ragcsdn/service/chat/RetrievalPipelineService.java`
- Create: `rag-csdn-server/src/main/java/com/example/ragcsdn/service/chat/DocumentRerankService.java`
- Modify: `rag-csdn-server/src/main/java/com/example/ragcsdn/service/impl/ChatServiceImpl.java`
- Test: `rag-csdn-server/src/test/java/com/example/ragcsdn/service/chat/ChatRoutingPolicyTest.java`
- Test: `rag-csdn-server/src/test/java/com/example/ragcsdn/service/chat/RetrievalPipelineServiceTest.java`
- Test: `rag-csdn-server/src/test/java/com/example/ragcsdn/service/chat/DocumentRerankServiceTest.java`

- [ ] **Step 1: Write the failing tests**

Create focused tests for topK routing, score labels, hybrid merge, and rerank ordering.

```java
@Test
void determineTopK_shouldUseComplexBucketForBroadQuery() {
    ChatOptimizationProperties properties = new ChatOptimizationProperties();
    properties.setSimpleTopK(3);
    properties.setNormalTopK(5);
    properties.setComplexTopK(8);
    ChatRoutingPolicy policy = new ChatRoutingPolicy(properties, new QueryComplexityAnalyzer(properties));

    int topK = policy.determineTopK(new Session(), "请系统比较Spring AI、RAG和向量检索方案");

    assertThat(topK).isEqualTo(8);
}

@Test
void mergeHybridResults_shouldPreferFusedScoreAndPreserveDistinctChunks() {
    RetrievalPipelineService service = new RetrievalPipelineService(null, null, new ChatMetadataHelper());
    List<Document> merged = service.mergeHybridResults(List.of(
            doc("A", "标题", "sid", 0, 1, 0.8d)
    ), List.of(
            doc("A", "标题", "sid", 0, 1, 3.0d)
    ), 5);

    assertThat(merged).hasSize(1);
    assertThat(merged.get(0).getMetadata().get("scoreLabel")).isEqualTo("融合得分");
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q "-Dtest=ChatRoutingPolicyTest,RetrievalPipelineServiceTest,DocumentRerankServiceTest" test`

Expected: FAIL with missing classes / methods.

- [ ] **Step 3: Write minimal implementation**

Move metadata helpers, keyword extraction, hybrid merge, rerank scoring, dynamic topK, and routing thresholds into dedicated services. Replace literal score labels with constants.

```java
final class ChatMetadataHelper {
    static final String SCORE_LABEL_VECTOR = "相似度";
    static final String SCORE_LABEL_KEYWORD = "关键词得分";
    static final String SCORE_LABEL_HYBRID = "融合得分";
    static final String SCORE_LABEL_MODEL_RERANK = "模型重排得分";

    String getMetadataString(Document document, String key, String defaultValue) {
        Map<String, Object> metadata = document.getMetadata();
        if (metadata == null) {
            return defaultValue;
        }
        Object value = metadata.get(key);
        return value == null ? defaultValue : String.valueOf(value).trim();
    }
}
```

```java
@Service
public class ChatRoutingPolicy {
    public int determineTopK(Session session, String query) {
        // move current ChatServiceImpl bucket / threshold logic here first
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q "-Dtest=ChatRoutingPolicyTest,RetrievalPipelineServiceTest,DocumentRerankServiceTest,ChatServiceImplTest" test`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add rag-csdn-server/src/main/java/com/example/ragcsdn/service/chat/ChatMetadataHelper.java rag-csdn-server/src/main/java/com/example/ragcsdn/service/chat/ChatRoutingPolicy.java rag-csdn-server/src/main/java/com/example/ragcsdn/service/chat/RetrievalPipelineService.java rag-csdn-server/src/main/java/com/example/ragcsdn/service/chat/DocumentRerankService.java rag-csdn-server/src/main/java/com/example/ragcsdn/service/impl/ChatServiceImpl.java rag-csdn-server/src/test/java/com/example/ragcsdn/service/chat/ChatRoutingPolicyTest.java rag-csdn-server/src/test/java/com/example/ragcsdn/service/chat/RetrievalPipelineServiceTest.java rag-csdn-server/src/test/java/com/example/ragcsdn/service/chat/DocumentRerankServiceTest.java rag-csdn-server/src/test/java/com/example/ragcsdn/service/impl/ChatServiceImplTest.java
git commit -m "refactor: extract chat retrieval and rerank pipeline"
```

### Task 4: Split Article Import Decision and Command Responsibilities

**Files:**
- Create: `rag-csdn-server/src/main/java/com/example/ragcsdn/service/article/BatchImportStatus.java`
- Create: `rag-csdn-server/src/main/java/com/example/ragcsdn/service/article/ArticleLinkDiscoveryService.java`
- Create: `rag-csdn-server/src/main/java/com/example/ragcsdn/service/article/ArticleImportDecisionService.java`
- Create: `rag-csdn-server/src/main/java/com/example/ragcsdn/service/article/ArticleImportCommandService.java`
- Modify: `rag-csdn-server/src/main/java/com/example/ragcsdn/service/article/BatchImportResponseAssembler.java`
- Modify: `rag-csdn-server/src/main/java/com/example/ragcsdn/service/impl/ArticleServiceImpl.java`
- Test: `rag-csdn-server/src/test/java/com/example/ragcsdn/service/article/ArticleLinkDiscoveryServiceTest.java`
- Test: `rag-csdn-server/src/test/java/com/example/ragcsdn/service/article/ArticleImportDecisionServiceTest.java`
- Test: `rag-csdn-server/src/test/java/com/example/ragcsdn/service/article/ArticleImportCommandServiceTest.java`

- [ ] **Step 1: Write the failing tests**

Write direct tests for duplicate/retry decisions and batch status constants.

```java
@Test
void decideImport_shouldRetryFailedArticleInsteadOfThrowingDuplicate() {
    Article failed = new Article();
    failed.setStatus(ArticleStatus.FAILED.getCode());
    failed.setSourceId("147000001");
    ArticleImportDecisionService service = new ArticleImportDecisionService();

    ArticleImportDecisionService.ImportDecision decision =
            service.decideExistingArticle(failed);

    assertThat(decision.action()).isEqualTo(ArticleImportDecisionService.Action.RETRY_FAILED);
}

@Test
void addSubmitted_shouldUseUnifiedBatchStatusConstant() {
    BatchImportResponseAssembler assembler = new BatchImportResponseAssembler();
    BatchImportResponse response = assembler.newResponse("AUTHOR_PUBLIC", "target", 1);
    CsdnArticleLink link = new CsdnArticleLink("sid", "url", "title");
    ArticleResponse article = new ArticleResponse();
    article.setId(9L);

    assembler.addSubmitted(response, link, article);

    assertThat(response.getItems().get(0).getStatus()).isEqualTo(BatchImportStatus.SUBMITTED.code());
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q "-Dtest=ArticleImportDecisionServiceTest,ArticleImportCommandServiceTest,ArticleLinkDiscoveryServiceTest,ArticleServiceImplTest" test`

Expected: FAIL with missing classes / constants.

- [ ] **Step 3: Write minimal implementation**

Introduce a unified batch-status constant type, discovery collaborator, import decision collaborator, and import command collaborator. Keep the async execution sequence unchanged.

```java
public enum BatchImportStatus {
    SUBMITTED("SUBMITTED", "已提交导入任务"),
    SKIPPED_DUPLICATE("SKIPPED_DUPLICATE", "文章已存在"),
    FAILED("FAILED", "导入失败");

    private final String code;
    private final String defaultMessage;

    // getters
}
```

```java
@Service
public class ArticleImportDecisionService {
    public ImportDecision decideExistingArticle(Article existingArticle) {
        if (existingArticle == null) {
            return new ImportDecision(Action.CREATE_NEW, null);
        }
        if (ArticleStatus.FAILED.getCode().equals(existingArticle.getStatus())) {
            return new ImportDecision(Action.RETRY_FAILED, existingArticle);
        }
        return new ImportDecision(Action.REJECT_DUPLICATE, existingArticle);
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q "-Dtest=ArticleImportDecisionServiceTest,ArticleImportCommandServiceTest,ArticleLinkDiscoveryServiceTest,ArticleServiceImplTest,BatchImportResponseAssemblerTest" test`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add rag-csdn-server/src/main/java/com/example/ragcsdn/service/article/BatchImportStatus.java rag-csdn-server/src/main/java/com/example/ragcsdn/service/article/ArticleLinkDiscoveryService.java rag-csdn-server/src/main/java/com/example/ragcsdn/service/article/ArticleImportDecisionService.java rag-csdn-server/src/main/java/com/example/ragcsdn/service/article/ArticleImportCommandService.java rag-csdn-server/src/main/java/com/example/ragcsdn/service/article/BatchImportResponseAssembler.java rag-csdn-server/src/main/java/com/example/ragcsdn/service/impl/ArticleServiceImpl.java rag-csdn-server/src/test/java/com/example/ragcsdn/service/article/ArticleLinkDiscoveryServiceTest.java rag-csdn-server/src/test/java/com/example/ragcsdn/service/article/ArticleImportDecisionServiceTest.java rag-csdn-server/src/test/java/com/example/ragcsdn/service/article/ArticleImportCommandServiceTest.java rag-csdn-server/src/test/java/com/example/ragcsdn/service/impl/ArticleServiceImplTest.java
git commit -m "refactor: split article import orchestration responsibilities"
```

### Task 5: Split CSDN Fetching, Selection, Noise Filtering, and Assembly

**Files:**
- Create: `rag-csdn-server/src/main/java/com/alibaba/cloud/ai/reader/csdn/CsdnFetchPolicy.java`
- Create: `rag-csdn-server/src/main/java/com/alibaba/cloud/ai/reader/csdn/CsdnHttpFetcher.java`
- Create: `rag-csdn-server/src/main/java/com/alibaba/cloud/ai/reader/csdn/CsdnContentSelector.java`
- Create: `rag-csdn-server/src/main/java/com/alibaba/cloud/ai/reader/csdn/CsdnNoiseFilter.java`
- Create: `rag-csdn-server/src/main/java/com/alibaba/cloud/ai/reader/csdn/CsdnDocumentAssembler.java`
- Modify: `rag-csdn-server/src/main/java/com/alibaba/cloud/ai/reader/csdn/CsdnDocumentReader.java`
- Test: `rag-csdn-server/src/test/java/com/alibaba/cloud/ai/reader/csdn/CsdnHttpFetcherTest.java`
- Test: `rag-csdn-server/src/test/java/com/alibaba/cloud/ai/reader/csdn/CsdnContentSelectorTest.java`
- Test: `rag-csdn-server/src/test/java/com/alibaba/cloud/ai/reader/csdn/CsdnNoiseFilterTest.java`

- [ ] **Step 1: Write the failing tests**

Add focused tests for fetch retry policy, content selection, and noise filtering so the logic can move out of `CsdnDocumentReader`.

```java
@Test
void isRetryableHttpStatus_shouldReadFromFetchPolicy() {
    CsdnFetchPolicy policy = CsdnFetchPolicy.defaultPolicy();

    assertThat(policy.retryableStatusCodes()).contains(408, 429, 503, 524);
}

@Test
void findContentElement_shouldPreferStructuredSelectorsInOrder() {
    org.jsoup.nodes.Document page = Jsoup.parse("""
            <html><body><article><div id="content_views">正文</div></article></body></html>
            """);
    CsdnContentSelector selector = new CsdnContentSelector();

    Element content = selector.findContentElement(page);

    assertThat(content.id()).isEqualTo("content_views");
}

@Test
void isNoiseLine_shouldRejectStandaloneMetricsAndShareLines() {
    CsdnNoiseFilter filter = new CsdnNoiseFilter();

    assertThat(filter.isNoiseLine("点赞数 12")).isTrue();
    assertThat(filter.isNoiseLine("分享至微信")).isTrue();
    assertThat(filter.isNoiseLine("这是正文")).isFalse();
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q "-Dtest=CsdnHttpFetcherTest,CsdnContentSelectorTest,CsdnNoiseFilterTest,CsdnDocumentReaderTest" test`

Expected: FAIL with missing classes / methods.

- [ ] **Step 3: Write minimal implementation**

Move fetch constants and helper methods into `CsdnFetchPolicy`, retry logic into `CsdnHttpFetcher`, selector order into `CsdnContentSelector`, and regex-based filtering into `CsdnNoiseFilter`. Keep `CsdnDocumentReader` as the entry-point coordinator.

```java
public record CsdnFetchPolicy(
        int maxFetchAttempts,
        long baseRetryDelayMillis,
        Duration connectTimeout,
        Duration requestTimeout,
        Set<Integer> retryableStatusCodes,
        String userAgent
) {
    public static CsdnFetchPolicy defaultPolicy() {
        return new CsdnFetchPolicy(
                3,
                400L,
                Duration.ofSeconds(15),
                Duration.ofSeconds(30),
                Set.of(408, 429, 500, 502, 503, 504, 521, 522, 523, 524),
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36"
        );
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q "-Dtest=CsdnHttpFetcherTest,CsdnContentSelectorTest,CsdnNoiseFilterTest,CsdnDocumentReaderTest,CsdnAccessBlockDetectorTest,CsdnDocumentMetadataBuilderTest" test`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add rag-csdn-server/src/main/java/com/alibaba/cloud/ai/reader/csdn/CsdnFetchPolicy.java rag-csdn-server/src/main/java/com/alibaba/cloud/ai/reader/csdn/CsdnHttpFetcher.java rag-csdn-server/src/main/java/com/alibaba/cloud/ai/reader/csdn/CsdnContentSelector.java rag-csdn-server/src/main/java/com/alibaba/cloud/ai/reader/csdn/CsdnNoiseFilter.java rag-csdn-server/src/main/java/com/alibaba/cloud/ai/reader/csdn/CsdnDocumentAssembler.java rag-csdn-server/src/main/java/com/alibaba/cloud/ai/reader/csdn/CsdnDocumentReader.java rag-csdn-server/src/test/java/com/alibaba/cloud/ai/reader/csdn/CsdnHttpFetcherTest.java rag-csdn-server/src/test/java/com/alibaba/cloud/ai/reader/csdn/CsdnContentSelectorTest.java rag-csdn-server/src/test/java/com/alibaba/cloud/ai/reader/csdn/CsdnNoiseFilterTest.java rag-csdn-server/src/test/java/com/alibaba/cloud/ai/reader/csdn/CsdnDocumentReaderTest.java
git commit -m "refactor: split csdn fetch and parsing pipeline"
```

### Task 6: Run Batch 1 Full Verification and Reduce Orchestrator Classes

**Files:**
- Modify: `rag-csdn-server/src/main/java/com/example/ragcsdn/service/impl/ChatServiceImpl.java`
- Modify: `rag-csdn-server/src/main/java/com/example/ragcsdn/service/impl/ArticleServiceImpl.java`
- Modify: `rag-csdn-server/src/main/java/com/alibaba/cloud/ai/reader/csdn/CsdnDocumentReader.java`

- [ ] **Step 1: Write the failing integration assertions**

Before final cleanup, add small assertions to existing tests that prove the top-level classes now delegate rather than own detail-heavy logic.

```java
@Test
void chatService_shouldStillBuildSystemPromptAfterServiceExtraction() throws Exception {
    String prompt = invokeBuildSystemPrompt("上下文");
    assertThat(prompt).contains("智能问答助手");
}
```

- [ ] **Step 2: Run test to verify failure**

Run: `mvn -q "-Dtest=ChatServiceImplTest,ArticleServiceImplTest,CsdnDocumentReaderTest" test`

Expected: FAIL if orchestration wiring broke during extraction.

- [ ] **Step 3: Write minimal implementation**

Delete dead private helpers from the three orchestrators, inject the new collaborators, and keep only sequencing / boundary logic.

```java
@Autowired
private ConversationMemoryService conversationMemoryService;

@Autowired
private QueryUnderstandingService queryUnderstandingService;

@Autowired
private RetrievalPipelineService retrievalPipelineService;

@Autowired
private DocumentRerankService documentRerankService;
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q "-Dtest=ChatServiceImplTest,ArticleServiceImplTest,CsdnDocumentReaderTest" test`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add rag-csdn-server/src/main/java/com/example/ragcsdn/service/impl/ChatServiceImpl.java rag-csdn-server/src/main/java/com/example/ragcsdn/service/impl/ArticleServiceImpl.java rag-csdn-server/src/main/java/com/alibaba/cloud/ai/reader/csdn/CsdnDocumentReader.java rag-csdn-server/src/test/java/com/example/ragcsdn/service/impl/ChatServiceImplTest.java rag-csdn-server/src/test/java/com/example/ragcsdn/service/impl/ArticleServiceImplTest.java rag-csdn-server/src/test/java/com/alibaba/cloud/ai/reader/csdn/CsdnDocumentReaderTest.java
git commit -m "refactor: reduce orchestrator classes after extraction"
```

### Task 7: Split Cleaning Metrics Evaluation Responsibilities

**Files:**
- Create: `rag-csdn-server/src/main/java/com/example/ragcsdn/cleaning/eval/CleaningMetricsConstants.java`
- Create: `rag-csdn-server/src/main/java/com/example/ragcsdn/cleaning/eval/CleaningSampleEvaluator.java`
- Create: `rag-csdn-server/src/main/java/com/example/ragcsdn/cleaning/eval/CleaningMetricsAggregator.java`
- Create: `rag-csdn-server/src/main/java/com/example/ragcsdn/cleaning/eval/CleaningFindingCollector.java`
- Modify: `rag-csdn-server/src/main/java/com/example/ragcsdn/cleaning/eval/CleaningMetricsEngine.java`
- Test: `rag-csdn-server/src/test/java/com/example/ragcsdn/cleaning/eval/CleaningSampleEvaluatorTest.java`
- Test: `rag-csdn-server/src/test/java/com/example/ragcsdn/cleaning/eval/CleaningMetricsAggregatorTest.java`
- Test: `rag-csdn-server/src/test/java/com/example/ragcsdn/cleaning/eval/CleaningFindingCollectorTest.java`
- Modify: `rag-csdn-server/src/test/java/com/example/ragcsdn/cleaning/eval/CleaningMetricsEngineTest.java`

- [ ] **Step 1: Write the failing tests**

Add tests for single-sample scoring, aggregate summaries, and comparison assembly.

```java
@Test
void aggregate_shouldAverageMetricColumns() {
    CleaningMetricsAggregator aggregator = new CleaningMetricsAggregator();

    CleaningBatchSummary summary = aggregator.summarize(List.of(
            new CleaningMetrics("a-1", 0.8d, 0.7d, 0.1d, 1.0d, 1.0d, 1.0d),
            new CleaningMetrics("a-2", 0.6d, 0.5d, 0.3d, 0.0d, 1.0d, 1.0d)
    ));

    assertThat(summary.averageCodeIntegrity()).isEqualTo(0.7d);
    assertThat(summary.top1HitRate()).isEqualTo(0.5d);
}

@Test
void evaluateSample_shouldRetainCodeBlocksAndMeasureNoiseLeakage() {
    CleaningSampleEvaluator evaluator = new CleaningSampleEvaluator();
    EvaluationArticleSample sample = new EvaluationArticleSample(
            "a-1", "标题", "url", "blog",
            "<div><h2>安装</h2><pre><code>return a;</code></pre><p>正文</p><p>点赞数 1</p></div>"
    );
    CleaningRunArtifact artifact = new CleaningRunArtifact("a-1", "## 安装\nreturn a;\n正文", List.of("## 安装", "return a;", "正文"));

    CleaningMetrics metrics = evaluator.evaluateSingle(sample, artifact, List.of(artifact));

    assertThat(metrics.codeIntegrity()).isGreaterThan(0.0d);
    assertThat(metrics.invalidContentRatio()).isLessThan(1.0d);
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q "-Dtest=CleaningMetricsEngineTest,CleaningSampleEvaluatorTest,CleaningMetricsAggregatorTest,CleaningFindingCollectorTest" test`

Expected: FAIL with missing classes / methods.

- [ ] **Step 3: Write minimal implementation**

Move single-sample evaluation, aggregation, and comparison/finding collection out of `CleaningMetricsEngine`, and centralize shared labels and thresholds.

```java
final class CleaningMetricsConstants {
    static final int SNIPPET_LENGTH = 18;
    static final double HEADING_WEIGHT = 2.0d;
    static final double CODE_WEIGHT = 2.0d;
    static final double BODY_WEIGHT = 1.0d;

    private CleaningMetricsConstants() {
    }
}
```

```java
public class CleaningMetricsAggregator {
    public CleaningBatchSummary summarize(List<CleaningMetrics> metrics) {
        // move current average calculation here first
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q "-Dtest=CleaningMetricsEngineTest,CleaningSampleEvaluatorTest,CleaningMetricsAggregatorTest,CleaningFindingCollectorTest" test`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add rag-csdn-server/src/main/java/com/example/ragcsdn/cleaning/eval/CleaningMetricsConstants.java rag-csdn-server/src/main/java/com/example/ragcsdn/cleaning/eval/CleaningSampleEvaluator.java rag-csdn-server/src/main/java/com/example/ragcsdn/cleaning/eval/CleaningMetricsAggregator.java rag-csdn-server/src/main/java/com/example/ragcsdn/cleaning/eval/CleaningFindingCollector.java rag-csdn-server/src/main/java/com/example/ragcsdn/cleaning/eval/CleaningMetricsEngine.java rag-csdn-server/src/test/java/com/example/ragcsdn/cleaning/eval/CleaningMetricsEngineTest.java rag-csdn-server/src/test/java/com/example/ragcsdn/cleaning/eval/CleaningSampleEvaluatorTest.java rag-csdn-server/src/test/java/com/example/ragcsdn/cleaning/eval/CleaningMetricsAggregatorTest.java rag-csdn-server/src/test/java/com/example/ragcsdn/cleaning/eval/CleaningFindingCollectorTest.java
git commit -m "refactor: split cleaning metrics evaluation pipeline"
```

### Task 8: Run Full Regression and Prepare Merge-Ready Branch State

**Files:**
- Modify: `rag-csdn-server/src/main/java/com/example/ragcsdn/service/impl/ChatServiceImpl.java`
- Modify: `rag-csdn-server/src/main/java/com/example/ragcsdn/service/impl/ArticleServiceImpl.java`
- Modify: `rag-csdn-server/src/main/java/com/alibaba/cloud/ai/reader/csdn/CsdnDocumentReader.java`
- Modify: `rag-csdn-server/src/main/java/com/example/ragcsdn/cleaning/eval/CleaningMetricsEngine.java`
- Modify: any newly created collaborator classes from Tasks 2-7 as needed

- [ ] **Step 1: Write the final verification checklist into tests/comments if any gap remains**

If any orchestrator still owns duplicated constants or helper logic, add a regression assertion before deleting the last leftover method.

```java
@Test
void chatService_refactor_shouldPreserveTopKDecisionBoundaries() throws Exception {
    Session session = new Session();
    int topK = invokeDetermineTopK(session, "Spring AI 如何接入 RAG");
    assertThat(topK).isBetween(3, 8);
}
```

- [ ] **Step 2: Run targeted tests to verify failure if a leftover regression exists**

Run: `mvn -q "-Dtest=ChatServiceImplTest,ArticleServiceImplTest,CsdnDocumentReaderTest,CleaningMetricsEngineTest" test`

Expected: PASS if no extra gap exists; if a final gap appears, fix it before full regression.

- [ ] **Step 3: Write minimal cleanup implementation**

Remove leftover unused imports, dead private methods, duplicated constants, and reflection-only tests that now have first-class unit coverage.

```java
// Example cleanup target after extraction
// delete private helper methods from ChatServiceImpl once all call sites use injected services
```

- [ ] **Step 4: Run full regression**

Run: `mvn -q test`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add rag-csdn-server/src/main/java rag-csdn-server/src/test/java
git commit -m "refactor: complete god class phase 2 extraction"
```
