import React from 'react';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, expect, it, vi } from 'vitest';
import { SePayEventReview, SePayJobs, SePayReconcile, SePayGuidance } from './features/payments/SePayParts';
import RefundResolve from './features/refunds/RefundResolve';
import { paymentApi } from './features/payments/paymentApi';
import { refundApi } from './features/refunds/refundApi';

afterEach(() => sessionStorage.clear());
const mount = element => render(<MemoryRouter>{element}</MemoryRouter>);
const event = { id: 8, code: 'DH12345678', amount: '1000.00', reference: 'TEST-REF', reason: 'TRANSFER_MISMATCH', paymentId: 31, reviewStatus: 'OPEN' };
it('localizes review reasons and preserves the exact review after lost response, then shows immutable evidence', async () => {
  const user = userEvent.setup(); const client = { events: vi.fn().mockResolvedValue({ items: [event] }), reviewEvent: vi.fn().mockRejectedValueOnce(new Error('Mất phản hồi')).mockResolvedValue({ ...event, reviewStatus: 'REVIEWED', reviewNote: 'Đã kiểm tra số tiền khác đơn', reviewedBy: 2, reviewedAt: '2026-11-25T02:00:00Z' }) };
  const first = mount(<SePayEventReview client={client} userId={2} />);
  expect(client.events).not.toHaveBeenCalled();
  await user.click(await screen.findByRole('button', { name: 'Tải sự kiện SePay' }));
  expect(await screen.findByText('Thông tin chuyển khoản không khớp khoản thanh toán.')).toBeInTheDocument();
  const input = screen.getByLabelText('Ghi chú kiểm tra sự kiện #8');
  await user.type(input, 'Đã kiểm tra số tiền khác đơn');
  await user.click(screen.getByRole('button', { name: 'Lưu ghi chú kiểm tra' }));
  await screen.findByText('Mất phản hồi'); expect(input).toBeDisabled();
  first.unmount(); mount(<SePayEventReview client={client} userId={2} />);
  await user.click(await screen.findByRole('button', { name: 'Tải sự kiện SePay' }));
  expect(screen.getByLabelText('Ghi chú kiểm tra sự kiện #8')).toBeDisabled();
  await user.click(screen.getByRole('button', { name: 'Thử lại ghi chú đã gửi' }));
  await screen.findByText('Đã kiểm tra số tiền khác đơn');
  expect(client.reviewEvent).toHaveBeenNthCalledWith(1, 8, { note: 'Đã kiểm tra số tiền khác đơn', expectedReviewStatus: 'OPEN' });
  expect(client.reviewEvent.mock.calls[1]).toEqual(client.reviewEvent.mock.calls[0]);
  expect(screen.queryByLabelText('Ghi chú kiểm tra sự kiện #8')).not.toBeInTheDocument();
});
it('filters reviewed events and recovers conflicts by reloading, without editable original facts', async () => {
  const user = userEvent.setup(); const client = { events: vi.fn().mockResolvedValue({ items: [event] }), reviewEvent: vi.fn().mockRejectedValue(Object.assign(new Error('Sự kiện đã có ghi chú khác.'), { status: 409, code: 'SEPAY_EVENT_ALREADY_REVIEWED' })) };
  mount(<SePayEventReview client={client} userId={2} />);
  await user.click(await screen.findByRole('button', { name: 'Tải sự kiện SePay' }));
  await user.selectOptions(screen.getByLabelText('Tình trạng kiểm tra'), 'OPEN');
  expect(client.events).toHaveBeenLastCalledWith('FAILED', 0, 'OPEN');
  await user.type(screen.getByLabelText('Ghi chú kiểm tra sự kiện #8'), 'Đã xem');
  await user.click(screen.getByRole('button', { name: 'Lưu ghi chú kiểm tra' }));
  await screen.findByText('Sự kiện đã có ghi chú khác.');
  expect(screen.getByRole('button', { name: 'Tải lại sự kiện để đối chiếu' })).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: /thu tiền|hoàn tiền/i })).not.toBeInTheDocument();
});
it('unfreezes a definitively invalid review while retaining an editable draft across remount', async () => {
  const user = userEvent.setup(); const client = { events: vi.fn().mockResolvedValue({ items: [event] }), reviewEvent: vi.fn().mockRejectedValue(Object.assign(new Error('Ghi chú không hợp lệ.'), { status: 400, code: 'VALIDATION_ERROR', fieldErrors: { note: 'Ghi chú không hợp lệ.' } })) };
  const first = mount(<SePayEventReview client={client} userId={2} />);
  await user.click(await screen.findByRole('button', { name: 'Tải sự kiện SePay' }));
  await user.type(screen.getByLabelText('Ghi chú kiểm tra sự kiện #8'), 'Ghi chú bị từ chối');
  await user.click(screen.getByRole('button', { name: 'Lưu ghi chú kiểm tra' }));
  await screen.findByText('Ghi chú không hợp lệ.');
  expect(screen.getByLabelText('Ghi chú kiểm tra sự kiện #8')).toBeEnabled();
  first.unmount(); mount(<SePayEventReview client={client} userId={2} />);
  await user.click(await screen.findByRole('button', { name: 'Tải sự kiện SePay' }));
  const input = screen.getByLabelText('Ghi chú kiểm tra sự kiện #8');
  expect(input).toBeEnabled(); expect(input).toHaveValue('Ghi chú bị từ chối');
  await user.clear(input); await user.type(input, 'Ghi chú đã sửa');
  expect(screen.getByRole('button', { name: 'Lưu ghi chú kiểm tra' })).toBeEnabled();
});
it('keeps pasted control characters editable without persisting or sending an invalid review', async () => {
  const user = userEvent.setup(); const client = { events: vi.fn().mockResolvedValue({ items: [event] }), reviewEvent: vi.fn() };
  mount(<SePayEventReview client={client} userId={2} />);
  await user.click(await screen.findByRole('button', { name: 'Tải sự kiện SePay' }));
  const input = screen.getByLabelText('Ghi chú kiểm tra sự kiện #8'); fireEvent.change(input, { target: { value: 'A\tB' } });
  expect(screen.getByRole('button', { name: 'Lưu ghi chú kiểm tra' })).toBeDisabled(); expect(input).toBeEnabled();
  expect(client.reviewEvent).not.toHaveBeenCalled();
  expect(screen.getByText(/không được chứa ký tự điều khiển/)).toBeInTheDocument();
  fireEvent.change(input, { target: { value: 'A\nB' } });
  expect(screen.getByRole('button', { name: 'Lưu ghi chú kiểm tra' })).toBeEnabled();
});
it('runs a bounded automation batch only on explicit staff action and reports deferred work', async () => {
  const user = userEvent.setup(); const client = { jobs: vi.fn().mockResolvedValue({ enabled: true, canRun: true, ttlSeconds: 1800, intervalSeconds: 60, queue: { pending: 3, due: 2, leased: 0 } }), runJobs: vi.fn().mockResolvedValue({ scanned: 2, reconciled: 1, expired: 0, deferred: 1, failed: 0 }) };
  mount(<SePayJobs client={client} />); expect(client.jobs).not.toHaveBeenCalled();
  await user.click(await screen.findByRole('button', { name: 'Xem job SePay' }));
  await screen.findByText(/30 phút/);
  await user.click(screen.getByRole('button', { name: 'Chạy một lượt đối soát' }));
  await screen.findByText(/Đã kiểm tra 2 khoản/); expect(client.runJobs).toHaveBeenCalledTimes(1);
  expect(screen.getByText(/Hoãn 1 khoản/)).toBeInTheDocument();
});
it('searches the immutable payment code and makes incomplete provider results clear', async () => {
  const user = userEvent.setup(); const onUpdated = vi.fn(); const client = { reconcileCode: vi.fn().mockResolvedValue({ items: [], reconciliationSummary: { checked: 2, matched: 0, complete: false } }) };
  mount(<SePayReconcile selected={{ id: 31, sepay: { canReconcile: true, code: 'DH12345678' } }} client={client} userId={2} onUpdated={onUpdated} />);
  await user.click(screen.getByRole('button', { name: 'Tra cứu theo mã DH12345678' }));
  expect(client.reconcileCode).toHaveBeenCalledWith(31);
  await screen.findByText(/Kết quả chưa đầy đủ/); expect(onUpdated).toHaveBeenCalledTimes(1);
});
it('shows server expiry without claiming that an uncertain provider check has cancelled the order', () => {
  mount(<SePayGuidance summary={{ items: [{ id: 31, status: 'PENDING', provider: 'SEPAY_SANDBOX', expiresAt: '2026-11-25T10:00:00Z', amount: '1000.00', sepay: { qrUrl: '/api/qr', bank: 'VietinBank', accountNumber: '0000000001', accountHolder: 'TEST', transferContent: 'SEVQR DH12345678' } }] }} />);
  expect(screen.getByText(/Hạn thanh toán thử nghiệm:/)).toBeInTheDocument();
  expect(screen.getByText(/Hệ thống chỉ hủy đơn khi xác minh/)).toBeInTheDocument();
});
const refund = { id: 81, provider: 'SEPAY_SANDBOX', status: 'PENDING', amount: '1000.00', canConfirm: true, canCancel: true, sepayExecution: { state: 'NOT_STARTED', canSubmit: true, canSync: false } };
it.each(['SEPAY_REFUND_NOT_CONFIGURED', 'SEPAY_REFUND_TRANSACTION_REQUIRED', 'SEPAY_REFUND_REFERENCE_REQUIRED', 'SEPAY_REFUND_AMOUNT_UNSUPPORTED'])('restores manual fallback after verified preclaim rejection %s and remount', async code => {
  const user = userEvent.setup(), onUpdated = vi.fn();
  const authoritative = { items: [{ ...refund, sepayExecution: { state: 'NOT_STARTED', canSubmit: false, canSync: false } }] };
  const client = { sepaySubmit: vi.fn().mockRejectedValue(Object.assign(new Error('Từ chối trước khi gửi.'), { status: 409, code })), detail: vi.fn().mockResolvedValue(authoritative) };
  const first = mount(<RefundResolve id={81} actorId={2} selected={refund} client={client} onUpdated={onUpdated} />);
  await user.click(screen.getByLabelText('Tôi đồng ý gửi toàn bộ yêu cầu hoàn này qua SePay Sandbox.'));
  await user.click(screen.getByRole('button', { name: 'Gửi yêu cầu hoàn qua SePay Sandbox' }));
  await waitFor(() => expect(client.detail).toHaveBeenCalledWith(81));
  await waitFor(() => expect(screen.getByLabelText('Tham chiếu hoàn tiền')).toBeEnabled());
  expect(screen.getByLabelText('Lý do hủy yêu cầu hoàn')).toBeEnabled(); expect(onUpdated).toHaveBeenCalledWith(authoritative);
  first.unmount(); mount(<RefundResolve id={81} actorId={2} selected={authoritative.items[0]} client={client} onUpdated={onUpdated} />);
  expect(screen.getByLabelText('Tham chiếu hoàn tiền')).toBeEnabled();
  expect(screen.queryByRole('button', { name: 'Thử lại yêu cầu SePay đã gửi' })).not.toBeInTheDocument();
});
it.each(['claimed', 'refresh-failed'])('retains provider intent when preclaim rejection refresh is %s', async outcome => {
  const user = userEvent.setup(); const detail = outcome === 'claimed' ? vi.fn().mockResolvedValue({ items: [{ ...refund, sepayExecution: { state: 'UNKNOWN', canSubmit: false, canSync: true, refundId: null } }] }) : vi.fn().mockRejectedValue(new Error('Mất kết nối'));
  const client = { sepaySubmit: vi.fn().mockRejectedValue(Object.assign(new Error('Từ chối trước khi gửi.'), { status: 409, code: 'SEPAY_REFUND_NOT_CONFIGURED' })), detail };
  mount(<RefundResolve id={81} actorId={2} selected={refund} client={client} onUpdated={vi.fn()} />);
  await user.click(screen.getByLabelText('Tôi đồng ý gửi toàn bộ yêu cầu hoàn này qua SePay Sandbox.')); await user.click(screen.getByRole('button', { name: 'Gửi yêu cầu hoàn qua SePay Sandbox' }));
  await waitFor(() => expect(detail).toHaveBeenCalledWith(81));
  expect(screen.queryByLabelText('Tham chiếu hoàn tiền')).not.toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Thử lại yêu cầu SePay đã gửi' })).toBeEnabled();
});
it('requires explicit approval before provider refund and holds the same send intent across a lost response', async () => {
  const user = userEvent.setup(); const client = { sepaySubmit: vi.fn().mockRejectedValueOnce(new Error('Mất phản hồi')).mockResolvedValue({ items: [{ ...refund, canConfirm: false, canCancel: false, sepayExecution: { state: 'UNKNOWN', canSubmit: false, canSync: true } }] }) };
  const onUpdated = vi.fn(); const first = mount(<RefundResolve id={81} actorId={2} selected={refund} client={client} onUpdated={onUpdated} />);
  expect(screen.getByRole('button', { name: 'Gửi yêu cầu hoàn qua SePay Sandbox' })).toBeDisabled();
  await user.click(screen.getByLabelText('Tôi đồng ý gửi toàn bộ yêu cầu hoàn này qua SePay Sandbox.'));
  await user.click(screen.getByRole('button', { name: 'Gửi yêu cầu hoàn qua SePay Sandbox' }));
  await screen.findByText('Mất phản hồi');
  first.unmount(); mount(<RefundResolve id={81} actorId={2} selected={refund} client={client} onUpdated={onUpdated} />);
  expect(screen.queryByLabelText('Tham chiếu hoàn tiền')).not.toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: 'Thử lại yêu cầu SePay đã gửi' }));
  expect(client.sepaySubmit).toHaveBeenNthCalledWith(1, 81, { acknowledged: true });
  expect(client.sepaySubmit.mock.calls[1]).toEqual(client.sepaySubmit.mock.calls[0]);
});
it('blocks manual confirmation/cancellation after provider claim even if stale capability flags remain true', async () => {
  const user = userEvent.setup(); const onUpdated = vi.fn(); const client = { sepaySync: vi.fn().mockResolvedValue({ items: [] }) };
  mount(<RefundResolve id={81} actorId={2} selected={{ ...refund, sepayExecution: { state: 'UNKNOWN', canSubmit: false, canSync: true, refundId: 'RF-TEST' } }} client={client} onUpdated={onUpdated} />);
  expect(screen.queryByLabelText('Tham chiếu hoàn tiền')).not.toBeInTheDocument();
  expect(screen.queryByLabelText('Lý do hủy yêu cầu hoàn')).not.toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: 'Kiểm tra kết quả hoàn SePay' }));
  expect(client.sepaySync).toHaveBeenCalledWith(81); expect(onUpdated).toHaveBeenCalledTimes(1);
});
it('encodes the review/job/refund HTTP contracts and never sends provider credentials', async () => {
  const summary = { orderId: 1, selectedRefundId: 81, items: [{ id: 81 }], payments: [], returns: [], collectedAmount: '1000.00', pendingRefundAmount: '1000.00', refundedAmount: '0.00', availableRefundAmount: '0.00' };
  const api = { request: vi.fn().mockResolvedValue(summary) }; const payments = paymentApi(api); const refunds = refundApi(api);
  await payments.events('FAILED', 0, 'REVIEWED'); await payments.reviewEvent(8, { note: 'Đã kiểm tra', expectedReviewStatus: 'OPEN' }); await payments.jobs(); await payments.runJobs(); await payments.reconcileCode(31); await refunds.sepaySubmit(81, { acknowledged: true }); await refunds.sepaySync(81);
  expect(api.request.mock.calls).toEqual([
    ['/admin/payments/sepay/events?status=FAILED&page=0&reviewStatus=REVIEWED'], ['/admin/payments/sepay/events/8/review', { method: 'POST', body: { note: 'Đã kiểm tra', expectedReviewStatus: 'OPEN' } }],
    ['/admin/payments/sepay/jobs'], ['/admin/payments/sepay/jobs/run', { method: 'POST', body: {} }], ['/admin/payments/31/sepay/reconcile-code', { method: 'POST', body: {} }],
    ['/admin/refunds/81/sepay/submit', { method: 'POST', body: { acknowledged: true } }], ['/admin/refunds/81/sepay/sync', { method: 'POST', body: {} }],
  ]);
});
