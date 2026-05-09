# Data Cleaning Optimization Design

## Goal

Improve CSDN article cleaning so the system can quantitatively demonstrate better code preservation, structure retention, noise removal, and retrieval effectiveness.

This iteration must not stop at “cleaner output looks better.” It must produce a reproducible evaluation pipeline with batch statistics and per-article reports, then use that pipeline to measure real improvement after cleaning optimization.

## Scope

### Included

- Build a mixed evaluation sample set of at least `100` CSDN articles.
- Reuse existing imported data first, then supplement with newly fetched articles if the corpus is too small or too narrow.
- Define machine-computable metrics for:
  - code integrity
  - structure retention
  - invalid content ratio
  - retrieval precision
- Produce two evaluation outputs:
  - batch summary report
  - per-article detailed report
- Measure the current implementation as a baseline before introducing the new cleaner.
- Design and implement a structured block-based cleaning pipeline.
- Compare baseline vs optimized output on the same sample set.

### Excluded

- No immediate requirement to change production database schema in this iteration.
- No mandatory online migration of all historical data during the first pass.
- No full retrieval architecture redesign outside the impact caused directly by better cleaned content.
- No dependence on subjective-only manual review as the primary evaluation method.

## Current Problems

### Cleaning Quality

The current cleaning flow is centered on DOM removal plus line-level filtering in `CsdnDocumentReader`, followed by token-based splitting in `ChunkDocumentSplitter`.

This causes several problems:

1. Fixed-size chunking can break paragraph boundaries and code blocks.
2. Code is not treated as a first-class structure, so snippets are often truncated or flattened.
3. Heading hierarchy is mostly lost after cleaning, making contextual reconstruction difficult.
4. Whitespace normalization removes code indentation fidelity.
5. Noise removal is present, but mostly line-based, so mixed-content blocks can still leak through.

### Evaluation Gap

The larger problem is that the system cannot currently prove the quality of cleaning changes with hard numbers.

There is no built-in way to answer questions such as:

- How much invalid content remains after cleaning?
- How many code blocks are preserved intact?
- How much document structure survives cleaning and splitting?
- Whether retrieval quality improved because of cleaner content?

Without a stable evaluation pipeline, cleaning changes are difficult to validate and easy to overclaim.

## Design Principles

1. Metrics come before optimization claims.
2. Baseline and optimized runs must use the same sample set.
3. Reports must be reproducible from code, not hand-written.
4. Block-level structure is required to make the metrics trustworthy.
5. The first iteration should prefer offline evaluability over invasive online schema changes.

## Proposed Approach

The work is split into three stages:

1. Build an evaluation harness and measure the current baseline.
2. Introduce a structured block-based cleaner and block-aware splitter.
3. Re-run the same evaluation set and compare the results.

This means the cleaning project is not just a parser refactor. It is a parser refactor plus an evidence pipeline.

## Architecture

### Component 1: `SampleSetBuilder`

Responsible for creating and maintaining a mixed corpus of at least `100` CSDN articles.

Responsibilities:

- load existing article content from current project data sources when available
- detect whether the current pool is too small or biased
- fetch additional CSDN articles when required
- persist a stable evaluation manifest so repeated runs use the same corpus

Suggested output:

- article id
- source URL
- title
- raw HTML or raw extracted page content
- source type:
  - existing
  - newly fetched

### Component 2: `CleanerBaselineRunner`

Runs the current implementation against the sample set and stores baseline outputs.

Responsibilities:

- execute the current reader + splitter pipeline
- capture resulting cleaned text and chunks
- compute baseline metrics
- emit batch and per-article baseline reports

This stage must happen before optimized cleaning is introduced, so later gains are comparable.

### Component 3: `StructuredCleanerV2`

The optimized cleaner should first parse content into structured blocks before filtering or splitting.

Minimum block types:

- `title`
- `heading`
- `paragraph`
- `list`
- `blockquote`
- `code`
- `table-row`
- `noise`

Responsibilities:

- preserve meaningful structure boundaries
- classify blocks before filtering
- protect code block integrity and indentation
- keep heading context available for downstream splitting
- assign noise labels to removable blocks

### Component 4: `MetricsEngine`

Computes quantitative metrics on both baseline and optimized results.

This is the core of the design because it defines what “better” means.

### Component 5: `ReportGenerator`

Produces human-readable and machine-readable outputs.

Outputs required:

- batch summary report
- per-article detailed report
- raw structured data files for reproducibility

## Metric Definitions

All metrics must be machine-computable and reproducible.

### Code Integrity

Goal:

- measure whether code blocks survive cleaning and splitting in usable form

Per-article signals:

- detected code block count
- fully preserved code block count
- truncated code block count
- indentation-damaged code block count

Primary score:

`fully preserved code blocks / total detected code blocks`

Secondary diagnostics:

- truncation rate
- indentation damage rate

### Structure Retention

Goal:

- measure whether the cleaner keeps document structure recoverable

Per-article signals:

- heading presence retained
- heading level retained
- paragraph boundaries retained
- list boundaries retained
- code block boundaries retained

