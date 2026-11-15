# TV3-07 — administration and lifecycle

All `/api/admin/orders` routes require ADMIN or STAFF, session/CSRF for mutations. All decimal amounts are strings; UTC dates ISO8601. Customer routes never expose internalNote/audit data. React routes `/admin/orders`, `/admin/orders/:id` use current app styling and staff guard.

## API

`GET /api/admin/orders?page=0&size=10&status=&query=` returns `{items,page,size,totalElements,totalPages}`. Summary = customer order summary plus `customer:{id,username,fullName,email}` (id nullable for historical guest orders), `recipientName`, `recipientPhone`. Search literal (not SQL wildcards), max150 chars, against order number/customer name/username/email/recipient name/phone. Page>=0 size1–100, status existing order enum. Stable newest placement/id first.

`GET /api/admin/orders/{id}` returns customer order detail snapshot plus `customer`, `internalNote`, `confirmedAt`, `completedAt`, `updatedAt`, `assemblyRequested`, `allowedTransitions:[targetStatus]`, `blockedTransitions:[{toStatus,reasons:[string]}]`. Include `PROCESSING -> COMPLETED` as blocked with specific reasons until evidence exists. Never mutate payment/shipping from this screen. Existing customer `history.note` is public, transition note is a message visible to the customer; internal note has its own editor. Internal note never goes into public status history.

`POST /api/admin/orders/{id}/transitions` body `{fromStatus,toStatus,note}`. Supported edges: PENDING_CONFIRMATION→CONFIRMED|CANCELLED, CONFIRMED→PROCESSING|CANCELLED, PROCESSING→COMPLETED|CANCELLED. CANCELLED requires reason1–1000 chars and no dispatch/fulfilled/movement evidence. PROCESSING cancellation is staff-only before dispatch. Other note optional max1000. Invalid edge409 INVALID_TRANSITION; stale fromStatus409 ORDER_STATE_CHANGED. Exact already-applied edge returns200 detail without additional history/audit (history must demonstrate that edge). No regression from terminal states. Successful transition records changed_by/status history and durable audit in same transaction.

`PATCH /api/admin/orders/{id}/internal-note` body `{internalNote,expectedInternalNote}`. Both nullable; desired empty ->null, expected is the exact prior value including imported whitespace; maximum5000 chars. If current note differs from expected and desired value is not already current,409 INTERNAL_NOTE_CHANGED; preserve frontend draft, reload explicitly. Same update repeated is harmless and no duplicate audit. Returns updated admin detail; UI never overwrites another edit silently.

## Completion evidence and shared services

CONFIRMED→PROCESSING requires each line fully covered by unreleased inventory reservations (fulfilled+remaining equals ordered quantity). No stock modification on confirm/process/complete; TV4 owns physical stock/shipping operations.

PROCESSING→COMPLETED requires: net SUCCEEDED payments minus PENDING/SUCCEEDED refunds covers grand_total (zero-total orders exempt); every noncancelled shipment delivered with delivered_at; exact delivered quantities per line; exact fulfilled reservation quantities and no remaining holds; matching physical SHIPMENT movements per line. No failed/returned/in-transit remainder. If assembly_requested, every delivered shipment containing a technically assembly-required line must mark installation_required and completed installation with timestamp/staff. Shipping/payment fields are read-only evidence, not supplied by the client. Completion marks coupon APPLIED under coupon lock.

OrderCompletionPolicy.evaluate(long orderId) returns Evaluation(boolean eligible,List<String> reasons), evaluating persisted evidence with constant-count batched JDBC reads. Called inside the lifecycle's order lock; readonly detail uses the same policy for guidance. Availability may change; POST always checks again.

Lock order: owner and actor users sorted by ID → order root → coupon → payments/fulfillment evidence → warehouse/balances through InventoryPort. Owner must be rechecked after root lock. All writers including TV4 and TV3-08 must obey root lock and recheck lifecycle state. Shared cancellation preserves SUCCEEDED payments, queues pending refunds, releases holds/coupon once, cancels unshipped shipments including planned installation. No hard deletion.

OrderAuditPort.append(long actorId,long orderId,String action,Map<String,Object> before,Map<String,Object> after) joins existing transaction (MANDATORY); local JDBC adapter appends audit_logs with actor name snapshot/server-generated request ID and explicit UTC created_at using the application's Clock (not the database's local-time default). Replace this adapter with TV1's service later; no duplicate audit table/module.

V005 adds orders.assembly_requested, backfills charged historical assembly orders true and preserves other snapshots. Checkout writes the selected service even for free assembly. V001/V002 remain immutable; migrate script applies V002 then V005 for isolated schemas. Local main/demo migrations are additive only.
