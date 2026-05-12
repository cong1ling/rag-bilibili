# God Class Phase 2 Refactor Design

**Date:** 2026-05-12

## Goal

在保持现有行为和测试结果稳定的前提下，继续拆分当前残留的上帝类，并顺手治理其中的硬编码行为、提示词和可抽取常量。

本轮覆盖四个目标类：

- `rag-csdn-server/src/main/java/com/example/ragcsdn/service/impl/ChatServiceImpl.java`
- `rag-csdn-server/src/main/java/com/example/ragcsdn/service/impl/ArticleServiceImpl.java`
- `rag-csdn-server/src/main/java/com/alibaba/cloud/ai/reader/csdn/CsdnDocumentReader.java`
- `rag-csdn-server/src/main/java/com/example/ragcsdn/cleaning/eval/CleaningMetricsEngine.java`

## Problem Statement

当前代码库已经完成一次“明显职责抽取”，但核心复杂度仍然集中在少数大类中：

- `ChatServiceImpl` 仍然承担 SSE 编排、会话记忆、查询理解、检索路由、混合检索、重排、日志与配置访问等多种职责。
- `ArticleServiceImpl` 同时负责链接发现、去重判断、状态流转、导入提交和响应组装。
- `CsdnDocumentReader` 同时承担网络抓取、重试策略、页面正文定位、噪声过滤和文档组装。
- `CleaningMetricsEngine` 既做样本遍历，又做指标计算、明细汇总和结果输出准备。

这些类的问题不只是“文件大”，而是职责边界不清，导致：

- 改动一个子流程需要重新理解整类上下文。
- 测试难以针对单一行为收缩。
- 硬编码策略散落在流程类中，后续调参只能继续扩大同一类。

## Scope

### In Scope

- 将四个目标类继续拆成职责明确的协作者。
- 把流程中的提示词、状态字面量、来源标签、抓取策略、评测规则等整理成常量类或策略类。
- 保持外部 API、已有主流程和测试预期不变。
- 为新拆出的类补充或迁移单元测试。

### Out of Scope

- 不修改接口契约。
- 不变更数据库结构。
- 不重写检索算法或引入新的框架。
- 不把所有常量都外置到配置；仅将“行为策略/跨类共享文案”提升为常量或集中策略。

## Refactor Strategy

采用两批执行，而不是四个类一次性混改：

### Batch 1

- `ChatServiceImpl`
- `ArticleServiceImpl`
- `CsdnDocumentReader`

这三者处在主业务链路中，需要一起整理职责边界，但仍保持“行为不变优先”。

### Batch 2

- `CleaningMetricsEngine`

该类偏离线评测与工具链，单独拆可以避免和主链路验证绑死。

## Target Architecture

### 1. ChatServiceImpl

`ChatServiceImpl` 调整为编排层，只保留：

- 会话校验与消息持久化入口
- SSE 生命周期控制
- 调用各协作者的顺序编排
- 失败兜底和主链路日志

拆出的协作者如下：

- `ConversationMemoryService`
  - 负责历史消息筛选、摘要刷新、摘要生成与标准化。
- `QueryUnderstandingService`
  - 负责 query rewrite、query intent 分类、HyDE 生成、query decomposition。
- `RetrievalPipelineService`
  - 负责 scoped source 解析、vector 检索、keyword 检索、hybrid merge。
- `DocumentRerankService`
  - 负责规则重排、模型重排、顺序解析与重排得分标签。
- `ChatRoutingPolicy`
  - 负责动态 topK、复杂度路由、HyDE/Decomposition 触发判定、LLM fallback 判定。
- `ChatMetadataHelper`
  - 负责来源头默认值、score label、metadata 读取等共享细节。

同时整理以下硬编码：

- 查询分类 prompt
- HyDE / decomposition / summary / model rerank prompt
- 来源标签，如“相似度”“关键词得分”“融合得分”“模型重排得分”
- 默认阈值和默认 score label

目标结果：

- `ChatServiceImpl` 降为主链路 orchestrator。
- 单个协作者聚焦单一职责，可单测独立验证。

### 2. ArticleServiceImpl

`ArticleServiceImpl` 调整为导入编排层，只保留：

- 对外 service 入口
- 主流程顺序组织
- 失败路径协调

拆出的协作者如下：

- `ArticleLinkDiscoveryService`
  - 负责作者文章/推荐文章链接发现。
- `ArticleImportDecisionService`
  - 负责去重、失败重试入口判断、是否跳过。
- `ArticleImportCommandService`
  - 负责创建文章记录、提交导入动作、状态推进。
- `BatchImportStatus`
  - 作为响应状态与文案常量来源，替代裸字符串。

当前已经存在的 `BatchImportResponseAssembler`、`ArticleImportFailureHandler` 等类继续复用，避免重复拆分。

### 3. CsdnDocumentReader

`CsdnDocumentReader` 保留 `DocumentReader` 入口职责，拆出：

- `CsdnFetchPolicy`
  - 持有抓取超时、重试次数、退避、可重试状态码、User-Agent 等策略常量。
- `CsdnHttpFetcher`
  - 负责 HTTP 请求、重试、transport error 判定。