Recommended scoring:

- heading level retention: high weight
- code block boundary retention: high weight
- paragraph boundary retention: medium weight
- list retention: medium weight

The metric should produce a weighted structure retention score per article and corpus average.

### Invalid Content Ratio

Goal:

- measure how much noise still survives in final cleaned output

Noise labels should include at least:

- author info
- category column info
- view / like / comment / collect / share metrics
- copyright statement
- sharing prompts
- recommended reading
- directory residue
- whitespace noise
- mixed promotional or operational copy

Primary score:

`noise characters retained in final output / total final output characters`

This metric should be computed after cleaning, not before.

### Retrieval Precision

Goal:

- measure whether better cleaned content improves actual retrieval performance

This requires a retrieval benchmark dataset, not just cleaner output analysis.

Recommended approach:

- select a benchmark subset from the `100+` sample pool
- associate each benchmark query with expected article or chunk targets

Primary scores:

- top1 hit rate
- top3 hit rate
- top5 hit rate

This is the cleanest way to quantify whether cleaning improvements translate into retrieval value.

## Evaluation Outputs

### Batch Summary Report

Must include at least:

- corpus size
- source mix:
  - reused articles
  - newly fetched articles
- average code integrity
- average structure retention
- average invalid content ratio
- retrieval precision:
  - top1
  - top3
  - top5
- baseline vs optimized deltas

This report should make it easy to answer whether the optimization materially helped.

### Per-Article Detailed Report

Each article report should include:

- title
- source URL
- baseline cleaning summary
- optimized cleaning summary
- removed block list
- retained structure block list
- code integrity observations
- structure retention observations
- invalid content observations
- retrieval benchmark notes if applicable

Recommended output formats:

- `json` for machine aggregation
- `markdown` for human inspection

## Cleaning Pipeline Design

### Baseline

Keep the current path intact and measurable:

- current reader extraction
- current line-level noise filtering
- current token splitter

This is needed for comparison, even if it is known to be weak.

### Optimized Pipeline

The optimized path should look like:

`raw HTML -> block extraction -> block classification -> block filtering -> structure-aware text reconstruction -> block-aware chunking`

Important improvements:

- preserve headings as explicit blocks
- preserve code blocks as explicit blocks
- keep paragraph and list boundaries
- classify removable noise before collapsing text
- avoid whitespace normalization that destroys indentation inside code

## Chunking Strategy

The splitter must become block-aware.

Desired behavior:

- do not split inside code blocks unless size constraints make it unavoidable
- avoid splitting heading from its immediately following explanatory content
- keep lists as coherent groups where possible
- apply overlap at safe semantic boundaries rather than blind character windows

This is necessary to make both code integrity and structure retention scores improve in a meaningful way.

## Rollout Order

### Phase 1: Evaluation Harness

Build:

- sample set builder
- baseline runner
- metrics engine
- report generator

Output:

- real baseline numbers for the current implementation

### Phase 2: Structured Cleaner V2

Build:

- block extractor
- block classifier
- block-aware filtering
- block-aware chunking

Output:

- optimized cleaned corpus

### Phase 3: Comparative Evaluation

Run:

- baseline
- optimized

On:

- the same sample manifest

Output:

- quantitative deltas
- per-article explanation reports

## Implementation Boundary

This iteration may introduce:

- new cleaner model classes
- block type definitions
- evaluation scripts or CLI commands
- optional intermediate metadata structures

This iteration should avoid first-pass schema expansion unless it becomes strictly necessary for evaluation or structured cleaning to function.

The preferred initial approach is:

- run offline
- prove measurable gains
- only then consider persistence model expansion for production ingestion

## Risks and Mitigations

### Risk: Metrics Are Easy to Game

Mitigation:

- define metrics at block level, not only character count level
- pair cleaning metrics with retrieval metrics
- use both aggregate and per-article reports

### Risk: Sample Set Bias

Mitigation:

- mix reused and newly fetched articles
- keep a manifest of article sources
- inspect category spread to avoid single-topic overfitting

### Risk: Retrieval Metric Becomes the Bottleneck

Mitigation:

- start with a benchmark subset rather than requiring full-corpus manual labeling
- use top1/top3/top5 hit rates first
- expand benchmark sophistication only if needed

### Risk: Production Integration Becomes Too Large

Mitigation:

- keep the first pass offline and evidence-driven
- defer schema changes unless measurement shows they are necessary

## Success Criteria

This work is successful only if it can answer the following with measured outputs:

1. What is the current real code integrity score?
2. What is the current real structure retention score?
3. What is the current real invalid content ratio?
4. What is the current real retrieval precision?
5. How much did each improve after optimization?

The final result should replace estimated percentages with measured values.

## Recommended Next Step

After this spec is approved, write an implementation plan that:

1. builds the evaluation harness first
2. establishes a stable `100+` sample manifest
3. measures current baseline values
4. introduces the structured cleaner and block-aware chunker
5. re-runs the same evaluation and publishes comparison reports
