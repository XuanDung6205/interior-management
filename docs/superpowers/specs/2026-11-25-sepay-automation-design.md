# SePay automation and settlement follow-up

User approved all remaining tasks and explicitly requested push/merge/branch deletion.
Existing Spring Boot/React/MariaDB design, sandbox-only host and private credentials
remain binding. No further design confirmation is necessary.

## Decisions and boundaries

- New SePay attempts expire after configurable 1,800 seconds (60..86,400). Existing
  attempts with NULL expires_at are not backfilled. COD keeps 24 hours independently.
- Automatic checks run every 60 seconds, batch 5, with conditional DB leases and
  retry/backoff. The same processor handles API results and HMAC callbacks. Never
  hold financial DB locks during HTTP. Pending and recently cancelled attempts
  remain discoverable for late collection; successful payments can discover API
  transaction/bank UUIDs needed for refunds. Fixed sandbox host, bounded bodies,
  whole-request timeout, no redirects, shared provider request pacing.
- Search API v2 by exact DH code, page1/per_page100. Complete means valid explicit
  pagination with no more pages and every returned row classified. Incomplete,
  malformed, unavailable or ambiguous results never authorize cancellation.
- Auto-expire only new due PENDING_CONFIRMATION orders with no collection/refund,
  no shipping/dispatch evidence, exactly one SePay attempt, and current complete empty
  provider check. Recheck under owner→order→coupon→payments locks. Cancel through
  existing inventory/coupon/history transaction. Exact late payment still queues
  one refund, never revives cancelled order/stock. Confirmed/processing orders
  and any mismatched/unknown transfer require staff handling instead of auto-cancel.
  Reviewed FAILED evidence also prevents automatic expiry; a note cannot erase it.
- Event review is separate from money: staff can mark FAILED/IGNORED event reviewed
  with immutable note/actor/time. No edit/delete of original facts or arbitrary
  paid flag. Same exact replay succeeds; conflicting review is409.
- Sandbox API refunds require staff submit of an existing eligible PENDING refund,
  then automatic retry/poll of that persisted intent. Reuse existing return/budget
  policy. Persist a unique provider request key before network call; UNKNOWN keeps
  budget held and disables manual confirmation/cancel. Retry same request/key.
  Only verified succeeded means locally SUCCEEDED; definitive failed/rejected may
  release held budget. Existing proof-based manual refunds stay usable for refunds
  never claimed by provider execution. No Production calls or automatic new sends.
  Sandbox may omit original_reference: accept this only with an exact match to the
  immutable requested transaction UUID. A supplied conflicting reference or UUID
  is never ignored; amount, VND and known refund ID remain mandatory.
- Add nullable V011 payment automation/review metadata and V012 refund execution
  metadata. Keep 47 base tables, no reset/DDL-auto-update. Private backups before
  upgrading user databases. Inject clock and HTTP clients for deterministic tests.

## Interfaces

Event GET `/api/admin/payments/sepay/events` retains status/page, adds reviewStatus
ALL(default)/OPEN/REVIEWED and reviewStatus/reviewNote/reviewedAt/reviewedBy fields.
POST `/api/admin/payments/sepay/events/{id}/review` body `{note,expectedReviewStatus:"OPEN"}`.
GET `/api/admin/payments/sepay/jobs` returns config and queue counts.
POST `/api/admin/payments/sepay/jobs/run` triggers one bounded batch (staff+CSRF).
POST `/api/admin/payments/{id}/sepay/reconcile-code` returns updated staff summary
plus reconciliationSummary `{checked,matched,complete}`; retains old UUID route.
Payments summary sepay adds `{automation:{enabled,ttlSeconds,intervalSeconds}}`.
Refund routes POST `/api/admin/refunds/{id}/sepay/submit` body `{acknowledged:true}`
and POST `/api/admin/refunds/{id}/sepay/sync` body `{}` return updated refund summary.
Refund item sepayExecution: `{state,refundId,canSubmit,canSync,message}`; existing
canConfirm/canCancel false after provider execution claims the refund.

## Shared backend integration

SePayTransfer gains optional providerTransactionId/bankAccountId while retaining
the old ten-argument constructor and canonical facts unchanged. Persist verified
UUIDs on payments as sepay_transaction_id/sepay_bank_account_id.
SePayApiClient gains public record ProviderResponse(int status, JsonNode body),
refundRequest(String transactionId,long amount,String key), refundDetails(String
bankAccountId,String refundId). Both return all parsed provider HTTP responses;
network/non-JSON errors throw controlled SEPAY_API_UNAVAILABLE without body/secrets.
SePaySettings gains public canReconcile(), retaining package-private token access.
Root owns this shared transport and V011; refund worker owns V012 and refund files.

## Validation/delivery

TDD for new financial/jobs behavior, isolated MariaDB and real HTTP fixtures;
React forms/pending/error permissions, full regressions and desktop/mobile browser
checks. Verify real Sandbox refund only against own isolated cancelled test order
when API supports the transaction. Independent review, fix material findings,
secret scan, feature PR, wait hosted CI, merge and delete only this feature branch.
