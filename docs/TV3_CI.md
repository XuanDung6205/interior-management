# TV3 commerce continuous integration

The workflow is `.github/workflows/commerce-ci.yml`. It runs on pull requests,
pushes to `main`, and manual dispatch. It uses a disposable MariaDB 12.2 service
on Ubuntu 24.04, Temurin Java 21 (the project's Java source target stays 17),
and Node 24, matching `frontend/package.json`.

The job performs these checks in order:

1. Run the Python operational helper tests, including recovery/release guards.
2. Create `furniture_shop_test_tv3_policy_ci` with V001-V009 and require 47
   InnoDB tables. Run the complete Maven test suite against it.
3. Run `scripts/ci_verify_tests.py`. Every Java class gated by
   `TV3_TEST_DB_URL` or `LEGACY_TEST_DB_URL` must appear in Surefire results and
   have no skipped tests (currently 14 commerce classes and one legacy class).
   The `tv3_policy_` database prefix also enables the order completion policy
   integration tests. Live Gemini tests stay explicitly disabled; the only
   Gemini key is an offline placeholder needed to instantiate the legacy
   Spring context. No real provider key is supplied and no live call is tested.
4. Install frontend dependencies with `npm ci`, run the React tests, and build
   the portable demo through `scripts/tv3_release.py build`.
5. Create and seed a separate demo database, back it up, and restore it into the
   new `furniture_shop_test_restore_ci` database. Recovery verifies the schemas,
   row counts and row digests of all 47 tables.
6. Start the verified JAR on port 8080 against the recovered database. Check
   runtime isolation, `/cart`, a direct `/orders/123` request, the bundled
   JavaScript, and missing asset/API responses. Then run the complete existing
   Playwright suite against the JAR, using installed Chromium. Vite is not a
   server in this test.

Commerce scheduled workers are disabled so they cannot alter test fixtures.
The legacy `EshopApplicationTests` context uses a separate
`furniture_shop_test_legacy_ci` database through `LEGACY_TEST_DB_URL`. The test
requires this isolated database prefix, validates the URL and sets Hibernate
`create-drop` only inside that legacy test context. CI creates the initially
empty database; Hibernate creates and drops its legacy tables. Vector schema
initialization stays disabled. `DB_URL` also points to the legacy database as a
defensive fallback. Commerce integrations use `TV3_TEST_DB_URL` and never share
the legacy schema; the packaged runner overrides `DB_URL` with its explicit
recovered database. Do not set a global `SPRING_JPA_HIBERNATE_DDL_AUTO` override:
that could drop commerce tables. The report gate scans all Java test source
files, including class names ending in `Tests`.
The CI database account is root only inside the disposable service: integration
tests inspect transaction/connection metadata and create failure-injection
triggers. No production database is accessed. MariaDB CLI wrappers execute the
service's own 12.2 client and dump binaries, avoiding a mismatch with the Ubuntu
client version. Passwords reach clients only through child environments.

Repository permissions are read-only (`contents: read`); checkout does not
persist credentials. The workflow uses `pull_request`, with no
`pull_request_target` event and no deployment step. New runs cancel obsolete
runs for the same PR/ref, and the job and long steps have time limits. External
actions are pinned to full commit hashes; update the hash and version comment
together after reviewing a new release.

Database/demo passwords are computed for each disposable run and masked before
test commands execute. The session signing key is generated for that run. These
are test-only credentials; the workflow does not need repository secrets. Never
replace them with credentials from a real environment.

On success, a seven-day artifact contains only the demo JAR, its SHA256 file,
`release.json`, and numeric/boolean summaries from the two CI helpers. Surefire
XML, application logs, SQL dumps, backup manifests, browser screenshots,
Playwright traces, cookies, `.env` files and raw response bodies are excluded.
The backup and application log remain in the disposable runner's temporary
directory. The app is stopped even if a later step fails; GitHub removes the
service at job completion.

To reproduce the key gates on a local disposable database, set `DB_PASSWORD`,
`DB_USERNAME` and `TV3_TEST_DB_URL` in the process environment. The URL must point
to `furniture_shop_test_tv3_policy_*` with V001-V009 already applied. A complete
Maven run also needs `LEGACY_TEST_DB_URL` and `DB_URL` pointing to a separate
initially empty `furniture_shop_test_legacy_*` database, a temporary Base64
`JWT_SECRET` encoding at least 32 bytes, and
`GEMINI_API_KEY=offline-test-placeholder` for legacy context initialization.
Do not use a live provider key. Then run:

```text
python -m unittest discover -s tests/operations -p "test_*.py" -v
./mvnw --batch-mode --no-transfer-progress test
python scripts/ci_verify_tests.py
```

Use `mvnw.cmd` on Windows. The report gate must run immediately after a fresh
complete test run; stale reports are not verification. See
[TV3_RELEASE.md](TV3_RELEASE.md) for build/run commands and
[TV3_BACKUP_RESTORE.md](TV3_BACKUP_RESTORE.md) for private backup/recovery commands.
For an already running packaged app on a disposable database, run:

```text
python scripts/ci_packaged_smoke.py --base-url http://127.0.0.1:8080
```

The smoke helper only accepts a loopback HTTP origin, waits up to 120 seconds,
and writes a sanitized summary. Existing browser tests accept
`TV3_E2E_BASE_URL=http://127.0.0.1:8080` and `TV3_E2E_CHANNEL=chromium`; supply
`TV3_DEMO_PASSWORD` through the environment to enable authenticated scenarios.

Local workflow validation used actionlint 1.7.12 (official release checksum
verified), YAML parsing and Bash syntax checks. CI helper unit tests also ran
locally. These checks do not constitute a hosted GitHub Actions run: record a
successful run URL after the branch is pushed and the workflow actually runs.
The local Docker daemon was unavailable during implementation.

The pinned action interfaces were checked against their official release
definitions: [checkout v6.0.2](https://github.com/actions/checkout/releases/tag/v6.0.2),
[setup-java v5.2.0](https://github.com/actions/setup-java/releases/tag/v5.2.0),
[setup-node v6.1.0](https://github.com/actions/setup-node/releases/tag/v6.1.0), and
[upload-artifact v6.0.0](https://github.com/actions/upload-artifact/releases/tag/v6.0.0).
The service configuration follows [GitHub's service container documentation](https://docs.github.com/en/actions/tutorials/use-containerized-services/use-docker-service-containers)
and [MariaDB's healthcheck script](https://github.com/MariaDB/mariadb-docker/blob/master/healthcheck.sh).
