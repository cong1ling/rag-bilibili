# Query Routing Cost Reduction Design

## Goal

Reduce query-understanding cost in the chat retrieval pipeline without materially degrading answer quality for common queries.

This iteration keeps the current `ChatServiceImpl` flow shape, preserves existing HyDE and decomposition fallback behavior for difficult cases, and treats data cleaning improvements as future design space rather than current implementation scope.

## Scope

### Included

- Add a local rule-based query complexity analyzer ahead of LLM query classification.
- Keep query rewrite as the default retained capability for multi-turn context completion.
- Route most queries by local scores first, with LLM classification used only as a fallback for low-confidence cases.
- Gate HyDE and decomposition with narrower trigger conditions to reduce unnecessary LLM calls.
- Replace hardcoded complexity decision logic with configurable thresholds and switches.
- Add observability for routing decisions, fallback usage, and retrieval sizing.
- Keep the current retrieval stack shape:
  - rewrite
  - intent understanding
  - optional HyDE
  - optional decomposition
  - retrieval
  - rerank

### Excluded

- No large-scale refactor of `ChatServiceImpl` into a separate orchestration layer in this iteration.
- No new database tables for online learning, user feedback, or routing analytics.
- No model replacement or prompt redesign for the final answer generation step.
- No immediate rewrite of `CsdnDocumentReader` or `ChunkDocumentSplitter`.
- No structured block-based ingestion in this iteration.
- No offline evaluation platform or A/B framework in this iteration.

## Current Problems

### Query Understanding

Current query understanding is implemented in `ChatServiceImpl` and uses separate LLM calls for:

- query rewrite
- query classification
- HyDE document generation
- broad query decomposition
- conversation summary generation

This causes several problems:

1. Query classification is expensive and unstable because it depends on an LLM call for a decision that is often simple.
2. Several front-loaded decisions happen before retrieval, so cost accumulates before any answer generation value is produced.
3. Heuristic complexity logic exists, but it is too narrow and duplicated:
   - `isSimpleFactQuery`
   - `isComplexQuery`
4. Complexity routing is based mostly on length, keywords, and cue words rather than a unified confidence-aware decision.
5. Dynamic `TopK` values are configurable, but the logic that selects between them is still effectively hardcoded in code paths.
6. The system does not currently emit enough routing telemetry to tune thresholds safely.

### Data Cleaning

Current article cleaning is based on DOM element removal plus line-level noise filtering. This is useful, but limited.

Important clarifications:

- Some metadata lines such as `作者：` and `分类专栏：` already have basic filtering rules.
- The issue is not complete absence of filtering; the issue is that filtering is still mostly line-based and structure-unaware.

The main limitations are:

- fixed token splitting can break paragraph and code boundaries
- code blocks are not treated as first-class structure
- heading hierarchy is flattened into plain text
- whitespace normalization removes indentation fidelity that matters for code
- semantically weak or mixed-content sections can still survive cleaning

These issues affect retrieval quality, but they are intentionally left as future work for this iteration to keep scope low-risk.

## Design Principles

1. Cost reduction comes before broad feature expansion.
2. Quality for common direct queries must remain materially unchanged.
3. Rule-based routing should handle the majority path.
4. LLM routing remains available as a narrow fallback, not the default path.
5. Configuration and observability must exist before aggressive threshold tuning.
6. This iteration must not block a future structured-ingestion cleanup redesign.

## Proposed Approach

The system will move from:

`rewrite -> classify by LLM -> HyDE or decompose -> retrieve`

to:

`rewrite -> analyze locally -> direct route or LLM fallback -> retrieve`

The recommended implementation is a rule-first, confidence-aware routing layer that decides whether the query can be handled locally or needs the existing LLM classifier as a fallback.

## Architecture

### New Component: `QueryComplexityAnalyzer`

Add a new focused component responsible only for local query analysis. It should not perform retrieval, prompt construction, or LLM invocation.

Responsibilities:

