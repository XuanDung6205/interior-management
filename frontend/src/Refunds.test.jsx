import React from 'react';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import App from './App';
import { emptyCart, guest, staff } from './test/fixtures';
import { customerSession } from './test/paymentFixtures';

const refund = { id: 81, orderId: 101, paymentId: 31, returnRequestId: null, provider: 'MANUAL_BANK', status: 'PENDING', amount: '100.00', currency: 'VND', reason: 'Hủy đơn', reference: null, confirmationNote: null, resolutionNote: null, processedBy: null, completedAt: null, createdAt: '2026-11-17T01:00:00Z', updatedAt: '2026-11-17T01:00:00Z', canConfirm: true, canCancel: true, actionBlockReason: null };
const summary = { orderId: 101, orderNumber: 'NN-101', orderStatus: 'CANCELLED', currency: 'VND', collectedAmount: '200.00', pendingRefundAmount: '100.00', refundedAmount: '0.00', availableRefundAmount: '100.00', canCreate: true, createBlockReason: null, selectedRefundId: 81, payments: [{ id: 31, method: 'BANK_TRANSFER', provider: 'MANUAL_BANK', status: 'SUCCEEDED', amount: '200.00', currency: 'VND', pendingRefundAmount: '100.00', refundedAmount: '0.00', availableRefundAmount: '100.00', canRefund: true }], returns: [{ id: 51, returnNumber: 'RT-51', refundableAmount: '50.00', availableRefundAmount: '40.00' }], items: [refund] };
const succeeded = { ...summary, items: [{ ...refund, status: 'SUCCEEDED', canConfirm: false, canCancel: false, reference: 'DEMO-REFUND-001', confirmationNote: 'Chứng từ giả nội bộ', processedBy: { id: 2, fullName: 'Nhân viên' }, completedAt: '2026-11-17T02:00:00Z' }] };
const list = { items: [{ ...refund, orderNumber: 'NN-101', orderStatus: 'CANCELLED', customer: { id: 15, username: 'customer', fullName: 'Khách demo' } }], page: 0, size: 10, totalElements: 11, totalPages: 2 };
afterEach(() => { vi.unstubAllGlobals(); sessionStorage.clear(); });
function mount(path = '/admin/refunds/81', handlers = {}, session = staff) {
  const calls = [];
  vi.stubGlobal('fetch', vi.fn(async (url, options = {}) => {
    const method = options.method || 'GET'; calls.push({ url, ...options, method });
    const handler = handlers[`${method} ${url}`]; let value;
    if (handler) value = typeof handler === 'function' ? await handler(options) : handler;
    else if (url === '/api/session') value = session;
    else if (url === '/api/csrf') value = { token: 'csrf', headerName: 'X-CSRF-TOKEN' };
    else if (url === '/api/session/login') value = staff;
    else if (url === '/api/cart/merge') value = emptyCart;
    else if (url === '/api/admin/refunds/81' || url === '/api/admin/orders/101/refunds') value = summary;
    else if (url.startsWith('/api/admin/refunds?')) value = list;
    else throw new Error(`Unexpected HTTP ${method} ${url}`);
    if (value instanceof Response) return value;
    return new Response(JSON.stringify(value.body ?? value), { status: Object.hasOwn(value, 'body') ? value.status : 200, headers: { 'Content-Type': 'application/json' } });
  }));
  return { ...render(<MemoryRouter initialEntries={[path]}><App /></MemoryRouter>), calls, user: userEvent.setup() };
}
async function createDraft(user) {
  await user.type(await screen.findByLabelText('Số tiền đề nghị hoàn'), '25.50');
  await user.type(screen.getByLabelText('Lý do đề nghị hoàn'), 'Hoàn phần còn lại');
  await user.selectOptions(screen.getByLabelText('Phiếu trả hàng đã nhận'), '51');
  await user.click(screen.getByRole('button', { name: 'Kiểm tra yêu cầu hoàn' }));
}
async function proof(user) {
  await user.type(await screen.findByLabelText('Tham chiếu hoàn tiền'), 'DEMO-REFUND-001');
  await user.type(screen.getByLabelText('Ghi chú hoàn tiền nội bộ'), 'Chứng từ giả nội bộ');
  await user.click(screen.getByLabelText('Tôi đã đối chiếu chứng từ và xác nhận đã trả đủ số tiền này.'));
  await user.click(screen.getByRole('button', { name: 'Kiểm tra chứng từ hoàn' }));
}
async function cancellation(user) {
  await user.type(await screen.findByLabelText('Lý do hủy yêu cầu hoàn'), 'Chưa gửi tiền, cần chia khoản hoàn');
  await user.click(screen.getByLabelText('Tôi xác nhận chưa gửi tiền cho yêu cầu hoàn này.'));
  await user.click(screen.getByRole('button', { name: 'Kiểm tra hủy yêu cầu' }));
}
it.each(['/admin/refunds', '/admin/refunds/81', '/admin/orders/101/refunds'])('guards private refund route %s and returns there after login', async path => {
  const first = mount(path, {}, customerSession);
  await screen.findByRole('heading', { name: 'Khu vực dành cho nhân viên' });
  expect(first.calls.some(call => call.url.startsWith('/api/admin/'))).toBe(false); first.unmount();
  const { user } = mount(path, {}, guest);
  await user.click(await screen.findByRole('link', { name: 'Đăng nhập tài khoản khác' }));
  await user.type(screen.getByLabelText('Tên đăng nhập'), 'staff'); await user.type(screen.getByLabelText('Mật khẩu', { exact: true }), 'password'); await user.click(screen.getByRole('button', { name: 'Đăng nhập' }));
  await screen.findByRole('heading', { name: path === '/admin/refunds' ? 'Quản lý hoàn tiền.' : path.endsWith('/81') ? 'Đối chiếu hoàn #81' : 'Hoàn tiền đơn NN-101' });
  await waitFor(() => expect(document.title).toBe('Quản lý hoàn tiền · Nếp Nhà'));
  await user.click(screen.getByRole('button', { name: 'Quản trị' }));
  expect(within(screen.getByRole('navigation', { name: 'Điều hướng chính' })).getAllByRole('link')).toHaveLength(17);
});
it('filters literal refund search, status and pages, ignoring obsolete read failures', async () => {
  let rejectOld;
  const { user, calls } = mount('/admin/refunds', { 'GET /api/admin/refunds?page=0&size=10&status=PENDING&query=': () => new Promise((resolve, reject) => { rejectOld = reject; }) });
  await screen.findByRole('link', { name: 'Đối chiếu hoàn 81' });
  await user.click(screen.getByRole('button', { name: 'Trang sau' }));
  await waitFor(() => expect(calls.some(call => call.url === '/api/admin/refunds?page=1&size=10&status=&query=')).toBe(true));
  await user.selectOptions(screen.getByLabelText('Trạng thái hoàn tiền'), 'PENDING');
  await waitFor(() => expect(rejectOld).toBeTypeOf('function'));
  await user.selectOptions(screen.getByLabelText('Trạng thái hoàn tiền'), 'SUCCEEDED');
  await user.type(screen.getByLabelText('Tìm yêu cầu hoàn'), 'REF%_'); await user.click(screen.getByRole('button', { name: 'Tìm kiếm' }));
  await waitFor(() => expect(calls.some(call => call.url === '/api/admin/refunds?page=0&size=10&status=SUCCEEDED&query=REF%25_')).toBe(true));
  await act(async () => rejectOld(new TypeError('obsolete'))); expect(screen.queryByRole('alert')).not.toBeInTheDocument();
});
it('uses server payment/return budgets and reviews exact create intent before reserving money', async () => {
  const { user, calls } = mount('/admin/orders/101/refunds', { 'POST /api/admin/orders/101/refunds': { status: 201, body: summary } });
  await createDraft(user); expect(calls.some(call => call.method === 'POST')).toBe(false);
  expect(screen.getByTestId('refund-available')).toHaveTextContent('100 ₫');
  await user.click(screen.getByRole('button', { name: 'Tạo yêu cầu hoàn' })); await screen.findByText('Đã giữ ngân sách cho yêu cầu hoàn. Chưa xác nhận đã trả tiền.');
  const sent = calls.find(call => call.method === 'POST' && call.url.endsWith('/refunds'));
  expect(JSON.parse(sent.body)).toEqual({ paymentId: 31, amount: '25.50', reason: 'Hoàn phần còn lại', returnRequestId: 51 });
  expect(sent.headers['Idempotency-Key']).toMatch(/^[A-Za-z0-9_-]{8,64}$/);
});
it.each([401, 403, 429, 500, 'invalid', 'unknown'])('preserves exact create intent/key on uncertain %s across reload and changed eligibility', async outcome => {
  const rejected = outcome === 'invalid' ? new Response('{}', { status: 201 }) : { status: outcome === 'unknown' ? 409 : outcome, body: { code: 'UNKNOWN_CONFLICT', message: 'Chưa rõ kết quả.' } };
  const first = mount('/admin/orders/101/refunds', { 'POST /api/admin/orders/101/refunds': rejected });
  await createDraft(first.user); await first.user.click(screen.getByRole('button', { name: 'Tạo yêu cầu hoàn' })); await screen.findByRole('button', { name: 'Thử lại yêu cầu hoàn đã gửi' });
  const original = first.calls.find(call => call.method === 'POST' && call.url.endsWith('/refunds')); first.unmount();
  const next = mount('/admin/orders/101/refunds', { 'GET /api/admin/orders/101/refunds': { ...summary, canCreate: false }, 'POST /api/admin/orders/101/refunds': { status: 201, body: summary } });
  await next.user.click(await screen.findByRole('button', { name: 'Thử lại yêu cầu hoàn đã gửi' })); await screen.findByText('Đã giữ ngân sách cho yêu cầu hoàn. Chưa xác nhận đã trả tiền.');
  const replay = next.calls.find(call => call.method === 'POST' && call.url.endsWith('/refunds')); expect(replay.body).toBe(original.body); expect(replay.headers['Idempotency-Key']).toBe(original.headers['Idempotency-Key']);
});
it('returns a definite rejected creation to an editable draft across reload and translates codes', async () => {
  const first = mount('/admin/orders/101/refunds', { 'POST /api/admin/orders/101/refunds': { status: 409, body: { code: 'RETURN_REFUND_EXCEEDS_BALANCE', message: 'RETURN_REFUND_EXCEEDS_BALANCE' } } });
  await createDraft(first.user); await first.user.click(screen.getByRole('button', { name: 'Tạo yêu cầu hoàn' })); await screen.findByText('Số tiền vượt ngân sách còn lại của phiếu trả hàng.');
  expect(screen.queryByRole('button', { name: 'Thử lại yêu cầu hoàn đã gửi' })).not.toBeInTheDocument(); first.unmount(); mount('/admin/orders/101/refunds');
  expect(await screen.findByLabelText('Số tiền đề nghị hoàn')).toHaveValue('25.50'); expect(screen.getByLabelText('Lý do đề nghị hoàn')).toHaveValue('Hoàn phần còn lại');
});
it('requires fixed-amount two-step proof and never sends amount/status/verifier', async () => {
  const { user, calls } = mount(undefined, { 'POST /api/admin/refunds/81/confirm': succeeded });
  await proof(user); expect(calls.some(call => call.url.endsWith('/confirm'))).toBe(false); expect(screen.queryByLabelText(/Số tiền|Người xác minh|Trạng thái/)).not.toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: 'Xác nhận đã hoàn tiền' })); await screen.findByText('Đã ghi nhận chứng từ hoàn tiền.');
  expect(JSON.parse(calls.find(call => call.url.endsWith('/confirm')).body)).toEqual({ reference: 'DEMO-REFUND-001', note: 'Chứng từ giả nội bộ', acknowledged: true });
});
it.each(['confirm', 'cancel'])('replays exact uncertain %s proof after reload even when already resolved', async action => {
  const first = mount(undefined, { [`POST /api/admin/refunds/81/${action}`]: new Response('{', { status: 200 }) });
  await (action === 'confirm' ? proof(first.user) : cancellation(first.user));
  await first.user.click(screen.getByRole('button', { name: action === 'confirm' ? 'Xác nhận đã hoàn tiền' : 'Xác nhận hủy yêu cầu hoàn' }));
  await screen.findByRole('button', { name: 'Thử lại thao tác hoàn đã gửi' }); const sent = first.calls.find(call => call.url.endsWith(`/${action}`)); first.unmount();
  const next = mount(undefined, { 'GET /api/admin/refunds/81': succeeded, [`POST /api/admin/refunds/81/${action}`]: succeeded });
  await next.user.click(await screen.findByRole('button', { name: 'Thử lại thao tác hoàn đã gửi' })); await screen.findByText(action === 'confirm' ? 'Đã ghi nhận chứng từ hoàn tiền.' : 'Đã hủy yêu cầu chưa gửi tiền. Nghĩa vụ hoàn vẫn cần đối chiếu.');
  expect(next.calls.find(call => call.url.endsWith(`/${action}`)).body).toBe(sent.body);
});
it('cancels only with explicit unsent attestation and retains a rejected private draft', async () => {
  const first = mount(undefined, { 'POST /api/admin/refunds/81/cancel': { status: 409, body: { code: 'REFUND_ALREADY_RESOLVED', message: 'REFUND_ALREADY_RESOLVED' } } });
  await cancellation(first.user); expect(first.calls.some(call => call.url.endsWith('/cancel'))).toBe(false); await first.user.click(screen.getByRole('button', { name: 'Xác nhận hủy yêu cầu hoàn' }));
  await screen.findByText('Yêu cầu đã được xử lý. Hãy tải lại để đối chiếu.'); expect(screen.queryByRole('button', { name: 'Thử lại thao tác hoàn đã gửi' })).not.toBeInTheDocument(); first.unmount(); mount();
  expect(await screen.findByLabelText('Lý do hủy yêu cầu hoàn')).toHaveValue('Chưa gửi tiền, cần chia khoản hoàn');
});
it('never offers manual execution for gateway refunds and displays friendly block reason', async () => {
  mount(undefined, { 'GET /api/admin/refunds/81': { ...summary, items: [{ ...refund, provider: 'VNPAY', canConfirm: false, canCancel: false, actionBlockReason: 'PAYMENT_NOT_REFUNDABLE' }] } });
  await screen.findByText('Khoản thu này không hỗ trợ hoàn thủ công.'); expect(screen.queryByLabelText('Tham chiếu hoàn tiền')).not.toBeInTheDocument(); expect(screen.queryByLabelText('Lý do hủy yêu cầu hoàn')).not.toBeInTheDocument();
});
it.each([401, 403, 429, 500, 'unknown'])('retains proof and blocks cancellation for uncertain confirm status %s', async status => {
  const first = mount(undefined, { 'POST /api/admin/refunds/81/confirm': { status: status === 'unknown' ? 409 : status, body: { code: 'UNKNOWN_CONFLICT', message: 'Chưa rõ kết quả.' } } });
  await proof(first.user); await first.user.click(screen.getByRole('button', { name: 'Xác nhận đã hoàn tiền' })); await screen.findByRole('button', { name: 'Thử lại thao tác hoàn đã gửi' });
  expect(screen.getByLabelText('Tham chiếu hoàn tiền')).toBeDisabled(); expect(screen.queryByLabelText('Lý do hủy yêu cầu hoàn')).not.toBeInTheDocument();
  const body = first.calls.find(call => call.url.endsWith('/confirm')).body; first.unmount();
  const next = mount(undefined, { 'POST /api/admin/refunds/81/confirm': succeeded }); await next.user.click(await screen.findByRole('button', { name: 'Thử lại thao tác hoàn đã gửi' })); await screen.findByText('Đã ghi nhận chứng từ hoàn tiền.'); expect(next.calls.find(call => call.url.endsWith('/confirm')).body).toBe(body);
});
it('scopes uncertain creation to the original actor and order and never sends an active-order request without a received return', async () => {
  const first = mount('/admin/orders/101/refunds', { 'POST /api/admin/orders/101/refunds': () => { throw new TypeError('lost response'); } });
  await createDraft(first.user); await first.user.click(screen.getByRole('button', { name: 'Tạo yêu cầu hoàn' })); await screen.findByRole('button', { name: 'Thử lại yêu cầu hoàn đã gửi' }); first.unmount();
  const second = mount('/admin/orders/101/refunds', {}, { ...staff, user: { ...staff.user, id: 999 } }); await screen.findByLabelText('Số tiền đề nghị hoàn'); expect(screen.queryByRole('button', { name: 'Thử lại yêu cầu hoàn đã gửi' })).not.toBeInTheDocument(); second.unmount();
  const third = mount('/admin/orders/102/refunds', { 'GET /api/admin/orders/102/refunds': { ...summary, orderId: 102, orderStatus: 'COMPLETED' } });
  await third.user.type(await screen.findByLabelText('Số tiền đề nghị hoàn'), '25'); await third.user.type(screen.getByLabelText('Lý do đề nghị hoàn'), 'Hàng đã nhận lại'); expect(screen.getByRole('button', { name: 'Kiểm tra yêu cầu hoàn' })).toBeDisabled();
  await third.user.selectOptions(screen.getByLabelText('Phiếu trả hàng đã nhận'), '51'); expect(screen.getByRole('button', { name: 'Kiểm tra yêu cầu hoàn' })).toBeEnabled(); expect(third.calls.some(call => call.method === 'POST')).toBe(false);
});
it.each(['0', '-1', '1.234', '10000000000000', '1e2'])('blocks malformed/nonpositive refund amount %s before sending', async amount => {
  const { user, calls } = mount('/admin/orders/101/refunds'); await user.type(await screen.findByLabelText('Số tiền đề nghị hoàn'), amount); await user.type(screen.getByLabelText('Lý do đề nghị hoàn'), 'Hủy đơn'); expect(screen.getByRole('button', { name: 'Kiểm tra yêu cầu hoàn' })).toBeDisabled(); expect(calls.some(call => call.method === 'POST')).toBe(false);
});
it.each([['FAILED', 'Thất bại'], ['PENDING', 'Đang chờ']])('distinguishes a %s original attempt value from a successfully collected payment', async (status, label) => {
  mount('/admin/orders/101/refunds', { 'GET /api/admin/orders/101/refunds': { ...summary, payments: [...summary.payments, { id: 32, method: 'COD', provider: 'COD', status, amount: '550.00', currency: 'VND', pendingRefundAmount: '0.00', refundedAmount: '0.00', availableRefundAmount: '0.00', canRefund: false }] } });
  const attempt = within((await screen.findByRole('heading', { name: '#32 · Thanh toán khi nhận hàng' })).parentElement);
  expect(attempt.getByText(label)).toBeInTheDocument(); expect(attempt.getByText('Giá trị yêu cầu')).toBeInTheDocument(); expect(attempt.getByText('550 ₫')).toBeInTheDocument(); expect(attempt.queryByText('Đã thu')).not.toBeInTheDocument();
  const collected = within(screen.getByRole('heading', { name: '#31 · Chuyển khoản ngân hàng' }).parentElement);
  expect(collected.getByText('Đã thu')).toBeInTheDocument(); expect(collected.getByText('Đã thu tiền')).toBeInTheDocument();
});

it('allows evidence-based manual creation and resolution for a SePay Sandbox collection',async()=>{
 const sepay={...summary,items:[{...refund,provider:'SEPAY_SANDBOX'}],payments:summary.payments.map(p=>({...p,provider:'SEPAY_SANDBOX'}))};
 const first=mount('/admin/orders/101/refunds',{'GET /api/admin/orders/101/refunds':sepay});
 await first.user.type(await screen.findByLabelText('Số tiền đề nghị hoàn'),'25.50');await first.user.type(screen.getByLabelText('Lý do đề nghị hoàn'),'Trả khoản thu Sandbox');expect(screen.getByRole('button',{name:'Kiểm tra yêu cầu hoàn'})).toBeEnabled();first.unmount();
 const second=mount('/admin/refunds/81',{'GET /api/admin/refunds/81':sepay});await proof(second.user);expect(screen.getByRole('button',{name:'Xác nhận đã hoàn tiền'})).toBeEnabled();
});
