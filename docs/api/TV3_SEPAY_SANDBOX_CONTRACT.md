# TV3 SePay Test mode contract

API version 1.7.0 in `tv3-commerce.yaml`. This integrates SePay Webhooks Test mode
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
- GET `/api/payments/sepay/config` returns enabled/environment/canReconcile only.
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
staff review. Both are acknowledged 200 with `{success:true,...}` so retries do
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
manual refund, never restores stock/order/coupon. Payment alone never marks an
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
UUID reconciliation. There is no arbitrary client "mark paid" endpoint.

## Refund boundary

Existing evidence-based manual refunds support SUCCEEDED SePay collections,
including pending late-collection refunds, with the same budgets/idempotency and
return proof. This records staff-confirmed refunds; it does not call SePay's
money-transfer/refund APIs or establish production refund capabilities.

## Primary provider references

- https://developer.sepay.vn/vi/sepay-webhooks/xac-thuc
- https://developer.sepay.vn/vi/sepay-webhooks/test-mode/bat-dau-nhanh
- https://developer.sepay.vn/vi/tien-ich-khac/test-mode/mo-phong-tao-ma-vietqr
- https://developer.sepay.vn/vi/sepay-api/v2/giao-dich/chi-tiet
- https://developer.sepay.vn/vi/tien-ich-khac/test-mode/api-access

QR PNG được tạo offline bằng ZXing tại endpoint có kiểm tra chủ đơn/quyền nhân viên, `Cache-Control: no-store`. Không gửi tài khoản, số tiền hay nội dung đến dịch vụ ảnh ngoài. Mẫu payload/CRC được đối chiếu ví dụ công khai của VietQR và ảnh được giải mã lại trong kiểm thử.
