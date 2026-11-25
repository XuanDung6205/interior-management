# TV3 SePay Test mode contract

API version 1.8.0 in `tv3-commerce.yaml`. This integrates SePay Webhooks Test mode
and API v2. Hosted gateway Merchant ID/Secret Key and Production are separate.

## Checkout and history

- Quote/place `paymentMethod: SEPAY`; retry/new attempt `method: SEPAY` at existing
  POST `/api/orders/{id}/payments`, with session/CSRF/Idempotency-Key unchanged.
- Persistence uses method BANK_TRANSFER and provider SEPAY_SANDBOX. Existing COD
  and MANUAL_BANK keep their original behavior. Staff cannot manually confirm a
  SePay payment. The gross balance and pending-refund rules still govern creation.
- The server calculates whole-VND amount from 1,000 to 499,000,000, snapshots payee
  and generates unique `DH` + eight digits from payment ID, not order ID. The QR
  content is `SEVQR DHxxxxxxxx` for this VietinBank test account. Codes never get
  assigned by the browser; allocator exhaustion rejects the transaction.
- GET `/api/payments/sepay/config` returns enabled/environment/canReconcile and automation
  {enabled,ttlSeconds,intervalSeconds}. No token or secret is returned.
- Payment summaries add `sepay` configuration and optional item `sepay` with code,
  bank/account/holder/content, canReconcile, and qrUrl only while pending/payable.
  Customers never receive secrets, raw events or staff notes. Staff summaries add
  at most 25 linked sepayEvents. Existing consumers may ignore these additive fields.

## Webhook

POST `/api/payments/sepay/webhook`, JSON only, <=65,536 raw bytes. This exact POST
route alone bypasses session CSRF. HMAC remains mandatory: verify
`X-SePay-Signature: sha256=<hex>` over UTF-8 timestamp + `.` + original body bytes,
with `X-SePay-Timestamp` Unix seconds within +/-300 seconds. Constant-time compare;
no reserialization before authentication. Invalid signature ->401; malformed ->400.

Incoming id/gateway/accountNumber/subAccount/code/content/transferType/
transferAmount/referenceCode/transactionDate follow SePay's documented payload.
Store only normalized business fields, excluding sender descriptions and balances.
Incoming direction, exact amount, bank/account, empty VA, and one exact `DH` code
are mandatory. The complete bounded snapshot phrase `SEVQR DHxxxxxxxx` must occur
once; bank-added surrounding text is allowed. A missing bank reference is retained
for review and cannot automatically collect. No partial/overpayment automatically settles an
attempt. Unknown code is IGNORED; mismatches/additional transfers are FAILED for
staff review. Both are acknowledged 200 with only `{success:true}` so retries do
not endlessly reapply unusable transfers. FAILED means automatic matching failed,
not a statement that bank funds were returned or a payment never occurred.

Per-source event identities (`webhook:<id>`, `api:<uuid>`) deduplicate retries.
Different IDs from the same source always require review on an already-paid
attempt, even when the bank reference repeats. Cross-source duplicates require
the same bank reference AND identical normalized facts including timestamp. Reused event ID with changed business
facts ->409 without altering old evidence. Duplicate same event returns original
outcome. Payment/refund/coupon/audit/outbox/event changes commit together. Runtime
failures roll back and leave delivery retryable. Order/owner locks serialize cancel
and competing collections. Cancelled-order late exact transfer queues one pending
refund request, never restores stock/order/coupon. Payment alone never marks an
undelivered order COMPLETED; existing guarded staff completion remains available.

## API reconciliation and review

STAFF/ADMIN POST `/api/admin/payments/{id}/sepay/reconcile`, CSRF required:
`{"transactionId":"a1b2c3d4-e5f6-7890-abcd-ef1234567890"}`. It uses server-only
Bearer token and GET `https://userapi-sandbox.sepay.vn/v2/transactions/{uuid}`.
UUID is NOT numeric webhook ID or referenceCode. Bank reference is what joins
the two representations, never an assumed equality of provider IDs.

API call occurs outside DB locks; fixed sandbox host, no redirects, 3-second
connect and 5-second whole-response limit, <=65,536-byte response. HTTP errors,
invalid envelopes/UUID/facts and timeout cannot imply paid status. Failure is
409 SEPAY_API_UNAVAILABLE; definitive provider404 is SEPAY_TRANSACTION_NOT_FOUND
and lets staff correct the UUID; authentication/CSRF/ownership keep existing semantics.
Response is fresh staff summary plus `reconciliation` processing result. Inspect
processingStatus, not just HTTP200. No API-supplied facts apply to another selected
payment. Replaying UUID is safe after lost response.

GET `/api/admin/payments/sepay/events?status=FAILED&page=0`, staff only. Allowed
statuses FAILED/IGNORED/PROCESSED, fixed 25/page, page0..10000. Unknown codes remain
visible here even without payment link. Staff UI exposes this review list and
UUID reconciliation. `reviewStatus=ALL|OPEN|REVIEWED` additionally filters the
review annotation; ALL is default. Items expose reviewStatus, reviewNote,
reviewedAt and numeric reviewedBy, for staff only. POST
`/api/admin/payments/sepay/events/{id}/review` accepts
`{"note":"Checked against the Sandbox transaction","expectedReviewStatus":"OPEN"}`.
A trimmed 1..1000-character note is mandatory. Only FAILED/IGNORED events may be
reviewed. Same actor/note replay keeps the original review; conflicting content
returns409. The annotation is immutable and never alters original event facts,
processing status, payment or refund money. There is no arbitrary "mark paid" endpoint.

