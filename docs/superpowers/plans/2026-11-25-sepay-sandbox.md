# SePay Sandbox Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans. Steps use checkbox syntax.

**Goal:** Complete SePay Test mode QR payment and recovery by API reconciliation.
**Architecture:** Dedicated settings/signature/client/processor services share the
existing order lock and finance tables. React consumes server payment metadata.
**Tech Stack:** Java17/Spring Boot4, React19, MariaDB12.2.
**Spec:** docs/superpowers/specs/2026-11-25-sepay-sandbox-design.md

## Global Constraints

- Sandbox only; server-owned amounts, HMAC exact bytes, no secrets in source.
- 47 tables; additive V010; existing COD/manual contracts remain compatible.
- Fake HTTP providers do not establish real SePay connectivity.

## Review Focus

- Event ID reused with changed facts: no silent acceptance or duplicate collection.
- Callback/cancel race: one collection/refund, no order resurrection.
- Wrong sum/bank/account/code and duplicate payments: review event, not paid order.
- API error/timeout/redirect or inconsistent facts: no unauthorized financial update.
- Hidden tab, navigation, expiration and network error: stop polls, preserve requests.

### Task 1: Authenticated SePay collection and QR

**Files:** commerce/payment/SePay*.java, CheckoutService/CheckoutQuoteService,
PaymentService/PaymentQueryService, security configuration, V010 and DB helper.
**Interfaces:** checkout `paymentMethod=SEPAY`; payment create `method=SEPAY`;
summary `sepay` configuration and item `sepay` metadata; webhook POST raw JSON.
- [x] Write SePayIntegrationTest using documented payloads and isolated MariaDB.
- [x] Run failing tests: SePay routes/checkout unavailable before implementation.
- [x] Implement settings, unique codes, snapshots, HMAC verifier, event processor.
- [x] Run SePay and existing Java suites, including concurrency and rollback cases.

### Task 2: Reconciliation HTTP boundary

**Files:** SePayApiClient, SePayReconciliationService/Controller and tests.
**Interfaces:** staff POST `/api/admin/payments/{id}/sepay/reconcile` with
`transactionId`; fixed sandbox API GET transaction resource; same event processor.
- [x] Verify documented API transaction schema and author local controlled fixtures.
- [x] Write failing tests: valid lookup, unavailable/malformed/redirect responses.
- [x] Implement bounded client, normalize facts, staff/CSRF/ownership checks.
- [x] Run HTTP client tests and DB tests proving rollback/no duplicate money.

### Task 3: React checkout, QR/status and staff reconciliation

**Files:** checkout and payment components/API/tests/styles.
**Interfaces:** consume Task1 summary metadata, Task2 reconciliation POST.
- [x] Write failing React tests for QR, polling cleanup, no manual SePay confirmation,
  request recovery, disabled configuration and reconciliation failure/success.
- [x] Implement accessible pending/paid/test mode UI and bounded visible polling.
- [x] Run full React tests and production build.

### Task 4: Reproducible setup, contract, delivery checks

**Files:** .env.example, docs/TV3_SEPAY_SANDBOX.md, docs/api contract/OpenAPI,
local secret helper, release metadata and CI migration references.
**Interfaces:** export env before run, CLI forward to exact webhook endpoint.
- [x] Add private setup flow with no secret stdout/argv/source and runnable examples.
- [ ] Run Java full suite on isolated MariaDB, Python helper suite, React/build,
  representative HTTP/browser smoke, and review changed files for secrets.
- [ ] Independent whole-branch review; fix material findings with regression tests.
- [ ] Commit, PR and hosted CI; merge only when checks pass, remove feature branch.