- inspect the rewritten query and lightweight conversation context
- compute local routing scores
- produce a routing suggestion
- emit a confidence score for whether local routing is trustworthy

Suggested outputs:

- `ambiguityScore`
- `breadthScore`
- `complexityScore`
- `decisionConfidence`
- `suggestedIntent`

This component should be deterministic and unit-testable.

### `ChatServiceImpl` Changes

`ChatServiceImpl#understandQuery` remains the entry point, but its decision order changes:

1. Rewrite query if rewrite is enabled and conversation history exists.
2. Run `QueryComplexityAnalyzer` on the rewritten query plus lightweight conversation signals.
3. If analyzer confidence is high enough, route locally:
   - `DIRECT`
   - `AMBIGUOUS`
   - `BROAD`
4. If analyzer confidence is too low, fall back to the existing LLM classifier.
5. Trigger HyDE only when:
   - intent is `AMBIGUOUS`
   - HyDE is enabled
   - ambiguity score exceeds the configured HyDE trigger threshold
6. Trigger decomposition only when:
   - intent is `BROAD`
   - decomposition is enabled
   - breadth score exceeds the configured decomposition trigger threshold

This preserves the old behavior for hard cases while shrinking the LLM usage surface for ordinary ones.

## Routing Logic

### Local Feature Signals

The analyzer should use low-risk lexical and conversational signals only. No embeddings or extra models are required.

Candidate signals:

- pronoun / reference cues:
  - `它`
  - `这个`
  - `那个`
  - `这部分`
  - `前面`
  - `this`
  - `that`
  - `it`
- fact cues:
  - `多少`
  - `谁`
  - `什么`
  - `when`
  - `who`
  - `what`
- broad or analytical cues:
  - `为什么`
  - `原理`
  - `流程`
  - `步骤`
  - `区别`
  - `对比`
  - `总结`
  - `分析`
  - `如何`
  - `tradeoff`
  - `architecture`
- query length
- distinct keyword count
- number of conjunctions suggesting multiple asks
- whether the query appears dependent on recent dialogue
- whether the query includes multiple constraints or comparison targets

### Score Semantics

- `ambiguityScore`: higher when the query is short, referential, elliptical, or context-dependent
- `breadthScore`: higher when the query requests analysis, comparison, process explanation, or multi-part coverage
- `complexityScore`: aggregate notion used for retrieval sizing and fallback confidence
- `decisionConfidence`: lower when multiple scores are close, weakly supported, or contradictory

### Intent Selection

Recommended local routing:

- `AMBIGUOUS` when ambiguity is clearly dominant
- `BROAD` when breadth is clearly dominant
- `DIRECT` otherwise

If dominance is weak or mixed, use the existing LLM classifier as fallback.

## Dynamic TopK Design

This iteration keeps the current configurable values:

- `simpleTopK`
- `normalTopK`
- `complexTopK`

But changes the selection source:

- `DIRECT` with low complexity -> `simpleTopK`
- `DIRECT` with moderate complexity -> `normalTopK`
- `AMBIGUOUS` or `BROAD`, or high complexity -> `complexTopK`

The important change is that `TopK` selection should be fed by the same analyzer score model rather than a separate set of ad hoc regex rules.

## Configuration

Extend `rag.chat` configuration with switches and thresholds dedicated to rule-first routing.

Suggested additions:

- `rule-routing-enabled`
- `rule-routing-llm-fallback-enabled`
- `ambiguity-threshold`
- `breadth-threshold`
- `complexity-threshold`
- `llm-fallback-confidence-threshold`
- `hyde-trigger-threshold`
- `decomposition-trigger-threshold`
- `routing-observation-only`

Behavior:

- `routing-observation-only=true` means analyzer runs and logs decisions, but current routing behavior stays unchanged
- this enables safe production observation before enabling rule-first behavior

## Observability

This change should ship with structured logs or equivalent telemetry before aggressive routing behavior is enabled.

