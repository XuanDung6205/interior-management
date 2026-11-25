# TV3-10 manual refund contract

This contract covers persisted proof-based manual refunds for COD/MANUAL_BANK and SePay requests not yet claimed by provider execution. SePay online Sandbox execution has a separate [contract](TV3_SEPAY_SANDBOX_CONTRACT.md). A request is not a bank transfer; staff attests an already completed refund after reconciling its receipt. Manual confirm/cancel endpoints make no external banking calls. Existing cancellation-generated PENDING refunds are first-class requests.

## Endpoints and views

All endpoints require STAFF/ADMIN plus CSRF for writes. Money decimal strings, timestamps UTC. No customer refund-write endpoints.

- GET `/api/admin/refunds?page=0&size=10&status=&query=` literal search <=150 over order number/refund reference/customer; status PENDING/SUCCEEDED/CANCELLED/FAILED or blank; stable createdAt/id descending. `{items,page,size,totalElements,totalPages}`. Items RefundView plus orderNumber/orderStatus/customer `{id,username,fullName}`.
- GET `/api/admin/orders/{orderId}/refunds` => RefundSummary.
- POST same URL body `{paymentId,amount,reason,returnRequestId:null|id}`, header Idempotency-Key8–64 ASCII alnum/_/- =>201 RefundSummary plus selectedRefundId. Amount strict positive decimal string up to2fractiondigits fitting DECIMAL15,2; reason trimmed1–1000. Actor-scoped hashed key; exact order/payment/normalized amount/reason/return intent replays even after state changes; changed intent409 IDEMPOTENCY_CONFLICT. Provider/currency from original successful eligible payment. No zero, unsupported-provider, unpaid or wrong-order payment. Available=sum success minus all PENDING/SUCCEEDED holds, checked per payment and across order. Original successful payment stays SUCCEEDED.
- GET `/api/admin/refunds/{refundId}` => RefundSummary plus selectedRefundId.
- POST `/api/admin/refunds/{refundId}/confirm` body `{reference,note,acknowledged:true}` => selected summary. Reference6–150 ASCII alnum/._:/-, note optional trimmed max1000 private. Only PENDING eligible COD/MANUAL_BANK/SEPAY_SANDBOX requests not claimed by SePay execution on SUCCEEDED collection. Full request amount immutable; cannot edit amount in confirmation. Reference unique per original provider. Same normalized proof on SUCCEEDED replays preserving processedBy/completedAt/audit; different proof409 REFUND_ALREADY_CONFIRMED. Reuse409 REFUND_REFERENCE_IN_USE. Validate aggregate pending+succeeded<=gross collected under order/payment/refund locks before accepting. Confirmation does not restore/change stock, order, coupon or payment states.
- POST `/api/admin/refunds/{refundId}/cancel` body `{reason,acknowledged:true}`. Explicit attestation that no money was sent; only unsent manual PENDING requests may cancel. Persist private resolutionNote; status CANCELLED releases held budget. Same normalized cancellation reason replays; changed or already paid request409 REFUND_ALREADY_RESOLVED. Never auto-release an uncertain external gateway refund. Cancelling a request does not erase the debt: summary shows available amount and replacement request can be created against the cancelled order.

RefundView `{id,orderId,paymentId,returnRequestId,provider,status,amount,currency,reason,reference,confirmationNote,resolutionNote,processedBy:{id,fullName}|null,completedAt,createdAt,updatedAt,canConfirm,canCancel,actionBlockReason}`. These notes/verifier are staff-only; existing customer snapshots continue safe public refund fields.

RefundSummary `{orderId,orderNumber,orderStatus,currency,collectedAmount,pendingRefundAmount,refundedAmount,availableRefundAmount,canCreate,createBlockReason,payments,returns,items}`. Payment refund-budget view `{id,method,provider,status,amount,currency,pendingRefundAmount,refundedAmount,availableRefundAmount,canRefund}`. `returns` eligible received TV4 refund requests `{id,returnNumber,refundableAmount,availableRefundAmount}`. `items` sorted ID ascending. `canCreate` requires eligible cancelled order or eligible return, available manual collection budget>0. Unsupported gateways can be displayed but not manually executed. Provider-claimed SePay requests have canConfirm/canCancel=false and reject manual actions409 SEPAY_REFUND_EXECUTION_CLAIMED, including UNKNOWN or terminal failed executions; never clear provider intent to reuse the manual path. UI translates reason codes.

