import { ApiError } from '../../lib/apiClient';

export const refundMessages = {
  PAYMENT_NOT_REFUNDABLE: 'Khoản thu này không hỗ trợ hoàn thủ công.',
  REFUND_EXCEEDS_BALANCE: 'Số tiền vượt ngân sách hoàn còn lại. Hãy tải lại để đối chiếu.',
  REFUND_REASON_REQUIRED: 'Hãy nhập lý do đề nghị hoàn.',
  RETURN_NOT_REFUNDABLE: 'Phiếu trả hàng chưa đủ điều kiện hoàn: cần đã duyệt và thực nhận hàng.',
  RETURN_REFUND_EXCEEDS_BALANCE: 'Số tiền vượt ngân sách còn lại của phiếu trả hàng.',
  REFUND_NOT_PENDING: 'Yêu cầu không còn ở trạng thái chờ hoàn.',
  REFUND_ALREADY_CONFIRMED: 'Yêu cầu đã xác nhận bằng chứng từ khác. Hãy đối chiếu lịch sử.',
  REFUND_REFERENCE_IN_USE: 'Tham chiếu hoàn đã được sử dụng. Cần đối chiếu chứng từ.',
  REFUND_ALREADY_RESOLVED: 'Yêu cầu đã được xử lý. Hãy tải lại để đối chiếu.',
  REFUND_RECONCILIATION_REQUIRED: 'Cần đối soát khoản hoàn trước khi xử lý tiếp.',
  IDEMPOTENCY_CONFLICT: 'Khóa yêu cầu đã dùng cho nội dung khác. Kiểm tra lịch sử trước khi tạo lại.',
  ORDER_NOT_REFUNDABLE: 'Đơn chưa đủ điều kiện hoàn. Cần đơn đã hủy hoặc phiếu trả hàng đã thực nhận.',
  NO_REFUNDABLE_BALANCE: 'Không còn ngân sách hoàn khả dụng.',
  REFUND_BALANCE_EXHAUSTED: 'Không còn ngân sách hoàn khả dụng.',
};
export const refundBlockMessage = code => refundMessages[code] || 'Chưa thể xử lý hoàn tiền. Hãy tải lại và đối chiếu điều kiện từ máy chủ.';
export const definitiveRefundRejections = new Set(Object.keys(refundMessages));
export const friendlyRefundError = failure => {
  if (!Object.hasOwn(refundMessages, failure.code)) return failure;
  return Object.assign(new Error(refundMessages[failure.code]), { status: failure.status, code: failure.code, fieldErrors: failure.fieldErrors });
};
const money = value => typeof value === 'string' && /^\d+\.\d{2}$/.test(value);
async function checkedSummary(promise, selected = false) {
  const value = await promise;
  if (!value || !Number.isSafeInteger(value.orderId) || !Array.isArray(value.items) || !Array.isArray(value.payments) || !Array.isArray(value.returns) || !['collectedAmount', 'pendingRefundAmount', 'refundedAmount', 'availableRefundAmount'].every(key => money(value[key])) || selected && !value.items.some(item => item.id === value.selectedRefundId)) {
    throw new ApiError(200, { code: 'INVALID_RESPONSE', message: 'Chưa đọc được kết quả hoàn tiền. Giữ nguyên yêu cầu đã gửi và thử lại.' });
  }
  return value;
}
export const refundApi = api => ({
  list: (page, status, query, signal) => api.request(`/admin/refunds?${new URLSearchParams({ page, size: 10, status, query })}`, { signal }),
  summary: (id, signal) => checkedSummary(api.request(`/admin/orders/${encodeURIComponent(id)}/refunds`, { signal })),
  detail: (id, signal) => checkedSummary(api.request(`/admin/refunds/${encodeURIComponent(id)}`, { signal }), true),
  create: (id, attempt) => checkedSummary(api.request(`/admin/orders/${encodeURIComponent(id)}/refunds`, { method: 'POST', body: attempt.body, headers: { 'Idempotency-Key': attempt.key } }), true),
  resolve: (id, attempt) => checkedSummary(api.request(`/admin/refunds/${encodeURIComponent(id)}/${attempt.action}`, { method: 'POST', body: attempt.body }), true),
});
const keyFor = (kind, actorId, id) => `nep-nha.refund.${kind}.${actorId}.${id}`;
export function readRefundRequest(kind, actorId, id) {
  try {
    const value = JSON.parse(sessionStorage.getItem(keyFor(kind, actorId, id)));
    if (!value || typeof value !== 'object') return null;
    if (kind === 'create') return /^[A-Za-z0-9_-]{8,64}$/.test(value.key) && validCreate(value.body) ? value : null;
    if (kind === 'createDraft') return typeof value.amount === 'string' && typeof value.reason === 'string' && value.reason.length <= 1000 ? value : null;
    if (kind === 'resolveDraft') return typeof value.reference === 'string' && typeof value.note === 'string' && typeof value.reason === 'string' ? value : null;
    const body = value.body;
    return body?.acknowledged === true && (value.action === 'confirm' ? /^[A-Za-z0-9._:/-]{6,150}$/.test(body.reference) && (body.note == null || typeof body.note === 'string' && body.note.length <= 1000) : value.action === 'cancel' && typeof body.reason === 'string' && body.reason.trim().length > 0 && body.reason.length <= 1000) ? value : null;
  } catch { return null; }
}
export function validAmount(value) { return /^(?:0|[1-9]\d{0,12})(?:\.\d{1,2})?$/.test(value) && /[1-9]/.test(value); }
function validCreate(body) { return Number.isSafeInteger(body?.paymentId) && validAmount(body.amount) && typeof body.reason === 'string' && body.reason.trim().length > 0 && body.reason.length <= 1000 && (body.returnRequestId == null || Number.isSafeInteger(body.returnRequestId)); }
export function saveRefundRequest(kind, actorId, id, value) {
  try { sessionStorage.setItem(keyFor(kind, actorId, id), JSON.stringify(value)); }
  catch { throw new Error('Cần cho phép bộ nhớ phiên để giữ nguyên yêu cầu trước khi gửi.'); }
}
export function clearRefundRequest(kind, actorId, id) { try { sessionStorage.removeItem(keyFor(kind, actorId, id)); } catch { /* An exact replay remains safe if storage removal fails. */ } }
