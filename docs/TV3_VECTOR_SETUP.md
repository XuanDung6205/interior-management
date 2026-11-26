# Catalog vector setup

The commerce vector runtime is optional and defaults to `VECTOR_ENABLED=false`.
Disabled startup makes no embedding request. Catalog/chat retain their SQL
fallback when vector retrieval is disabled, unconfigured, unavailable or empty.
The persistent index contains only public product names/descriptions, category
names, active SKU descriptions and material names. It excludes customer,
address, order, payment, refund and stored conversation records.

When semantic retrieval is enabled and configured, the current search question
is sent to Gemini for a transient query embedding. The question embedding is
not stored in `vector_store` or the reindex outbox. The runtime does not attach
customer profiles, addresses, order histories or financial records to this
request. Chat generation has its existing separate provider behavior.

## Prepare the schema

Use MariaDB 12.2 with native `VECTOR(768)` and cosine indexing. The shared
`scripts/tv3_schema.py` registry applies V001–V014 in order: 45 bootstrap tables,
two notification tables and two vector/outbox tables. V014 adds runtime columns,
indexes and a nullable product FK; pre-existing generic vectors remain intact.

Back up an existing database before upgrading; see
[backup/recovery](TV3_BACKUP_RESTORE.md). On an existing isolated demo database:

```powershell
python scripts/tv3_database.py migrate --database furniture_shop_tv3_dev
python scripts/tv3_database.py verify --database furniture_shop_tv3_dev
```

The helper checks exact table names, actual `VECTOR(768)` dimensions, and all
registered V014 catalog/lease/request column types. A pre-existing embedding
with another type or dimension stops migration before additive DDL. The helper
never converts or replaces that generic vector data; resolve compatibility with
the schema owner while retaining its verified backup. A 49-table count alone
does not prove V014 is ready.

## Configure the process

Spring does not automatically load `.env.example`; export variables through the
terminal, IDE or deployment secret mechanism. Defaults are:

```text
VECTOR_ENABLED=false
VECTOR_BATCH_SIZE=5
VECTOR_INTERVAL_SECONDS=60
VECTOR_MAX_ATTEMPTS=8
```

To opt in, supply `GEMINI_API_KEY` privately to the backend process and set
`VECTOR_ENABLED=true`. Restart the process after changing these settings. Do not
put keys in `VITE_*`, source files, SQL, URLs or command arguments. The model is
fixed to `gemini-embedding-001` with exactly 768 finite normalized values. There
is no production provider-host override. Requests use the official Google HTTPS
endpoint, the key in a header, no redirects, bounded payloads and timeouts.

`configured=true` means a nonblank key was supplied; it does not establish that
the credential works or that indexing succeeded. Set
`COMMERCE_JOBS_ENABLED=false` to disable automatic workers during tests or a
controlled manual exercise. This switch does not prohibit authenticated staff
from explicitly running a bounded batch.

## Verify a key and run the packaged demo

`scripts/tv3_vector.py` explicitly loads an access-controlled private config;
its default filename is `.env.gemini.local`, which is ignored by Git. It accepts
only `GEMINI_API_KEY` and the four `VECTOR_*` variables above. Keep the actual
key in that private file or your secret mechanism; never copy it into a command
or a committed example. Use `--config` for a different private path and ensure
that path is also excluded from version control.

```powershell
python scripts/tv3_vector.py check --config .env.gemini.local
python scripts/tv3_vector.py run --config .env.gemini.local --database furniture_shop_tv3_dev --port 8086
```

`check` makes one real embedding request using a fixed public furniture phrase;
it verifies 768 finite, nonzero values and prints only a small verification
summary. It does not persist the response or write to the database. This is
separate from running an outbox batch or completing the browser workflow.

`run` verifies the existing packaged release and starts the local demo with
credentials in the child environment. Build the release and verify/migrate the
isolated schema first; the helper does not seed, reset or upgrade a database.
`DB_PASSWORD` and other database connection settings still come from the
process environment. Add `--sepay-config .env.sepay.local` to explicitly load
the existing private SePay setup. On Windows, `--windows-trust` makes the Java
runtime use the Windows root certificate store when that local trust setup is
needed; certificate validation remains enabled. This option applies to `run`.

## Operate and recover

Open `/admin/operations` as STAFF/ADMIN. Inspect enabled/configured status,
indexed products, queue counts and DEAD events. Enqueue a bounded page (the UI
uses 25 products), then run a batch after enabling/configuring the provider.
Enqueue only persists work; it does not perform provider IO. Repeat pages using
the returned cursor until `hasMore=false`. Automatic bounded discovery also
detects changed or missing catalog entries.

Work is claimed and persisted in short database transactions; the provider
request occurs after the claim transaction finishes. Lease tokens and catalog
fingerprints prevent a stale worker from publishing changed catalog content.
Restarting the application retains queued events. Retry/backoff is bounded by
`VECTOR_MAX_ATTEMPTS`; after resolving the cause, staff may retry a DEAD event
with a recorded reason. Public catalog removal cleans owned vector rows through
the nullable product FK. Generic vector rows without catalog ownership remain.

A local MariaDB 12.2 probe reproduced a primary-key tombstone after an FK
cascade removed a vector and the exact same product ID/vector UUID pair was
reused. Application cleanup uses explicit vector-row DELETE, and cascade tests
use fresh IDs; these measures do not establish that the native server issue is
fixed. Preserve assigned product identities in production: do not reset or
reuse product IDs when recreating catalog data.

Vector retrieval returns product IDs. The app reads active status, current
prices and live stock again; vector similarity cannot authorize a purchase,
payment or stock reservation. See the [API contract](api/TV3_VECTOR_RUNTIME_CONTRACT.md)
for exact operations and error codes.

## Verification scope

Deterministic HTTP fixtures exercise transport behavior without a real Gemini
credential. The native MariaDB fixture backup/restore independently verifies
768 values, cosine distance zero and unchanged binary/content/metadata digests;
see the dated evidence in [backup/recovery](TV3_BACKUP_RESTORE.md).
On 2026-11-26, the private-config helper also received a real HTTP 200 response
from `gemini-embedding-001` and verified exactly 768 valid embedding values with
the supplied credential. No key or provider response was saved in the evidence.
The newly packaged application was then exercised against its own 49-table
demo schema: Gemini indexed three public catalog products, recommendations
used the semantic retrieval path, and chat returned a real model response.
Every source price and stock value was checked against the current catalog.
No keys, product identifiers or response bodies are included in this evidence.
The complete packaged Chrome regression passed 12/12 cases in about 1.3 minutes,
including the COD and SePay partial-refund/dashboard/review flows. Separate live
vector operations and Gemini chat checks passed on desktop and mobile, with no
page errors or horizontal overflow. Chat used `gemini-3.5-flash-lite` and its
three source products matched current catalog prices and stock. Missing keys
and offline operation remain supported through SQL fallback. Synthetic vectors
and fixture successes must still be distinguished from live AI indexing.
