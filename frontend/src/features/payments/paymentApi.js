export const paymentApi = api => ({
  reconcile: (paymentId, body) => api.request(`/admin/payments/${encodeURIComponent(paymentId)}/sepay/reconcile`, { method: 'POST', body }),
  reconcileCode: paymentId => api.request(`/admin/payments/${encodeURIComponent(paymentId)}/sepay/reconcile-code`, { method: 'POST', body: {} }),
  events: (status, page, reviewStatus = 'ALL') => api.request(`/admin/payments/sepay/events?${new URLSearchParams({status,page,reviewStatus})}`),
  reviewEvent: (id, body) => api.request(`/admin/payments/sepay/events/${encodeURIComponent(id)}/review`, { method: 'POST', body }),
  jobs: () => api.request('/admin/payments/sepay/jobs'),
  runJobs: () => api.request('/admin/payments/sepay/jobs/run', { method: 'POST', body: {} }),
  summary: (orderId, signal) => api.request(`/orders/${encodeURIComponent(orderId)}/payments`, { signal }),
  create: (orderId, attempt) => api.request(`/orders/${encodeURIComponent(orderId)}/payments`, { method: 'POST', body: attempt.body, headers: { 'Idempotency-Key': attempt.key } }),
  list: (page, status, method, query, signal) => api.request(`/admin/payments?${new URLSearchParams({ page, size: 10, status, method, query })}`, { signal }),
  detail: (paymentId, signal) => api.request(`/admin/payments/${encodeURIComponent(paymentId)}`, { signal }),
  confirm: (paymentId, proof) => api.request(`/admin/payments/${encodeURIComponent(paymentId)}/confirm`, { method: 'POST', body: proof }),
});
export const creationRejections = new Set(['PAYMENT_PENDING_EXISTS', 'PAYMENT_NOT_REQUIRED', 'ORDER_NOT_PAYABLE', 'ORDER_ALREADY_PAID', 'REFUND_RECONCILIATION_REQUIRED', 'PAYMENT_BANK_NOT_CONFIGURED', 'SEPAY_NOT_CONFIGURED', 'SEPAY_AMOUNT_UNSUPPORTED', 'SEPAY_CODE_EXHAUSTED']);
export const proofRejections = new Set(['PAYMENT_ALREADY_CONFIRMED', 'PAYMENT_REFERENCE_IN_USE', 'PAYMENT_EXCEEDS_BALANCE', 'PAYMENT_NOT_CONFIRMABLE', 'PAYMENT_EXPIRED', 'REFUND_RECONCILIATION_REQUIRED']);
const keyFor = (kind, userId, resourceId) => `nep-nha.payment.${kind}.${userId}.${resourceId}`;
export function readPaymentRequest(kind, userId, resourceId) {
  try {
    const value = JSON.parse(sessionStorage.getItem(keyFor(kind, userId, resourceId)));
    if (kind === 'create') return value && /^[A-Za-z0-9_-]{8,64}$/.test(value.key) && ['COD', 'BANK_TRANSFER', 'SEPAY'].includes(value.body?.method) && Object.keys(value.body).length === 1 ? value : null;
    if (kind === 'reconcile') return value && /^[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}$/.test(value.transactionId) ? value : null;
    if (kind === 'draft') return value && typeof value.reference === 'string' && value.reference.length <= 150 && (value.note == null || typeof value.note === 'string' && value.note.length <= 1000) ? value : null;
    return value && /^[A-Za-z0-9._:/-]{6,150}$/.test(value.reference) && value.acknowledged === true && (value.note == null || typeof value.note === 'string' && value.note.length <= 1000) ? value : null;
  } catch { return null; }
}
export function savePaymentRequest(kind, userId, resourceId, value) {
  try { sessionStorage.setItem(keyFor(kind, userId, resourceId), JSON.stringify(value)); }
  catch { throw new Error('Cần cho phép bộ nhớ phiên để giữ yêu cầu và thử lại an toàn trước khi gửi.'); }
}
export function clearPaymentRequest(kind, userId, resourceId) { try { sessionStorage.removeItem(keyFor(kind, userId, resourceId)); } catch { /* Retaining a completed request only permits an exact server replay. */ } }
