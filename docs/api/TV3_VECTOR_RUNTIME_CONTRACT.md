# Commerce catalog vector operations

Base path: `/api/admin/operations/vector`. The commerce session must have
STAFF or ADMIN access. All POST requests require the existing commerce CSRF
token; guest/customer access does not authorize indexing. Bodies and results
are JSON. These operations manage catalog retrieval only and never change
orders, prices, stock or financial decisions.

The authoritative [OpenAPI contract](tv3-commerce.yaml) is version 1.9.0 with
71 operations; this addition covers only the four vector operations below.

## Read status

`GET /api/admin/operations/vector` returns:

```json
{
  "enabled": false,
  "configured": false,
  "model": "gemini-embedding-001",
  "dimensions": 768,
  "batchSize": 5,
  "intervalSeconds": 60,
  "indexedProducts": 0,
  "outbox": {"pending": 0, "retry": 0, "sent": 0, "dead": 0, "leased": 0},
  "deadEvents": []
}
```

`indexedProducts` counts vectors with catalog ownership. `leased` is a subset
of pending/retry work with an unexpired lease, rather than an additional queue
state. Up to 20 recent DEAD events expose `id`, `productId`, `attempts`, a safe
`lastError` code and `updatedAt`. Provider response bodies, keys and embedding
content are not returned. `configured` reports key presence, not provider
credential validation or indexing success.

## Enqueue a catalog page

`POST /api/admin/operations/vector/reindex`:

```json
{"afterId": 0, "limit": 25}
```

`afterId` defaults to zero and must be nonnegative. `limit` is required and must
be 1–100. Returns `queued`, `skipped`, `nextAfterId` and `hasMore`. Use the
returned cursor for the next page. Current fingerprints and existing work can
make an entry skipped. Enqueue performs no provider request and is allowed
before a key is installed. The actor is taken from the staff session.

## Run one bounded batch

`POST /api/admin/operations/vector/run`, with `{}` as body, returns
`scanned`, `indexed`, `deleted`, `retried`, `failed` and `skipped`.
The configured batch size bounds claimed work. `retried` counts failed work
scheduled for another attempt and is a subset of `failed`. A successful HTTP
response alone does not imply an indexed product: inspect these counters and
the refreshed status. Provider failure keeps durable retry/dead state.

Disabled or unconfigured execution returns a controlled conflict with
`VECTOR_DISABLED` or `VECTOR_NOT_CONFIGURED`. An enabled runtime whose vector
schema is unavailable returns `VECTOR_SCHEMA_UNAVAILABLE`. Validation, session,
access and CSRF failures use the existing commerce error contract. Provider
errors are represented by bounded codes such as `VECTOR_PROVIDER_TIMEOUT`,
`VECTOR_PROVIDER_UNAVAILABLE` or `VECTOR_RESPONSE_INVALID`; raw provider text
and credentials are suppressed.

## Retry a DEAD event

`POST /api/admin/operations/vector/events/{id}/retry`:

```json
{"reason": "Retry after the provider configuration was corrected"}
```

The trimmed reason must contain 1–1000 characters. Only a DEAD event can be
retried; another state returns `VECTOR_NOT_DEAD`, and an absent event/product
returns the normal not-found response. Retry resets attempts and lease state,
records actor/reason/time and returns a fresh status snapshot. It persists work
without making a provider request.

## Runtime and verification

The model is fixed to `gemini-embedding-001`, exactly 768 finite normalized
values, with native MariaDB cosine search. `CatalogVectorSearch` returns at most
20 product IDs and falls back to SQL on disabled/unconfigured/provider/schema
failure. Current product status, price and stock are read again by the caller.
No vector value authorizes commerce mutations. Persistent embeddings contain
public catalog data only. The current semantic search question is sent to
Gemini for a transient query embedding, without automatically attaching
customer/address/order/payment data; this query embedding is not persisted in
the vector table or outbox.

See [setup](../TV3_VECTOR_SETUP.md) for migration guards, private-config helper
commands and the difference between key presence and a completed indexing run.
The helper's real Gemini HTTP 200/768-value embedding check passed on 2026-11-26.
The new packaged application also indexed three catalog products through Gemini,
used semantic recommendations, and returned a real model chat response; source
prices and stock matched the current catalog. HTTP fixtures, synthetic native
vector recovery and these live packaged API checks are distinct evidence.
The packaged Chrome regression passed 12/12, including the COD/SePay
partial-refund/dashboard/review cases. Separate live vector operations and
Gemini chat checks passed on desktop/mobile with no page errors or horizontal
overflow; `gemini-3.5-flash-lite` returned three current catalog sources.
