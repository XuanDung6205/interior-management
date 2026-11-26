# TV3–TV5 schema, finance and vector integration

User approved the three follow-up tasks. Preserve Spring Boot/React/MariaDB and
all existing order/payment/refund policies. Work on a separate branch in the
current clean checkout; implementation choices below are agent decisions.

## 1. Schema and delivery

- Apply V001–V013 plus additive V014 vector-runtime metadata through a shared
  ordered migration registry. Keep V001/V013 immutable. Latest schema has 49
  InnoDB tables; V014 adds columns/indexes only.
- Database create/migrate, JAR metadata, CI and backup/recovery share the schema
  contract. Verify actual expected table names, not only a count. Retain restore
  support for trusted historical 47-table backup manifests; never overwrite a DB.
- Back up existing user DBs before upgrades. Verify migrations are idempotent and
  new vector content survives a real dump/restore with matching row digests.

## 2. TV3–TV5 business integration

- Paid COD/SePay contributes once to gross collected. PENDING/FAILED payments do
  not. Only SUCCEEDED refunds reduce actual revenue; PENDING refunds continue to
  hold the operational refund budget without pretending money has been returned.
- Invalidate dashboard after committed order/payment/refund/inventory changes.
  Rollbacks leave cache valid. A concurrent old cache loader must not repopulate
  data after invalidation. Keep bounded TTL cache, do not add Redis to finance.
- Pin complete calendar windows and stable payment/refund timestamps in tests;
  refunds in a later reporting window are accounted at their completion time.
- Reviews require the authenticated owner and at least one actually delivered
  unit of the selected order item, with a valid delivered timestamp. A partially
  delivered item is eligible once the customer received a unit. Completed status
  alone, reservation, shipment creation, failed dispatch or another customer's
  order are insufficient.
- Prove the combined flow through real MariaDB and packaged browser tests.

## 3. Commerce vector runtime

- Use Gemini text embeddings `gemini-embedding-001`, exactly 768 finite normalized
  values, and MariaDB native cosine search. Fixed official HTTPS host, API key in
  a header, bounded request/response sizes and timeouts, no redirects, no secrets
  or raw provider errors in responses/logs. Deterministic HTTP fixtures test the
  real transport; a fake vector must never be called a live AI result.
- VECTOR_ENABLED defaults false. No key/offline/provider errors keep catalog/chat
  usable with existing SQL fallback. No outbound call on disabled startup.
- Persist embeddings only for public catalog descriptions, names, categories,
  materials and SKU descriptions. Never persist user/address/chat/order/financial
  embeddings. A semantic search sends the current query text to Gemini to obtain
  a transient query embedding; disclose this and never automatically append user
  profiles, addresses, orders or financial data to that request.
- V014 adds nullable product identity/fingerprint/model fields to vector_store,
  and lease/retry/request metadata to commerce_vector_reindex_outbox. Product FK
  cascade cleans deleted catalog vectors. Existing generic vectors are preserved.
- Persist/claim work in short transactions; release locks before HTTP. Lease-token
  fencing and current catalog fingerprint checks reject stale results. A bounded
  discovery pass detects changed/missing catalog entries; restart cannot lose
  queued tasks. Stable vector IDs, capped attempts, backoff and DEAD/manual retry.
- Search returns product IDs only. Chat/recommendations read active products,
  current prices and live stock again; vectors never authorize a purchase or price.
- Staff+CSRF operations expose status, bounded enqueue/run and DEAD retry. React
  operations page explains disabled/not-configured/queued/success/failure states.

## Shared contracts and ownership

Vector worker owns `commerce/vector/**`, V014, AI retrieval hooks and its tests.
`CatalogVectorSearch.searchProducts(String question,int limit)` returns a bounded
`List<Long>`; returns empty on disabled/unavailable, preserving SQL fallback.
Keep existing FurnitureChatService constructor usable by existing unit tests.

Vector API:
- GET `/api/admin/operations/vector`: `{enabled,configured,model,dimensions,batchSize,
  intervalSeconds,indexedProducts,outbox:{pending,retry,sent,dead,leased},deadEvents}`.
- POST `/vector/reindex` body `{afterId,limit}` (limit1..100, UI25):
  `{queued,skipped,nextAfterId,hasMore}`. No provider IO.
- POST `/vector/run` body `{}`:
  `{scanned,indexed,deleted,retried,failed,skipped}`; controlled not-configured error.
- POST `/vector/events/{id}/retry` body `{reason}` returns fresh status.

Settings: VECTOR_ENABLED=false, VECTOR_BATCH_SIZE=5, VECTOR_INTERVAL_SECONDS=60,
VECTOR_MAX_ATTEMPTS=8; key from GEMINI_API_KEY, model fixed gemini-embedding-001.
Global COMMERCE_JOBS_ENABLED=false disables automatic vector workers in tests.

Schema worker owns Python helpers/tests/CI/docs except this design and plan;
coordinate V014 filename with vector worker. Integration worker owns financial
cache invalidation, review eligibility and integration tests. Root owns React
operations vector controls, final wiring, real backup/migration/release checks.

## Acceptance

RED/GREEN meaningful regressions; all Java/React/operations tests, no DB class
skipped, native vector search fixtures and timeout/dimension/stale-worker cases;
packaged Chrome flow and real backup/restore. Independent review. Source work
does not imply production deployment, real-money payment or a live Gemini call.
