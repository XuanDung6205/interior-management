# Order print and CSV export implementation plan

> **For agentic workers:** Use superpowers:executing-plans for root backend/integration, superpowers:dispatching-parallel-agents for independent frontend and contract domains. One fresh whole-branch review after integration.

**Goal:** Implement the two selected non-sandbox conveniences: owner/staff printable order slips and staff-filtered CSV downloads.

**Architecture:** Shared validated staff search filter feeds list and bounded CSV export. Safe public snapshot projection feeds authenticated print routes. Existing session/CSRF and business services remain authoritative; no new tables or financial writes. React/browser handles printing, file download and responsive rendering.

**Tech:** Existing Java17/Spring/MariaDB/React/Playwright/OpenAPI; no new library/server/provider dependency.

**Spec:** `docs/api/TV3_ORDER_DOCUMENTS_CONTRACT.md`. User selected two proposed tasks; routine implementation choices recorded in contract, proceed within authorization. Base867fc20, preserve in-place feature-branch workflow.

## Global constraints
Preserve all47tables/migrations and business history; no bank/sandbox/SMTP activity. Owner-only public print, staff-only all-order print/export; no internal fields. Strict applied date/status/query filter equivalence, UTC+07day semantics, literalLIKE escaping,5000export cap, finite memory. CSV escaping/formula display defense; no universal spreadsheet guarantee. No-store sensitive payloads, no sensitive caches or raw exported files committed.

## Review focus
Cross-owner/role access; private-field projection and stored XSS; date inclusivity/order/filter divergence; spreadsheet injection/cell breakout; lost auth/HTML mistaken as CSV/stale actor content; print page fragmentation and narrow layouts.

## Tasks
- [x] Backend(root): shared `AdminOrderSearch`, backward-compatible list date extension, safe snapshot projections and boundedCSV; no-store/auth guards. Missing-feature RED5fail/1pass → GREEN6/6; additional CSV-Accept JSON error and SPA deep links reproduced RED, fixed and GREEN13/13 including security checks.
- [x] Frontend: print routes/links, private fresh documents, appliedfilters/download/A4styles; React157/14files, existing navigation unchanged.
- [x] Contract/docs: OpenAPI1.6.0/55operations and guide,3newGETs, exact safe21field print allowlist/date params/CSVformat; Postman syntax lint0issues, refs/operationIDs checked.
- [x] Integrate: fullJava189/188pass/1liveGemini skip and16DBclasses, tooltests39pass; actual JAR/fullChrome8/8, finaldocumentflow1/1after print-onlyCSSfix; fresh independent review no important findings. Real50SKUcheckout/PDF6pages validated. Preserve sourceDBs and clean only task-created test databases; save reviewed feature commit. Follow established delivery preference via PR and merge only after hosted checks succeed.

## Ledger
- CSV view uses phone/recipient/orderfinancial snapshot only, not internal notes/proofs; printing uses browserdialog and safe order projection. Website feature, not standalone spreadsheet artifact.
- Reuse existing chart-free React components/visual language. ui-ux-pro-max targeted search confirmed long-token wrapping and mobile table overflow concerns; print tables must wrap/repeat headers instead of clipping.
- Backend tests use rollback fixtures in `furniture_shop_test_tv3_policy_docs_20261121`; legacy context has own `furniture_shop_test_legacy_docs_20261121`; packaged browser/QA uses freshly seeded `furniture_shop_test_docs_demo_20261121`. No migration or main/demo reset.
- CSV requests Accept:text/csv exposed missing JSON error negotiation. Reproduced before pinning application/json for CommerceException responses; domain400errors now remain readable to download client. Explicit new SPA routes preserve refresh inside executable JAR.
- Browser firstfull8passed46.3s/newdocs4.2s; finalCSS-only correction makes HTML/root canvas white and removes app min-height on print. Rebuilt after stopping Windows JVM holding JAR file lock; final one-flow6.6s and sixHTTPchecks passed. Release docs now require stopping a running Windows JAR before rebuilding; BUILD SUCCESS alone is not publication success.
- PDF QA used50new catalogSKU/stock fixtures only in isolated demo, then real API cart/quote/checkout and owner print; no forged order/financial records. Real order canceled through API after capture. Final PDF6A4pages, all50SKUcodes exactlyonce; rendered pages inspected, repeatedthead and no clipped rows; finalpage backgroundwhite.
- Logs: `target/tv3-documents-{red,errors-red,green,full-java,build,e2e,e2e-final,openapi,tools}.log`; `target/tv3-print-pagination-summary.json`. Frontend screenshots visually reviewed by separate agent. No hosted CI run claimed for this branch.
- Final local React run on finaltree:157tests/14files passed54.43s (`target/tv3-documents-react-final.log`). Stopped owned packaged runtime and verified no live connections before dropping exactly the three session test databases. Main and persistent demo verified47tables each; no source data reset. All PDF/CSV artifacts, temporary QA scripts and credentials remain ignored/local.
- Delivery ruling: retain the user's repeated session preference to push and merge completed project work, including the explicit instruction “làm xong push lên github và merge ... luôn”. No new bank/hosting deployment scope is inferred. Publish this reviewed branch through a PR, wait for actual hosted CI and merge the exact tested head; record its evidence in the PR.
