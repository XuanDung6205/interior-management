# SePay automation Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans with independent work
> delegated under dispatching-parallel-agents. No questions between approved tasks.

**Goal:** Complete event review, safe expiry/reconciliation, Sandbox refunds and handoff.
**Architecture:** Shared bounded API transport and transactional finance processor;
separate short-lived intent/lease transactions around provider calls; React staff UI.
**Tech Stack:** Java17/Spring Boot4, React19, MariaDB12.2.
**Spec:** docs/superpowers/specs/2026-11-25-sepay-automation-design.md

## Global Constraints

- Sandbox host only, no credential output; no HTTP while holding money locks.
- V011/V012 additive,47tables, same request/key after uncertain network outcome.
- No automatic cancellation after ambiguous provider result or any money evidence.

## Review Focus

- Callback/API/send/cancel races cannot double collect/refund or restore stock.
- Expiry requires current complete empty query and all locked business predicates.
- Provider failure/timeout/malformed reply leaves recoverable held intent.
- Review annotation never changes original evidence or money.
- UI provider-in-flight states prohibit manual fallback; jobs obey lease/backoff.

### Task1: Automatic reconciliation, expiry and event review (root)

Files: SePayApiClient/Transfer/EventService, SePayAutomation*, SePayEventReview*,
V011, SePayPayments, CommerceJobsConfiguration, DB helper and integration tests.
Interfaces: shared API/record and staff routes from spec.
- [ ] Write RED HTTP and DB tests for exact-code lookup, ambiguity, API outage,
  due expiry/recheck/cancel race, late funds, idempotent event review and lease.
- [ ] Implement shared transport/pacing, metadata, worker and staff endpoints.
- [ ] Run focused and existing financial tests, then complete Java suite.

### Task2: Recoverable provider refund execution (refund worker)

Files: SePayRefund*.java, RefundService/QueryService, V012 and own tests.
Consumes: SePayApiClient.ProviderResponse/refundRequest/refundDetails and payment UUIDs.
Produces: submit/sync endpoints and sepayExecution summary as spec.
- [ ] Write failing tests for persisted intent, verified response, uncertain retry,
  partial budget, blocked manual resolution and terminal state validation.
- [ ] Implement submit/retry/poll lease and immutable request identity, no live calls.
- [ ] Run isolated/refund HTTP tests with owned fixtures; coordinate DB access.

### Task3: React staff workflows (frontend worker)

Files: payment/refund components, API clients, UI tests and e2e when useful.
Consumes: spec response fields/routes; no backend file edits.
- [ ] Write failing UI tests for localized reasons, review note/replay/conflict,
  code lookup, jobs, expiry notice and provider refund pending/unknown states.
- [ ] Implement forms with accessible labels and safe retained requests.
- [ ] Run own tests and full React/build; coordinate dependency operations.

### Task4: Documentation/contract and delivery (docs worker/root)

Files: TV3 guide/handoff/task allocation/contracts/OpenAPI, .env.example and helper.
Consumes: actual implemented interfaces and verified evidence from owners.
- [ ] Replace stale VNPAY/OpenAPI/schema claims with current integration boundaries.
- [ ] Update run/demo scenarios, new configuration and secret-safe helper keys.
- [ ] Root runs full Java/MariaDB, React, operational, packaged browser and live
  Sandbox checks; backs up/upgrades user DBs and scans publishable artifacts.
- [ ] Independent review; commit/push/hosted CI; merge main and delete feature branch.
