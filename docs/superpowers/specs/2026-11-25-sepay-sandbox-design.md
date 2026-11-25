# SePay Test mode integration

The user approved implementing the five steps discussed in chat: authenticated
webhooks, per-attempt QR, React payment screens, local CLI testing, and API
reconciliation. They chose SePay Webhooks Test mode, a test VietinBank account,
HMAC-SHA256 and `DH` followed by eight decimal digits. This approval supersedes
another design/permission round. Implementation stays in Spring Boot/React/MariaDB.

## Approach

Add an explicit SEPAY checkout/API choice, persisted as BANK_TRANSFER with provider
SEPAY_SANDBOX. Keep existing COD and manual bank flows. Hosted payment gateway
credentials and live APIs are separate products and are outside this change.
Automatic network refunds are outside the approved five-step scope; existing
proof-based manual refunds must remain available for SePay collections.

## Invariants

- Feature disabled by default. Secrets are server-only environment variables.
- Snapshot payee/bank/content on each payment. Add nullable columns with V010;
  keep 47 tables and preserve existing rows. Unique eight-digit codes are allocated
  from the payment auto-increment ID (never reused, fail on exhaustion), separate from order IDs.
- Positive SePay payments are whole VND, at least 1,000 and at most 499,000,000.
  Server totals are authoritative; no customer supplied amounts or paid flag.
- POST `/api/payments/sepay/webhook` alone is CSRF-exempt. Authenticate exact raw
  JSON bytes using HMAC-SHA256(timestamp + '.' + body), constant-time compare,
  five-minute timestamp tolerance. Reject missing/bad signatures before persistence.
- Deduplicate by provider transaction ID under a transaction. Store sanitized
  event fields, retain mismatches/unknown codes for staff review, acknowledge
  authenticated ignored events, and reject conflicting reuse of an event ID.
- Lock owner, order, coupon, payment before updating money; no HTTP under DB locks.
  Exact incoming transfer, matching bank/account/code/content only. A repeated event
  cannot collect twice. Distinct transfer for already-paid attempt needs review.
- Late exact collection on cancelled order records the real collection and one
  pending manual refund; never revives order, stock or coupon. No forced completion;
  normal staff completion policy still applies.
- Reconciliation is staff-only POST with CSRF and a provider transaction ID. Fetch
  that transaction from fixed sandbox API host, bounded HTTP timeout/body size,
  no redirects. Normalize the documented API representation into the same processor.
  API failures cannot change money or imply payment failure.
- React shows Test mode clearly, exact amount/content/QR for pending payable attempts,
  refreshes while visible with a bounded polling session and manual refresh. No
  secrets in browser; no request may confirm payment from a client assertion.
- Test against isolated DB and documented HTTP fixtures. Real SePay/CLI verification
  requires user's privately configured credentials; never claim it happened otherwise.

## Delivery

Add API contract/examples, configuration/run guide and local secret setup. Preserve
existing contracts; run Java/MariaDB, React and build checks, then independent
review. Existing chat authorization covers feature branch and GitHub PR/merge
after verification; no secret may be committed or published.

QR rendering update: generate VietQR PNG offline with ZXing3.5.4 at protected
owner/staff endpoints. No external image service receives transfer facts. Match
the published VietQR payload/CRC example and decode the generated PNG in tests.
