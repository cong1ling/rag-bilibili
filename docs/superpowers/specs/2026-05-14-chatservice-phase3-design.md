# ChatService Phase 3 Refactor Design

**Date:** 2026-05-14

## Goal

Continue reducing `ChatServiceImpl` into a thin orchestration layer without changing external behavior. This round keeps prompt text, SSE event structure, routing semantics, and database read/write paths unchanged while extracting four focused collaborators: query rewrite, query expansion, conversation summary, and streaming orchestration.

## Constraints

- Prompt wording must remain unchanged.
- SSE event names, payload shapes, and emission order must remain unchanged.
- Routing decisions and trigger thresholds must remain unchanged.
- Database reads/writes and persistence timing must remain unchanged.
- This round is limited to internal extraction, constant relocation, and test coverage improvements.

## Current Problems

`ChatServiceImpl` is still roughly 1100 lines and still owns several unrelated responsibilities:

- Query rewrite logic and fallback behavior
- Query classification, HyDE generation, and decomposition
- Conversation summary generation and persistence
- SSE start/content/error/end orchestration and assistant message persistence

This creates three practical issues:

1. The class is still too large to reason about safely.
2. Many tests still need reflection or indirect assertions because behavior is hidden behind private methods.
3. The routing and streaming path still mixes domain decisions with infrastructure sequencing.

## Recommended Approach

Use a fine-grained collaborator split and keep `ChatServiceImpl` as the boundary coordinator.

### Why this approach

- It is the lowest-risk way to keep behavior stable.
- It preserves the current workflow and persistence boundaries.
- It lets us replace private-method testing with direct collaborator tests.
- It avoids the anti-pattern of moving the same god class into a differently named god class.

## Alternatives Considered

### 1. Fine-grained collaborator split

Extract four focused services and keep `ChatServiceImpl` as the orchestrator.

- Pros: best long-term shape, safest under behavior-freeze constraints, easiest to test directly
- Cons: adds more classes in the short term

### 2. Single workflow service extraction

Move most of `streamMessage()` into a `ChatSessionWorkflowService`.

- Pros: `ChatServiceImpl` becomes shorter immediately
- Cons: mostly renames the god class and delays the real split

### 3. Aggressive pipeline redesign

Turn routing, expansion, retrieval, prompt building, and streaming into a new pipeline model.

- Pros: cleanest eventual architecture
- Cons: too risky for this round and violates the “no behavior changes” constraint in practice

## Target Structure

After this round, `ChatServiceImpl` should keep only these responsibilities:

- request/session validation
- user message persistence
- coordination across memory, rewrite, expansion, retrieval, prompt, and streaming collaborators
- final persistence boundary ownership

The extracted collaborators are:

### `QueryRewriteService`

Responsibility:

- own `rewriteQuery(...)`
- preserve current feature toggle behavior
- preserve current failure fallback behavior
- incorporate memory summary exactly as today

Non-responsibilities:

- no HyDE generation
- no decomposition
- no retrieval
- no SSE

Proposed interface:

```java
public interface QueryRewriteService {
    String rewrite(
            String query,
            List<org.springframework.ai.chat.messages.Message> historyMessages,
            String summary
    );
}
```

### `QueryExpansionService`

Responsibility:

- own `classifyQuery(...)`
- own `generateHydeDocument(...)`
- own `decomposeQuery(...)`
- build the final `QueryPlan`
- preserve `usedLlmFallback`, `usedHyde`, and `usedDecomposition` semantics

Non-responsibilities:

- no actual retrieval
- no SSE
- no topK calculation

Proposed interface:

```java
public interface QueryExpansionService {
    QueryExpansionDecision expand(
            String originalQuery,
            String rewrittenQuery,
            ConversationMemory memory
    );
}
```

`QueryExpansionDecision` remains conceptually equivalent to today’s `QueryUnderstandingDecision`.

### `ConversationSummaryService`

Responsibility:

- own `summarizeConversation(...)`
- own `refreshAndPersistConversationSummary(...)`
- preserve summary truncation and fallback behavior
- preserve summary persistence timing after assistant completion

Non-responsibilities:

- no recent window slicing
- no history-to-message conversion

That windowing logic remains in `ConversationMemoryService`.

