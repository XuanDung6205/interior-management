# TV3 local read measurement — 2026-11-17

The independent TV3-11 read benchmark completed 160 requests with zero errors
against the isolated local commerce runtime. Overall client p50 was **16.345 ms**,
p95 **39.521 ms**, maximum **90.437 ms**, and attempted throughput
**42.932 requests/second** over **3.726794 seconds**. This is a short, modest
baseline on existing demo data, not evidence of production capacity or the
backlog's broader job/cache/provider acceptance criteria.

Raw evidence: [HTTP measurement](tv3-read-load-2026-11-17.json) and
[database EXPLAIN/index snapshot](tv3-explain-2026-11-17.json).
Harness and reproduction instructions:
[tests/performance/tv3/README.md](../../tests/performance/tv3/README.md).

## Conditions and dataset

The measured window began at **2026-11-17 16:21:29 UTC** (23:21:29 Asia/Bangkok).
The root agent verified the dev runtime after additive V007 migration and waited
for the refund integration suite to finish. It paused Maven activity and asked
the frontend author to hold E2E, unit tests and builds for this measurement.
The frontend author subsequently reported that a focused Vitest run had already
started before the hold notice; overlap during these requests is possible.
**This was not a confirmed quiet measurement window; contention was uncontrolled.**
Spring devtools, Vite and the IDE also remained active on the same laptop. The
runtime's first HTTP response explicitly reported `application: tv3-commerce`
and `isolatedData: true`; each worker session repeated the isolation check.

The machine reported Windows 11 build 26200, Python 3.14.3, 16 logical CPUs and
`Intel64 Family 6 Model 154 Stepping 3, GenuineIntel`. CPU model naming, physical
RAM, resource saturation and competing operating-system activity were not
instrumented by this harness. The target was `http://127.0.0.1:8080` with the
commerce profile. No scheduling was enabled for expiry/reconciliation jobs.
The app used its Hikari datasource; pool utilization and connection acquisition
times were not exposed or measured, and no pool size is inferred.

The existing isolated dataset contained three furniture SKUs and retained
cancelled E2E orders. API snapshots reported **23 customer orders, 23 admin
orders and 23 payments**, with **zero items/lines in the selected customer cart**.
These API summaries were unchanged after the measurement. The harness did not
create or reseed business data. An empty cart makes this result unrepresentative
of a cart containing multiple SKU/inventory projections.

## Workload and actual results

There were **2 concurrent workers**, each with independent customer and staff
sessions, for **4 authenticated cookie jars**. Each worker executed 20 rounds of
the same four GETs, with a 25 ms think time after each request and a 10-second
timeout. Each endpoint received **40 attempts, 40 successes and zero errors**:

- `/api/cart`: p50 **14.203 ms**, p95 **39.092 ms**, max **49.447 ms**;
  **10.733 attempted requests/second**.
- `/api/orders?page=0&size=10`: p50 **14.812 ms**, p95 **39.069 ms**, max
  **41.761 ms**; **10.733 attempted requests/second**.
- `/api/admin/orders?page=0&size=10`: p50 **15.031 ms**, p95 **37.497 ms**, max
  **90.437 ms**; **10.733 attempted requests/second**.
- `/api/admin/payments?page=0&size=10`: p50 **19.000 ms**, p95 **45.743 ms**,
  max **48.139 ms**; **10.733 attempted requests/second**.

Percentiles use nearest rank over attempted client wall-clock latencies,
including failures had there been any. Latency includes HTTP connection, body
reading and JSON parsing, and excludes think time. Throughput divides each
endpoint's count by the shared measured wall time, which includes think time
and client scheduling. It is not a saturation result. No request was retried.

Two warmup rounds per worker produced **16 excluded GETs**. Runtime checks,
real CSRF/session logins and before/after dataset snapshots were also excluded
from measured request counts and latency. Warmup did not flush any cache;
cache state remained uncontrolled. There is no cold-cache, warm-cache speedup,
Redis hit-rate or provider-failure claim.

