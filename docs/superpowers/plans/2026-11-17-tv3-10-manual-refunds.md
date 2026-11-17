# Manual refunds without sandbox — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans; independent frontend/performance work may use superpowers:dispatching-parallel-agents. Steps use checkbox syntax for tracking.

**Goal:** Deliver the non-gateway portion of TV3-10, extend TV3-12 E2E/handoff and measure independent TV3-11 read performance while sandbox registration is unavailable.

**Architecture:** JDBC finance snapshots share the existing datasource transaction and sorted-user/order-root locks. Staff attests manual refund evidence; reserve pending amounts before confirming. Read eligible persisted TV4 returns, never mutate return logistics or stock. Frontend follows current React/session/CSRF/replay patterns.

**Tech Stack:** Java17 Spring Boot, MariaDB, React/Vite/Vitest/Chrome.

**Spec:** docs/api/TV3_MANUAL_REFUNDS_CONTRACT.md; approved task10 in2026-11-13-tv3-react-commerce.md.

## Global constraints

No bank calls/secrets/real transfers, no manual handling of gateway requests, no payment/financial history deletion. Money server checked, verifier and UTC time server owned. Isolated test/demo schemas only for fixtures. V007 additive. Keep pending refunds reserved and successful original collections intact.

## Review focus

Cross-order/return identity, partial concurrent reservations, cancellation/confirmation races, stale proof and request restore on unknown responses, customer privacy and real fulfillment consistency.

## Deliverables

- [x] Root: V007, strict refund policy/service/query/controllers/security; integration RED then GREEN for ownership/CSRF, budget/partial/concurrency, return eligibility, proof/key replay, cancellation, atomic audit rollback and privacy.
- [x] Frontend: queue/order/detail pages, two-step create/confirm/cancel and persistent uncertain requests; RED then GREEN; all routes/nav retained.
- [x] Independent performance harness: isolatedData check, read-only concurrent load/session login, measured latency/errors plus safe EXPLAIN and config/dataset evidence; no invented cache/provider results.
- [x] Integrate: additive main/dev migrations, current OpenAPI/runbook/dictionary/backlog updates, full Maven/React/build/real Chrome E2E, fresh branch review and significant findings resolved; commit/publication follows the user's explicit request.

## Ledger

Base main c7866f7; branch codex/tv3-manual-refunds. Baseline149 Maven(148pass1liveGemini skip),97React/5Chrome. Working tree clean. User explicitly asks to proceed with non-sandbox tasks; routine implementation choices stay within approved10 scope. Existing cancellation already queues full PENDING refund. Ruling: implement unsent-manual cancellation/replacement for partial settlement and consume only received approved TV4 returns; online gateway execution remains blocked on provider access. Ruling: work in the established current checkout on a feature branch, preserving user's ongoing local demo setup; no additional worktree requested.

Implemented13 manual refund integration tests. Initial auth RED401expected/404actual then12GREEN. Fresh independent review found no significant backend defect; minor return display cap on partial collection was tightened to actual gross collection: observed focusedRED1, then final fullMaven162tests/161pass/1liveGemini skip. All money reservations and receipt updates are atomic with audit and do not mutate stock/original success collections. Return fixtures removed after tests so existing suites retain FK-safe cleanup.

V007 applied additively to main and persistent isolated demo;45tables, main financial data preserved. OpenAPI1.4 has39operations; PyYAML parse/Postman syntax lint pass. Runbook/dictionary144TV3attributes updated. No real bank/provider calls or secrets in source.

Frontend initially17RED routes, expanded30refund tests; final127/127 across12files using maxWorkers2, Vite60modules437.60kBJS/119.63kBgzip. Root captured finalstdout target/tv3-refund-frontend-final.log. Final review addedFAILED/PENDINGamountlabelregressions (2REDthen2GREEN), onlySUCCEEDEDlabelledcollected; title-effect assertion now waits for actual effect. Unlimited-worker run had1existing1sasync-list wait timeout under concurrentMaven, preserved separately; final bounded-workerfullrun passes. This runner limit is recorded, not a production performance claim.

Root realChrome full6/6pass (51.1s); newrefundflow23.9s verifies requestreplacement/idempotency/reload/lostresponse and proofreplay/private/publichistory;15refund viewport checks keep10navlinks/nooverflow. All6refund screenshots inspected. This runs real Spring/MariaDB demo transactions with fictitious proofs, not provider sandbox. Additional final targeted refund browser run verifies the readonly payment-label fix. Evidence target/tv3-refund-e2e{,-final}.log.

IndependentTV3-11baseline:160allowlistedGETs/0errors,p95=39.521ms,8EXPLAINplans and11guard/statistics/protocoltests pass. Dataset/timing/machine/scheduler/pool limitations and possibleVitestcontention recorded in docs/performance; no optimization/production-capacity/Redis/provider-failure claims. Expiry/notification/reconciliation jobs and online tasks remain pending, reflected in original backlog. No fullTask10/11/12 completion claim.

Final targeted Chrome refund run after the readonly label fix:1/1pass13.5s, rechecks all15viewport conditions; full6scenario evidence remains in target/tv3-refund-e2e.log. User subsequently explicitly requested push+merge. Main financial dataset remains0orders; V007columns verified in both main/dev. Disposable regression schemas are cleaned after verification, persistent demo history retained.