POST `/api/admin/payments/{id}/sepay/reconcile-code` with `{}` searches by the
persisted DH code, not a browser-provided code, and returns a fresh payment summary
plus `reconciliationSummary:{checked,matched,complete}`. A bounded first page
(per_page100) is complete only when explicit valid pagination says no more pages
and every returned row can be classified. Partial/malformed/ambiguous responses
cannot authorize cancellation. Matching uses the same collection/dedup guards as
UUID lookup and Webhook; a 200 alone is not proof of payment.

## Automation and expiry

GET `/api/admin/payments/sepay/jobs` returns safe configuration and queue counts.
POST `/api/admin/payments/sepay/jobs/run` with `{}` is a staff/CSRF-protected single
bounded batch; results include scanned/reconciled/expired/deferred/failed. It is
not an endpoint to force expiry or backdate a payment. Explicit staff run is
available even when scheduler flags are off, provided SePay and API access are ready.

Default scheduler is every60seconds, batch5, with DB claim leases and retry/backoff;
HTTP occurs outside money locks. New attempts get expiresAt=now+1800seconds,
configurable60..86400. Existing NULL-expiry attempts are not backfilled. COD retains
its independent24-hour confirmation policy. No API token means no verified expiry.

Expiry requires a current complete empty provider lookup plus a locked recheck:
PENDING_CONFIRMATION, no collection/refund or dispatch evidence, exactly one pending
SePay attempt with non-NULL due expiry, and zero linked FAILED/PROCESSED events.
Multi-attempt orders require staff follow-up. Reviewing an event does not remove
this protective evidence guard.
Cancellation releases inventory/coupon and records history/audit together using
the existing order policy. Confirmed/processing/mixed-method orders, unresolved
money events and API uncertainty require follow-up, not automatic cancellation.
Recently cancelled/expired attempts remain eligible for reconciliation within a
configurable72-hour window; late exact funds queue one refund without resurrection.
Successful payments may also be looked up to capture verified transaction/account
UUIDs required for provider refunds.

## Provider refunds and manual fallback

STAFF/ADMIN POST `/api/admin/refunds/{id}/sepay/submit` accepts
`{"acknowledged":true}`. The local request must already be an eligible PENDING
refund against a verified successful SEPAY_SANDBOX payment, with the existing
order/return budget policy satisfied. The server persists an immutable provider
intent and idempotency key before HTTP. It never automatically submits every new
PENDING refund. Server-only Test API access must permit the write operation.

POST `/api/admin/refunds/{id}/sepay/sync` with `{}` resumes the same intent: use
provider refund ID for lookup once known, otherwise resend the same transaction,
amount and X-Idempotency-Key. A lost response, timeout,500/503 or malformed reply
keeps uncertainty and held budget. Never generate a different key to escape it.
The background worker only retries/polls intents already approved by staff.

Summary items add `sepayExecution:{state,refundId,canSubmit,canSync,message}`.
Existing canConfirm/canCancel are false after provider execution claims a refund,
so manual confirmation/cancel cannot race an in-flight provider command. Only
verified succeeded settles locally SUCCEEDED; verified failed/rejected can release
held pending budget. Provider details must agree with the persisted amount, currency and established
refund identity. A supplied original_reference must exactly equal the stored bank
reference. Sandbox can omit it or return null/blank: only then, the response must identify
the exact requested transaction UUID; missing/wrong transaction identity is not
evidence of success. A supplied wrong reference is rejected even when the UUID
matches. Error bodies,
request keys, tokens and raw provider responses never enter the browser.

Existing proof-based manual refunds remain available before a request is claimed
for provider execution, with the same evidence/budgets/idempotency. This application
uses only the fixed Sandbox host. Sandbox support does not establish production
refund capability for a real bank account.

## Primary provider references

- https://developer.sepay.vn/vi/sepay-webhooks/xac-thuc
- https://developer.sepay.vn/vi/sepay-webhooks/test-mode/bat-dau-nhanh
- https://developer.sepay.vn/vi/tien-ich-khac/test-mode/mo-phong-tao-ma-vietqr
- https://developer.sepay.vn/vi/sepay-api/v2/giao-dich/chi-tiet
- https://developer.sepay.vn/vi/tien-ich-khac/test-mode/api-access
- https://developer.sepay.vn/vi/sepay-api/v2/giao-dich/danh-sach
- https://developer.sepay.vn/vi/sepay-api/v2/hoan-tien/hoan-tien-giao-dich
- https://developer.sepay.vn/vi/sepay-api/v2/hoan-tien/chi-tiet

QR PNG được tạo offline bằng ZXing tại endpoint có kiểm tra chủ đơn/quyền nhân viên, `Cache-Control: no-store`. Không gửi tài khoản, số tiền hay nội dung đến dịch vụ ảnh ngoài. Mẫu payload/CRC được đối chiếu ví dụ công khai của VietQR và ảnh được giải mã lại trong kiểm thử.
