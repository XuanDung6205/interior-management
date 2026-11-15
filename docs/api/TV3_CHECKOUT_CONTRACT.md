# TV3-04/05/06 — checkout and customer orders

All endpoints below require the current authenticated session. Mutations require CSRF.
Money is a decimal string with two fractional digits, timestamps are UTC ISO-8601.

## Checkout

`GET /api/checkout/addresses` returns `{items:[{id,label,recipientName,phone,addressLine1,addressLine2,wardName,districtName,provinceName,postalCode,countryCode}]}` from the current user's saved addresses.

`POST /api/checkout/quote` body:
```
{cartId:1,addressId:null,shippingAddress:{recipientName,phone,email,addressLine1,addressLine2,wardName,districtName,provinceName,postalCode,countryCode:"VN",companyName,taxCode},couponCode:null,paymentMethod:"COD",assemblyRequested:false,customerNote:null}
```
Use exactly one of addressId and shippingAddress. Inline addresses are snapshots, not saved to the address book. Only VN delivery is currently supported. Payment methods: COD, BANK_TRANSFER; gateway comes in TV3-09.

Response `{cartId,quoteFingerprint,currency,items,shippingAddress,subtotal,discountAmount,shippingFee,assemblyFee,taxAmount,grandTotal,couponCode,paymentMethod,assemblyRequested,warnings}`. Items: `{variantId,productId,sku,productName,variantName,imageUrl,colorName,dimensionsSnapshot,materialsSnapshot,warrantyMonths,assemblyRequired,quantity,unitPrice,lineTotal,availableQuantity}`. Quote does not reserve anything. Invalid/empty cart, unavailable SKU/stock or coupon returns an API error.

Every persisted monetary amount, including each line total and subtotal before coupon, must fit DECIMAL(15,2); an excessive amount is409 ORDER_AMOUNT_TOO_LARGE even if a coupon would make grandTotal fit. Browser middleware401/403/408/429, network/5xx and unknown conflicts do not establish an original order's outcome: preserve the pending exact request/key through re-login/reload. Only a typed business response after replay lookup (or QUOTE_CHANGED) can return the attempt to editing; restore the submitted form and obtain a fresh quote.

Default configurable policy: VND 50,000 delivery, free when merchandise subtotal >=10,000,000; requested assembly VND100,000 per assembly-required unit. Catalog prices include tax; additional taxAmount=0. Country VN only. These are local project defaults, not an external carrier quote.

`POST /api/checkout/orders`: same body plus `quoteFingerprint`, header `Idempotency-Key` (8–64 ASCII letters/digits/_/-). Returns order detail, HTTP201 (including replay). Keep the same key and exact request after uncertain network failure. Keys are scoped to the authenticated user. Different request with same key:409 IDEMPOTENCY_CONFLICT. Fresh calculation differs:409 `{code:"QUOTE_CHANGED",message,quote:<new quote>}`; user must review, then place with a new key. Converted carts are retained; replay does not touch a newer cart.

## Orders

`GET /api/orders?page=0&size=10&status=` returns `{items:[{id,orderNumber,status,currency,grandTotal,itemCount,placedAt,paymentStatus,shipmentStatus}],page,size,totalElements,totalPages}`. Status optional, exact order status enum. Size1–100.

`GET /api/orders/{id}` returns `{id,orderNumber,status,currency,subtotal,discountAmount,shippingFee,assemblyFee,taxAmount,grandTotal,couponCode,customerNote,placedAt,cancelledAt,cancelReason,items,shippingAddress,payments,shipments,history,refunds,canCancel,cancellationMessage}`. Items contain saved snapshots and id. Payment `{id,method,status,amount,currency,paidAt}`; shipment `{id,status,trackingNumber,carrier}`; history `{fromStatus,toStatus,note,changedAt}`; refund `{id,paymentId,status,amount,currency,reason}`. No internal_note, provider payload or other owner's data.

`POST /api/orders/{id}/cancel` body `{reason}` (required,1–1000 chars). Returns updated detail. Only PENDING_CONFIRMATION or CONFIRMED without physical fulfillment/dispatch can be cancelled. Repeated cancellation returns the same cancelled order without another release/history/refund. Pending payments become CANCELLED. Successful collections retain SUCCEEDED and create durable PENDING refund requests for the remaining unrefunded/unreserved amount; actual refund execution/verification belongs to TV3-10. PROCESSING/shipped/completed orders require staff after-sales handling.

## Integration boundaries

InventoryPort reserve/release joins the caller transaction (MANDATORY), never REQUIRES_NEW. Reserve locks balances by variant then warehouse, allocates across active warehouses, increments reserved only and inserts inventory_reservations. Physical on_hand and inventory_movements are untouched. Release refuses fulfilled reservations or shipment evidence; repeated release is harmless. Holds have no automatic expiry yet: they remain until cancellation/TV4 fulfillment; TV3-11 owns coordinated expiry. Coupon RESERVED likewise has expires_at=NULL and counts toward usage until release or payment lifecycle changes it to APPLIED. Never ignore a hold simply because a timestamp passed without releasing physical reservation.

Lock order for lifecycle integration: referenced owner/actor user rows (ascending ID) → order roots → coupon → payment rows → warehouses (shared, ascending ID) → balances (variant,warehouse). Checkout/cancel for the same owner serialize on that user row. TV4 must lock/recheck the order before fulfillment, then use the same warehouse/balance order and recheck remaining holds. Avoid acquiring a user FK lock late through history/payment-verifier writes after holding coupon or inventory locks.

Checkout snapshots use CheckoutCatalogPort, saved addresses use CheckoutAddressPort; these adapters only read TV1/TV2 tables. New additive V002 adds orders.request_fingerprint; bootstrap V001 stays immutable. JDBC order persistence deliberately uses scalar FKs, shares Spring's transaction with existing JPA cart/coupons and inventory adapter, and avoids duplicating TV1/TV2 entities.

TV3-07/V003 now persists selected assembly separately as orders.assembly_requested and exposes assemblyRequested in customer/admin detail, including free assembly. TV4 must fulfill this selected service for technically assembly-required lines; see TV3_ADMIN_ORDERS_CONTRACT.md for completion proof. Catalog/address snapshots represent the calculation read; no stronger cross-module price/address locking is specified.