Proposed interface:

```java
public interface ConversationSummaryService {
    String summarize(List<Message> messages);
    void refreshAndPersist(Long sessionId);
}
```

### `ChatStreamingOrchestrator`

Responsibility:

- own the `SseEmitter` lifecycle inside the async execution path
- send `start`, `content`, `error`, and `end` events in the current order
- subscribe to the LLM `Flux<ChatResponse>`
- collect the full assistant response
- persist assistant message
- trigger summary refresh after completion

Non-responsibilities:

- no session validation
- no user message persistence
- no query rewrite/classification/expansion
- no retrieval

Proposed interface:

```java
public interface ChatStreamingOrchestrator {
    void stream(StreamRequest request);
}
```

`StreamRequest` should contain only the data already computed by `ChatServiceImpl`:

- `SseEmitter emitter`
- `Long sessionId`
- `Long userId`
- `Long userMessageId`
- `String userContent`
- `String systemPrompt`
- `List<org.springframework.ai.chat.messages.Message> memoryMessages`
- query/result metadata needed for the final `end` event
- `List<Document> relevantDocs`
- confidence summary
- `boolean summaryUsed`

## Data Flow After Refactor

The runtime flow remains the same, only the ownership changes:

1. `ChatServiceImpl` validates the session.
2. `ChatServiceImpl` persists the user message.
3. `ConversationMemoryService` builds memory context.
4. `QueryRewriteService` rewrites the query.
5. `QueryExpansionService` produces the final `QueryPlan`.
6. `ChatServiceImpl` performs retrieval, rerank, confidence evaluation, and prompt construction using existing collaborators.
7. `ChatStreamingOrchestrator` executes the LLM stream and SSE emission flow.
8. `ConversationSummaryService` refreshes the persisted summary after assistant completion.

This preserves current behavior while shrinking the central coordinator.

## Testing Strategy

Each extraction step should add direct collaborator tests first, then rewire `ChatServiceImpl`.

### Required direct tests

- `QueryRewriteServiceTest`
  - rewrite feature toggle behavior
  - LLM failure fallback
  - summary participation in rewrite

- `QueryExpansionServiceTest`
  - direct / ambiguous / broad classification outcomes
  - HyDE trigger behavior
  - decomposition trigger behavior
  - fallback-to-direct behavior

- `ConversationSummaryServiceTest`
  - summary generation threshold behavior
  - truncation and fallback summary behavior
  - session summary persistence behavior

- `ChatStreamingOrchestratorTest`
  - `start -> content -> end` happy path
  - `error` path
  - assistant message persistence
  - summary refresh after completion

### Existing test retention

`ChatServiceImplTest` remains, but its purpose changes:

- less private-method reflection
- more orchestration and wiring verification
- preserve regression checks around prompt construction, routing outcome, and emitter-visible behavior

## Refactor Sequence

Recommended execution order:

1. Extract `QueryRewriteService`
2. Extract `QueryExpansionService`
3. Extract `ConversationSummaryService`
4. Extract `ChatStreamingOrchestrator`
5. Remove dead glue and reflection-only test coverage where direct unit tests now exist

This sequence starts with the most isolated logic and leaves the riskiest SSE flow for last.

## Additional Cleanup Included In Scope

These items are explicitly in scope because they support the refactor without changing behavior:

- remove the dead injected `chatRoutingPolicy` field if `currentRoutingPolicy()` remains the real source of truth
- relocate any remaining prompt constants that are still embedded inside `ChatServiceImpl`
- convert reflection-heavy tests into first-class collaborator tests where possible

## Out of Scope

These are intentionally excluded from this round:

- changing prompt wording
- changing SSE payload schema
- redesigning routing semantics
- rewriting retrieval flow
- changing persistence transactions or write timing
- converting the chat flow into a new pipeline architecture

## Success Criteria

This round is successful if all of the following are true:

- `ChatServiceImpl` becomes a thin orchestration layer
- the extracted collaborators each have a single clear responsibility
- behavior remains unchanged from the caller’s perspective
- direct collaborator unit tests replace a meaningful portion of private-method coverage
- `ChatServiceImpl` drops into roughly the 500-700 line range without introducing a replacement god class elsewhere
