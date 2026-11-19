# Non-sandbox operations and handoff implementation plan

> **For agentic workers:** use superpowers:executing-plans; independent domains use superpowers:dispatching-parallel-agents. One fresh whole-branch review at completion.

**Goal:** Finish all user-requested non-sandbox TV3-11/12 work:24h safeCODexpiry, durable web notification/retry, measured read performance with real pool status, actual TV4 delivery/installation/returns/refund integration, and reproducible handoff.

**Architecture:** Existing order-root lock protocol and database transaction shared with stock/coupon/financial records. Durable database outbox feeds owner-only inbox; scheduling is bounded/retryable, no external banking/SMTP/Redis dependency. Aftercare uses existing TV4 tables and physical movement evidence; React consumes same secure session/CSRF contracts.

**Tech:** Java17 Spring Boot/MariaDB/React/Vite/Playwright/Pythonlocalharness; no new external infrastructure.

**Spec:** docs/api/TV3_OPERATIONS_HANDOFF_CONTRACT.md (user selected24h COD deadline).

## Global constraints
No backfill/autoexpiry for old orders, BANK_TRANSFER/gateway/paid/dispatched orders or free orders. No fake staff actor for system audit. Atomic outbox writes, durable retries and idempotent localdelivery. No arbitrary SQL/data reset on main/persistentdemo. UTC timestamps; money/stock/permission uncached; no fake bank/refund/fulfillment evidence. Existingfinancial history preserved.

## Review focus
Expiry-vs-money/dispatch/manualcancel races; concurrent workers/poison event retry; private-note leaks; returned quantities/repeated restock/multiwarehouse lock ordering; realistic measured load/data preservation/secret-free handoff.

## Tasks
- [x] Root:V008 schema/helpers; safe expiry+systemaudit+transactional outbox/inbox+job operations+realHikarimetric endpoints. RED->GREEN integration including races/rollback/retries/permissions/privacy.
- [x] Aftercare backend agent:newpackage/tests only; actual delivery installation/return/reception/restock and shared completion/refund evidence.
- [x] Frontend agent: notifications/operations/aftercare forms, exact uncertainty retry, routes/links, componentTDD/build +realE2E.
- [x] Performance/handoff agent: guarded larger disposable fixture/measurements/EXPLAIN/pool evidence, operations handbook; no production mutations.
- [x] Integrate: migrationmain/dev additive, OpenAPI/backlog/runbook/dictionary, fullschema/Java/React/browser/performance checks, freshreview/fixes. Save on local branch; publish only if requested.

## Ledger
Base278aeca; mainclean. Branchcodex/tv3-operations-handoff. Priorfull162Java(161pass1skip),127React,6Chrome. Userexplicitlyauthorizes all remaining non-sandbox tasks; chooses24h CODexpiry. Ruling: web inbox delivers fully without SMTP/account secrets; provider reconciliation remains deferred. Ruling: extend onlymissingaftercarebridge needed for promisedTV4E2E using existingtables, not a second logistics system. Ruling: retain current checkout/featurebranch per established userworkflow, no extra worktree consent needed.

## Final evidence — 2026-11-19

- Additive V008/V009 applied to main and persistent demo:47tables; no legacy deadline backfill, financial rewrites or inventory reset. Seed inserts missing inventory only; Modena assembly_required=true, Oslo=false. Existing snapshots/history retained.
- `target/tv3-ops-full-final.log`:180Java tests,179pass/1liveGemini skip, BUILD SUCCESS. New operations11/aftercare6 plus scheduler-default regression1. Full suite rerun after scheduler fix.
- Fresh independent review found P2: cached Spring test contexts could run default-enabled jobs against unrelated fixtures. Reproduced default=true RED; fixed Surefire property plus explicit commerce test overrides, GREEN without environment override. Logs `target/tv3-ops-scheduling-{red,green}.log`.
- React142/13files and production build63modules passed (`target/ops-frontend-{verification,build-final}.log`). Final Chrome7/7passed53.5s, new end-to-end aftercare10.3s (`target/tv3-ops-e2e-final.log`). First full run exposed Oslo fixture assumption; restored Oslo=false and use Modena for actual assembly-required flow. No product rule relaxed. Actual desktop/mobile screenshots visually reviewed: no blocking clipping/overflow.
- Schema47tables/22behavior checks passed and fixtures rolled back. OpenAPI1.5.0/52operations passed Postman syntax lint. Harness15tests passed.
- Performance isolated fixture1000orders/3000lines/1003variants;1600GET/run before/after,3200success/0errors. Measured p95 worsened40.150→43.508ms; no HTTP speedup claim. Three indexes improve documented first-page plans; deep offset still scans. Actual pool samples peak3active,0awaiting. Reports/raw data under `docs/performance/`.
- Scope remaining: VNPAY credentials, signed callbacks/provider reconciliation/online refunds and real email delivery; no Redis dependency added. Other members' full modules and production deployment remain separate acceptance work.
- Final schema verification rerun after correcting summary table count:47tables/22checks passed (`target/tv3-ops-schema.log`). Stopped only owned benchmark runtime8081, dropped exactly the three session-created disposable databases; main `furniture_shop` and persistent `furniture_shop_tv3_dev` verified47tables each. Persistent demo browser history/stock retained,8080/5173 demo left running.