- `CsdnContentSelector`
  - 负责正文 DOM selector 列表和正文节点选择。
- `CsdnNoiseFilter`
  - 负责噪声正则、过滤规则和内容段清洗。
- `CsdnDocumentAssembler`
  - 负责 title/description/author/canonicalUrl/documentText 的最终装配。

当前已有的 `CsdnAccessBlockDetector`、`CsdnDocumentMetadataBuilder` 继续保留并纳入协作链。

### 4. CleaningMetricsEngine

`CleaningMetricsEngine` 单独拆分为评测流程层，只保留：

- 样本集评测入口
- 调用指标汇总与报告装配

拆出的协作者如下：

- `CleaningSampleEvaluator`
  - 负责单样本评测和原始结果生成。
- `CleaningMetricsAggregator`
  - 负责聚合 precision / recall / noise removal / structure preservation 等指标。
- `CleaningFindingCollector`
  - 负责异常项、坏样本、明细记录整理。
- `CleaningMetricsConstants`
  - 负责评测阈值、标签、默认列名或分类枚举常量。

## Data Flow

### Chat Flow

1. `ChatServiceImpl` 接收消息并创建 SSE emitter。
2. `ConversationMemoryService` 生成最近消息和摘要上下文。
3. `QueryUnderstandingService` 产出 rewrite / intent / retrieval queries。
4. `ChatRoutingPolicy` 给出 topK、HyDE、decomposition、fallback 决策。
5. `RetrievalPipelineService` 拉取候选文档并合并。
6. `DocumentRerankService` 返回最终文档顺序。
7. `ChatPromptBuilder` 与 `ResponseConfidenceService` 组装提示词和置信度。
8. `ChatServiceImpl` 将最终响应写入 SSE 与持久层。

### Article Import Flow

1. `ArticleServiceImpl` 调用 `ArticleLinkDiscoveryService` 获取候选链接。
2. `ArticleImportDecisionService` 判断 submitted / duplicate / retryable failure 分支。
3. `ArticleImportCommandService` 执行文章创建与导入提交。
4. `BatchImportResponseAssembler` 根据统一状态常量组装返回。

### CSDN Reader Flow

1. `CsdnHttpFetcher` 按 `CsdnFetchPolicy` 抓取 HTML。
2. `CsdnContentSelector` 定位正文节点。
3. `CsdnNoiseFilter` 清洗噪声与无效段落。
4. `CsdnDocumentAssembler` 生成 `Document` 文本和 metadata。

### Cleaning Evaluation Flow

1. `CleaningMetricsEngine` 读取样本集。
2. `CleaningSampleEvaluator` 计算单样本清洗结果。
3. `CleaningMetricsAggregator` 聚合整体指标。
4. `CleaningFindingCollector` 输出异常明细。

## Constants and Hardcoding Policy

本轮按以下标准处理硬编码：

- 会影响运行策略的字面量：抽成常量或策略类。
- 会跨多个类复用的状态值/标签/提示词：抽成常量类。
- 仅局部、明显表达实现意图的短字面量：保留在局部实现中。

优先收敛的常量类别：

- Chat prompt 文案与 prompt 模板
- 检索 score label 和来源标签
- 批量导入状态码与默认消息
- CSDN 抓取 retry / timeout / UA / selector / noise 规则
- Cleaning metrics 标签与默认阈值

## Testing Strategy

坚持测试先行，按拆分单元逐步迁移：

### Batch 1

- `ChatServiceImpl` 相关测试先锁定主链路行为：
  - `ChatServiceImplTest`
  - `ChatPromptBuilderTest`
  - `ResponseConfidenceServiceTest`
  - 新增协作者测试
- `ArticleServiceImplTest` 及新增导入协作者测试
- `CsdnDocumentReader` 定向测试与新增 fetch/select/filter/assembler 测试

### Batch 2

- `CleaningMetricsEngine` 现有相关测试
- 新增 evaluator / aggregator / finding collector 测试

### Verification Gate

- 每拆一块先跑定向测试
- 每个 batch 结束跑 `rag-csdn-server` 全量测试
- 如果行为发生偏差，以测试回归为准，不在同批中继续扩展需求

## Risks

- `ChatServiceImpl` 拆分时最容易出现协作者间上下文对象设计不当，导致参数数量膨胀。
- `CsdnDocumentReader` 拆分时最容易把“抓取策略”和“解析结果”耦合打散过头，增加来回跳转成本。
- `ArticleServiceImpl` 如果把导入决策和命令执行界线划错，会导致重复状态写入。
- `CleaningMetricsEngine` 如果在第一批一起拆，会拖慢主链路验证，因此明确放到第二批。

## Success Criteria

- `ChatServiceImpl` 明显降为编排层，不再承载 query understanding、retrieval、rerank 的完整实现细节。
- `ArticleServiceImpl` 只保留导入编排职责。
- `CsdnDocumentReader` 的抓取、选择、过滤、装配职责分离。
- `CleaningMetricsEngine` 不再同时承担遍历、聚合、明细整理三种职责。
- 核心硬编码被集中到常量类或策略类。
- 定向测试与 `rag-csdn-server` 全量测试通过。