## Business eligibility and concurrency

Cancelled order permits refunds without a returnRequestId, bounded by original collected money. Active orders require a same-order TV4 return with resolution REFUND, status RECEIVED/COMPLETED, approved_by set and actual received items. APPROVED alone is insufficient. A cancelled order with an explicitly supplied return still validates it; never silently ignores wrong IDs. Return money cap is received item snapshot unit prices, proportionally reduced by original order-wide goods discount, rounded down to cents, capped by order gross collection. Shipping/assembly are excluded from return-goods budget. Subtract all PENDING/SUCCEEDED refunds already linked to that return, across payments. Never create/fake/approve TV4 returns here; only consume persisted evidence. Validate received quantities do not exceed the original order item quantities. TV4 remains responsible for return logistics/stock.

Lock protocol: sorted actor/owner users -> order -> original payment rows ordered ID -> return request/items if relevant -> refunds ordered ID. All same-order finance/lifecycle writers already lock the order root first. Creation, confirmation/cancellation and audit use the same database transaction at READ_COMMITTED. Audit actions REFUND_REQUESTED, REFUND_CONFIRMED, REFUND_CANCELLED exactly once. No network under transaction. Append through OrderAuditPort; verifier/time from authenticated actor and Clock UTC. Concurrent60+60 against100 cannot reserve120; replay uses same request; audit failure rolls back all effects.

Business errors: PAYMENT_NOT_REFUNDABLE, REFUND_EXCEEDS_BALANCE, REFUND_REASON_REQUIRED, RETURN_NOT_REFUNDABLE, RETURN_REFUND_EXCEEDS_BALANCE, REFUND_NOT_PENDING, REFUND_ALREADY_CONFIRMED, REFUND_REFERENCE_IN_USE, REFUND_ALREADY_RESOLVED, REFUND_RECONCILIATION_REQUIRED, IDEMPOTENCY_CONFLICT. Definitive typed409 clears uncertain create/proof attempt into editable draft; preserve401/403/429/5xx/network/INVALID_RESPONSE and unknown conflicts. A replay is checked before current-state eligibility.

## React / schema / remaining work

Staff queue `/admin/refunds`; per-order `/admin/orders/:id/refunds`; selected `/admin/refunds/:id`. Add order/payment detail links, staff nav/title/login return. Render totals, payment budgets, actual eligible returns, history and creation form. Two-step explicit proof confirmation/cancellation, fixed amount, clear pending-vs-completed semantics. Persist uncertain creation key+exact body and confirmation/cancel proof per actor/resource, restoring across reload/auth failures; map conflicts to readable Vietnamese. Read responses guard stale aborted requests. Preserve all TV3/TV4 routes and responsive nav.

V007 adds nullable refunds.confirmation_note/resolution_note VARCHAR1000. BootstrapV001–V006 immutable. Database helper create/migrate addsV007; main/dev additive migration only, no financial history deletion. BankTransferSettings only supplies existing demo notice, not evidence of actual refund. Browser mutation smoke asserts isolatedData and bank DEMO before attestation.

Independent TV3-11 performance work may add a guarded read-only local load/EXPLAIN harness with actual p50/p95/errors and dataset/config metadata; no Redis/provider claims without infrastructure. Coordinated expiry/notifications and online reconciliation require their own state-policy work and are not marked done by this manual-refund delivery. TV3-12 extends E2E cancellation->manual refund->public history and runbook. No dependency on VNPAY credentials.

## SePay extension (25/11)

SePay refund items add `sepayExecution:{state,refundId,canSubmit,canSync,message}`.
Creating a PENDING request does not automatically send money. Staff may choose the
separate Sandbox submit action when enabled and eligible, or the manual proof path
before a provider claim exists. Persisted provider intent cannot revert to manual;
uncertain results keep budget held and replay the same key. `SEPAY_REFUNDS_ENABLED`
defaults false, and a Test API token with write permission is required for sending.
This does not establish Production support.
