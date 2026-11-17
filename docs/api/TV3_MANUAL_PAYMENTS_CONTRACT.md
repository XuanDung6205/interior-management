# TV3-08 — COD and manually verified bank transfers

Scope: real persisted payment workflow; STAFF/ADMIN attest collection after checking their receipt/statement. This does not call a bank, transfer funds, or integrate a gateway sandbox (TV3-09). Development uses the isolated demo DB and conspicuously fictitious bank instructions. No real bank account is supplied by the user.

A PENDING row whose timestamp has passed still blocks creation until a coordinated writer changes it to an explicit terminal status (TV3-11). Reading an expired timestamp never silently releases stock/coupon or mutates the attempt. Dispatch evaluates gross collected amounts plus live pending COD obligations against the original total; terminal failed bank history does not block a verified replacement. Refunds remain part of the separate net-collection completion policy.

## API / DTO

All endpoints require a current session; mutations require CSRF. Money decimal strings, UTC timestamps.

`GET /api/orders/{id}/payments` owner only returns PaymentSummary. `POST /api/orders/{id}/payments`, body `{method:"COD"|"BANK_TRANSFER"}`, header Idempotency-Key8–64ASCII alphanumeric/_/- returns201 PaymentSummary. Server computes outstanding gross receivable; client cannot choose amount/status/verifier. An existing live PENDING attempt returns409 PAYMENT_PENDING_EXISTS (checkout already created one). New attempt only when prior attempts are terminal; no simultaneous payment instructions. Same key + order/method replays, same key different intent409 IDEMPOTENCY_CONFLICT. Cancelled/completed/zero/fully-collected orders reject new attempts. No automatic expiry job here; explicit EXPIRED/FAILED rows can be retried.

`GET /api/admin/payments?page=0&size=10&status=&method=&query=` STAFF/ADMIN only: `{items,page,size,totalElements,totalPages}`. Literal query up to150 chars matches order number/customer name/username/payment reference, status optional payment enum, method optional COD/BANK_TRANSFER/GATEWAY. Items PaymentView + orderNumber/orderStatus/customer `{id,username,fullName}`. Stable createdAt/id descending.

`GET /api/admin/payments/{id}` returns PaymentSummary for that payment's order plus `selectedPaymentId`. `POST /api/admin/payments/{id}/confirm`, body `{reference,note,acknowledged:true}`, returns same summary. Reference6–150 ASCII letters/digits/._:/-; note optional max1000, internal to staff. Both COD and BANK_TRANSFER require a unique receipt/statement reference. Payment amount is immutable; paidAt is server verification time; verifiedBy comes from current actor. Only manual providers COD/MANUAL_BANK are supported, never GATEWAY confirmation. Reference uniqueness uses existing(provider,provider_transaction_id) constraint.

Same confirmed payment + same normalized reference/note returns200 without rewriting receipt/verifier/time/audit. Different proof409 PAYMENT_ALREADY_CONFIRMED. Payment beyond remaining gross receivable409 PAYMENT_EXCEEDS_BALANCE; reused proof409 PAYMENT_REFERENCE_IN_USE. Such inconsistent/overpaid real receipts require manual finance reconciliation; this endpoint never silently reallocates money or claims funds were returned.

PaymentSummary: `{orderId,orderNumber,orderStatus,currency,totalAmount,collectedAmount,pendingRefundAmount,refundedAmount,netCollectedAmount,remainingAmount,canCreate,createBlockReason,items,bankTransfer}`. remainingAmount=max(total-collected,0) for active orders,0 for cancelled; refunds do not reopen original receivable. New collection on an active order with PENDING/SUCCEEDED refunds is blocked for reconciliation.

PaymentView: `{id,orderId,method,provider,status,amount,currency,reference,paidAt,expiresAt,createdAt,isExpired}`. Staff view additionally `{confirmationNote,verifiedBy:{id,fullName}|null,canConfirm,confirmationMessage,isLateCollection}`. Customer never receives staff verification notes or internal order notes. Expired PENDING rows are flagged by server time and cannot normally confirm; later coordinated expiry belongs to TV3-11. Newly-created attempts have no arbitrary expiry while the coordinated job is absent.

## Cancellation and completion

Cancellation and confirmation serialize using sorted owner/actor users → order root → coupon → payment rows. Confirm before cancel: existing cancellation queues pending refund. Cancel before actual collection is recorded: staff can explicitly record a manual receipt against a CANCELLED/EXPIRED/PENDING payment on the cancelled order (within original uncollected amount), preserving SUCCEEDED collection and adding a durable PENDING refund request with cancel:<paymentId>. Never restore order/coupon/stock or report refunded. Other terminal attempts on active orders cannot be confirmed.

On an active order, confirm only PENDING, unexpired manual attempts, require amount <= original total minus SUCCEEDED collections. Mark coupon APPLIED on full collection under lock, even if shipping has not finished. If order PROCESSING and OrderCompletionPolicy is eligible, call the existing shared lifecycle; never fabricate dispatch/installation. Receipt update, coupon state, refund request if late, audit and optional completion are one transaction. Audit action PAYMENT_CONFIRMED, append through existing OrderAuditPort, UTC, exactly once. No external network under locks.

V006 adds payments.confirmation_note (nullable VARCHAR1000). Existing financial fields/snapshots remain intact. Request creation idempotency compares persisted orderId/method under owner lock, using user-scoped hashed keys; no new idempotency table is needed.

## Bank configuration

BankTransferSettings.forOrder(orderNumber) returns `{mode,configured,isDemo,notice,bankName,accountNumber,accountHolder,transferContent}`. Constructor `(JdbcTemplate,String mode,String bankName,String accountNumber,String accountHolder)` injected through commerce.payments.bank.*. Modes UNCONFIGURED(default), DEMO, MANUAL. DEMO instructions only on furniture_shop_tv3_dev or furniture_shop_test_*; visibly invalid account DEMO-KHONG-CHUYEN-TIEN and notice that it is not a bank sandbox and no real transfer should be made. Reject DEMO on other DBs rather than contaminate real data. MANUAL requires complete public payee information via environment; no merchant keys. transferContent=orderNumber. Never send account information to an external QR service.

MANUAL configuration trims outer whitespace but rejects control characters; account number is6–64ASCII digits (Vietnamese manual payee format only, preserving leading zeros), bank name<=100 and holder<=150. This is format validation, not verification that an account exists. New bank attempts require configured instructions; existing checkout attempts without configuration display a clear unavailable notice. COD remains available. Developer demo deliberately does not use a digit-shaped real account.

## React

Customer route `/orders/:id/payments`: amounts, separate refund information, history, manual bank guidance, refresh, retry-create only if canCreate. Preserve uncertain create key/body per user/order across network/auth errors; only explicit business rejection clears it, not401/403/429. Explain that neither choosing a method nor creating an attempt marks it paid.

Staff routes `/admin/payments`, `/admin/payments/:id`: filters/search/page, chosen receipt, note and two-step attestation before confirmation. Show fixed server amount; no editable amount/verifier/status. Preserve proof on conflict/network error, retry same reference/note after uncertain response. Highlight late collection creates a refund request, not a returned transfer. Link from existing customer/admin order detail; add staff nav/title/login return. Demo banner on all payment screens. Tests cover permissions, owner isolation, replay/conflicting proof, over-collection concurrency, cancel/confirm race and actual delivered-order completion.