## Database plans and existing indexes

The fixed helper captured **8 EXPLAIN plans** from local
`furniture_shop_tv3_dev`, MariaDB **12.2.2**, at **16:22:25 UTC**. Seven table
counts were captured: 2 users, 23 orders, 23 order items, 23 payments, 1 refund,
50 carts and 24 cart items. These are whole-table counts; the selected customer's
active cart in the HTTP workload was empty. Customer ID 2 was used for the
representative customer-order plans; staff ID 1 authenticated the admin reads.
The three-product description came from the root's pre-run dataset inspection,
rather than a products count in this helper.

On this dataset the customer-order page uses `idx_orders_user_time` with an
estimated 23 order rows; its count also uses that index. The cancelled admin
order page uses `idx_orders_status_time`. Order item/payment/shipment correlated
subqueries use existing order-related indexes, including
`fk_order_items_order`, `idx_payments_order_status`/`fk_payments_order` and
`idx_shipments_order_status`; address joins use `uq_order_addresses_type`.
These are optimizer-plan observations, not measured SQL execution times.

The unfiltered admin-order and admin-payment page plans show full scans of
their respective 23-row base tables plus `Using filesort`. The pending-payment
filter uses `idx_payments_expiry` for status and still shows a filesort. These
are concrete cases to revisit with larger data and measured query/pool timings;
they do not establish that an index change improves this workload. Existing
index column order and estimated cardinality are preserved in the raw snapshot.
No indexes, table statistics, cache state or business rows were modified.

Reproduce the fixed database evidence with environment-only `DB_USERNAME` and
`DB_PASSWORD` (plus optional loopback `DB_HOST`/`DB_PORT`):

```powershell
python tests/performance/tv3/explain_read.py `
  --database furniture_shop_tv3_dev --customer-id 2 `
  --output docs/performance/tv3-explain-new-run.json
```

The first attempt failed before issuing SQL because `DB_USERNAME` was absent
from the transient process environment. After setting that variable explicitly,
the helper exited successfully and saved all eight plans. No measurements from
the failed attempt are reported.

## Reproduction and remaining scope

Supply existing isolated customer/staff credentials in the four environment
variables listed in the harness README. The root agent used transient process
environment values; no credential file was created. Reproduce the measured
configuration in another coordinated quiet window:

```powershell
python tests/performance/tv3/read_load.py `
  --base-url http://127.0.0.1:8080 --workers 2 --rounds 20 `
  --warmup-rounds 2 --think-ms 25 --timeout 10 `
  --scheduler-note 'Commerce scheduling not enabled; no expiry/reconciliation jobs running.' `
  --db-pool-note 'Hikari datasource present; pool utilization and connection timings are not instrumented or measured.' `
  --dataset-note 'Persistent isolated TV3 demo database: three furniture SKUs and retained cancelled E2E orders; no production data.' `
  --window-note 'Record a newly coordinated quiet window and actual remaining background processes.' `
  --output docs/performance/tv3-read-load-new-run.json
```

Existing E2E history is not a frozen dataset fixture; reruns must compare the
recorded counts and use the fixed EXPLAIN helper to capture current database
size/index evidence. The script permits no business mutations, arbitrary routes,
external hosts, redirects or system proxies. Its only POST is legitimate login.
Response bodies, credentials, CSRF tokens and cookies are omitted from reports.

The stdlib harness suite passed **11 tests**, including a temporary loopback
HTTP protocol fixture, isolation/URL/database guards, CSRF login and worker
cookie separation, redirect refusal, report redaction and percentile/count
checks. Syntax compilation and `git diff --check` passed. The fixture was run
outside the filesystem/network sandbox because sandboxed loopback connections
were blocked; it did not contact the real application or database.

TV3-11 remains open for coordinated expiry races, durable notifications,
gateway reconciliation, realistic larger/repeatable datasets and sustained
load, database-pool/query runtime telemetry, and infrastructure-backed Redis or
provider failure tests. No index or application behavior change is justified
solely by this short local baseline.
