# TV3–TV5 Integration Implementation Plan

> **For agentic workers:** Use executing-plans with independent tasks delegated
> under dispatching-parallel-agents. The user authorized implementation of all
> three tasks; do not stop for routine permission.

**Goal:** Align schema/tools, prove TV3 finance against TV5, activate durable vector runtime.
**Architecture:** Shared migration contract; after-commit cache invalidation; leased
outbox around bounded embedding HTTP and live-catalog retrieval.
**Tech Stack:** Java17/Spring Boot4, React19, Python3, MariaDB12.2 native VECTOR768.
**Spec:** docs/superpowers/specs/2026-11-26-tv3-tv5-integration-design.md

## Global Constraints

No secret output/commits; no destructive DB reset; backup before user DB upgrade;
49 latest tables; V014 additive; no provider HTTP under financial/database locks;
disabled/offline vector runtime preserves SQL catalog and existing SePay behavior.

## Review Focus

- Older 47-table manifests restore safely without accepting arbitrary schema.
- Lease expiry, restart and catalog edits cannot publish a stale embedding.
- No API failure or cache invalidation failure changes committed money.
- A delivered unrelated line/customer cannot authorize a review.
- Disabled/unknown vector state never appears as a completed AI indexing run.

### Task 1: Schema/tool alignment (schema worker)

Files: scripts/tv3_{database,backup,release}.py and shared schema registry;
tests/operations; CI; relevant docs/environment examples.
- [x] Add RED tests for 49-table exact schema and historical47 restore, vector dump safety.
- [x] Implement ordered V013/V014 migrations, metadata and guarded recovery.
- [x] Run Python tests; root verifies actual DB roundtrip and migration idempotence.

### Task 2: Financial/review integration (integration worker)

Files: Tv5Cache, audit/inventory hooks where necessary, ReviewService,
DashboardService and focused Java integration/concurrency tests.
- [x] Write RED tests for COD/SePay collection/refund, reporting boundaries,
  cache rollback/stale-loader, owner/delivered-line review eligibility.
- [x] Implement after-commit invalidation and only fixes demonstrated by tests.
- [x] Run focused DB tests in coordinated window; root runs full regression.

### Task 3: Durable vector runtime (vector worker)

Files: commerce/vector/**, V014__commerce_vector_runtime.sql, AI service hooks,
unit/HTTP/vector DB tests and provider fixture contracts.
- [x] Test strict768 transport, API outage/malformed data, leases/retry/restart,
  changed/deleted catalog, true semantic retrieval with current price/stock.
- [x] Implement fixed-provider opt-in embedding client, discovery/enqueue/worker,
  cosine retrieval and staff operations API defined in spec.
- [x] Run HTTP unit tests, then DB tests in coordinated window.

### Task 4: Operations UI and final acceptance (root)

Files: React vector operations panel/tests, docs contract as needed; no secrets.
- [x] Add RED UI tests for disabled/config missing, bounded enqueue/run, DEAD retry.
- [x] Implement labelled controls and replay-safe loading/error feedback.
- [x] Back up/migrate user DBs, verify real native-vector backup/restore, build JAR,
  run all Java/React/Python and packaged browser integration checks.
- [x] Independent review, correct findings, inspect publishable diff/secret scan.

## Verified acceptance — 2026-11-26

Java: 290 cases, with 289 passing and one optional legacy AI test skipped;
all 20 database integration classes executed. React: 194/194; operations:
60/60; packaged Chrome: 12/12. Separate real Gemini indexing of three public
catalog products, semantic recommendations, real chat generation and
desktop/mobile UI checks passed, including current price and stock checks.
Native 49-table and historical v1/47-table restores were verified; private
credentials and data were excluded from Git. The active-warehouse correction
also passed the full Java suite. Final JAR restart/UI checks passed: the
persisted three-product index, real Gemini chat and operations UI remained
valid after process restart.
