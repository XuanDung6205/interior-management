# TV3 CI, database recovery and demo release implementation plan

> **For agentic workers:** Use superpowers:executing-plans for the release/integration work; superpowers:dispatching-parallel-agents for the independent CI and recovery domains. One fresh whole-branch review at completion.

**Goal:** Deliver the three non-sandbox improvements the user selected: repeatable GitHub CI, verified MariaDB backup/recovery, and a portable React/Spring demo package.

**Architecture:** Keep Java17-compatible commerce, React and MariaDB47-table runtime. CI provisions disposable MariaDB and executes existing suites. Logical backups use installed MariaDB tools with child-environment credentials, checksums and restore only into a newly created test database. A release build packages Vite output in Spring static resources and explicitly forwards existing React routes; API/security behavior remains intact.

**Tech stack:** Existing Maven wrapper/Spring Boot/React/Vite, Python standard library, MariaDB CLI, GitHub Actions. No Docker daemon, bank provider, SMTP or Redis is required on the laptop.

**Spec / authorized scope:** User accepted CI → backup/restore → demo packaging for the five-person,14-day group assignment. Current base99f08e2. This file records the concrete implementation choices within that authorization; no new publishing request is inferred. Prior in-place feature-branch workflow is retained.

## Global constraints
- Preserve main/demo DB records, financial snapshots and inventory; restore never targets furniture_shop or furniture_shop_tv3_dev or any existing database.
- No passwords/tokens/SQL dumps committed, exposed in logs/argv or uploaded as CI artifacts. Backups contain private business data and remain ignored/local.
- MariaDB snapshot includes all47InnoDB tables. No concurrent DDL during backup. Verify checksum before restore; malformed manifests and schema-changing dump directives fail closed.
- CI uses read-only repository permissions and PR events, never pull_request_target; no live Gemini/provider credentials. Jobs disabled for test fixtures.
- JAR serves existing React routes and assets on same origin. Unknown /api paths must not return index.html; route forwarding cannot consume static files or change auth/CSRF rules.
- Tests appropriate to each change; actual restore and packaged HTTP/Chrome smoke against disposable databases. Do not claim hosted CI execution without a recorded GitHub run.

## Review focus
- Restore path/checksum/directive tampering, existing targets and source/target confusion must fail before executing arbitrary SQL.
- Interrupted dump/build must not publish a seemingly valid artifact or mix stale generated assets into a new release.
- Packaged deep links, missing assets and unknown API requests must retain expected behavior.
- Linux CI must genuinely run conditional commerce integrations rather than silently skip them; DB setup and Java/Node versions must match the project.
- Credentials, SQL data, cookies and local environment must stay out of release archives, logs and Actions artifacts.

## Tasks
- [x] CI domain: read-only PR/main workflow, matching MariaDB12.2 service/client, schema provisioning, Java/React/build, verified recovery and packaged Chrome; no-skip gate for15database integration classes. Actionlint/YAML/Bash checks pass. Hosted runs are tracked in the PR, not inferred from local checks.
- [x] Recovery domain: stdlib helper and15guard tests, checksum/schema/rowdigest verification, existing target refusal, private partial output. Actual47table/702row restore passed on a new DB.
- [x] Release domain(root): React-integrated executable JAR, release profile/metadata/checksum, explicit SPA routes and launcher guards. CLI/SPA RED→GREEN, actual build/HTTP/deep links/Chrome passed.
- [x] Integrate local verification: docs, regression/security, actual recovered-DB browser flow and one fresh independent review; important launcher finding fixed RED→GREEN. Save reviewed code on feature branch. Publication lifecycle/checks/merge result are tracked in the GitHub PR after the explicit request below.

## Ledger
- Docker CLI exists but daemon is not running; native executable JAR is the supported demo deliverable, not unverified Docker scaffolding.
- Existing seed/create scripts accept explicit MariaDB CLI paths; installed MariaDB12.2 clients and Corretto21 are available locally. Java source target remains17.
- Existing tests gate commerce integrations on TV3_TEST_DB_URL with prefix furniture_shop_test_tv3_policy_; CI must provision this exact family. Live Gemini remains opt-in and skipped.
- Existing React routes use BrowserRouter; packaged deep links require explicit server routing. Existing source resources include legacy assets/templates; release must not expose templates or embed secrets.
- Recovery dialect RED→GREEN: MariaDB12.2 compact dumps include autocommit directives by default, disabled explicitly; lexer must preserve >=/<=/<>/!= operators. First failed restore retained only its newly created target. Fresh backup `backups/tv3-20261120-final` restored all47table schema/count/data hashes and702rows exactly into `furniture_shop_test_restore_release_20261120`.
- Native release build succeeded (`target/tv3-release-build.log`), JAR107927347bytes at `target/releases/nep-nha-demo.jar`; packaged six-HTTP-check smoke passed and Chrome7/7passed29.4s (`target/tv3-release-e2e.log`) against recovered DB, with no Vite serving those pages.
- Full Java regression182tests/181pass/1liveGemini skip (`target/tv3-release-full-java.log`); CIgate verified15database classes. React142/13files passed. Operational helper tests39passed, actionlint passed. Build intentionally packages after separate tests, metadata does not claim it executed tests.
- Full-suite environment correction: old EshopApplicationTests boots the legacy ProductDataInitializer. It now requires explicit local LEGACY_TEST_DB_URL, pins datasource/Hikari to that URL, creates/drops entity schema only in that isolated context and checks the actual DB prefix. Commerce retains47table migration/validate policy. CI refuses missing/skipped legacy or commerce integrations.
- Fresh independent review found P1 inherited Spring/JVM overrides could bypass launcher DB guard. Regression reproduced RED; runner now filters framework/JVM override env, loads only classpath config and pins datasource/Hikari/schema-validation/loopback with nonsecret CLI options.39helper tests GREEN; actual launcher with intentionally invalid inherited Spring/JVM connection/config overrides passed all6HTTPchecks on the selected recovered DB. No destructive production override was used in live verification.
- User explicitly requested this batch be pushed and merged on2026-11-20. Proceed with PR, wait for actual hosted CI, resolve failures, then merge and synchronize main; no further approval needed.
- Final local cleanup: stopped only owned packaged runtimes; verified no remaining connections and dropped exactly four session-created test databases. Main and persistent demo retain47tables each. Kept the verified private `backups/tv3-20261120-final` snapshot; removed only two obsolete session backup attempts after checking resolved paths stay directly under workspace/backups. No source business history reset.
- First hosted CI run44668794232 passed operational/MariaDB/Java/no-skip checks, then npm11.19.1 rejected the pre-existing lockfile for two missing optional Rolldown WASM transitive entries. Reproduced locally with that exact npm (RED), regenerated lock metadata only via package-lock-only, and npm ci dry-run passed (GREEN). Existing package versions/integrities and direct dependencies retained; do not replace npm ci with npm install in CI.