Minimum fields to emit per query:

- `originalQuery`
- `rewrittenQuery`
- `ruleRoutingEnabled`
- `routingObservationOnly`
- `suggestedIntent`
- `finalIntent`
- `ambiguityScore`
- `breadthScore`
- `complexityScore`
- `decisionConfidence`
- `usedLlmFallback`
- `usedHyde`
- `usedDecomposition`
- `finalTopK`
- `retrievedDocCount`

This is sufficient for threshold tuning without adding new persistence tables in this iteration.

## Rollout Plan

### Phase 1: Observation Mode

Ship the analyzer, config, and telemetry, but do not change routing behavior yet.

Behavior:

- analyzer runs
- scores are logged
- current LLM classification still decides final routing

Goal:

- understand score distribution
- identify confidence cut lines
- quantify how many queries would have stayed local safely

### Phase 2: Rule Routing with LLM Fallback

Enable rule-first routing.

Behavior:

- high-confidence local decisions route directly
- low-confidence cases call the current LLM classifier
- HyDE and decomposition are both guarded by score thresholds

Goal:

- reduce front-loaded LLM calls substantially
- maintain answer quality for common direct queries

### Phase 3: Threshold Tuning

Tune configuration after observing real traffic.

Behavior:

- adjust thresholds rather than changing code first
- monitor fallback rate and difficult-query quality

Goal:

- compress cost further without widening regression risk

## Testing Strategy

### Unit Tests

Add focused tests for `QueryComplexityAnalyzer`.

Cases to cover:

- short factual direct query
- short referential ambiguous query
- broad analytical query
- mixed query with low confidence that should trigger fallback
- conversation-dependent query

These tests should assert deterministic scores and suggested intent categories where reasonable.

### Service-Level Tests

Update `ChatServiceImplTest` to validate:

- common direct queries do not call LLM classification when rule confidence is high
- low-confidence cases still use LLM fallback
- HyDE only triggers when both intent and threshold conditions match
- decomposition only triggers when both intent and threshold conditions match
- `TopK` selection comes from analyzer-driven complexity

### Regression Safety

Retain existing behavior coverage for:

- rewritten multi-turn queries
- ambiguous hard cases
- broad multi-subquestion cases
- empty retrieval confidence handling

The success condition is not “no behavior changed.” The success condition is “common queries get cheaper, difficult queries still retain a viable fallback path.”

## Data Cleaning Follow-Up Boundary

This iteration deliberately does not modify `CsdnDocumentReader` or `ChunkDocumentSplitter`.

Future cleanup work should likely move toward:

- structure-preserving extraction
- code-aware blocks
- heading-aware chunking
- indentation-preserving code handling
- metadata block filtering instead of mostly line-level filtering

To avoid blocking that work, query-routing changes in this iteration must not assume any specific future chunk schema beyond the current document text plus metadata model.

## Risks and Mitigations

### Risk: Rule Misclassification

Mitigation:

- keep LLM fallback
- start in observation mode
- tune via config before tightening behavior

### Risk: Hidden Quality Regression on Complex Queries

Mitigation:

- keep HyDE and decomposition available
- use score-gated triggering rather than outright removal
- add targeted regression tests for broad and ambiguous queries

### Risk: Thresholds Become Another Hardcoded System

Mitigation:

- move thresholds to config
- log score distributions
- prefer tuning by configuration over code edits

## Success Metrics

Primary success metric:

- lower average number of front-loaded LLM calls per query

Secondary success metrics:

- fallback rate stays concentrated in genuinely difficult queries
- direct query answer quality remains stable in regression testing
- HyDE and decomposition usage drops where they were previously unnecessary

## Recommended Next Step

After this spec is approved, write an implementation plan that:

1. adds the analyzer and its tests
2. adds configuration and observation-mode logging
3. rewires `understandQuery` to use rule-first routing with LLM fallback
4. updates tests around HyDE, decomposition, and dynamic `TopK`
