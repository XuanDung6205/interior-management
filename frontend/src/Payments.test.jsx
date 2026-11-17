import React from 'react';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import App from './App';
import { emptyCart, guest, staff } from './test/fixtures';
import { customerSession, payment, paymentSummary, paidSummary, paymentList, staffPayment, staffPaymentSummary } from './test/paymentFixtures';

afterEach(() => { vi.unstubAllGlobals(); sessionStorage.clear(); });
function mount(path = '/orders/101/payments', handlers = {}, session = customerSession) {
  const calls = [];
  vi.stubGlobal('fetch', vi.fn(async (url, options = {}) => {
    const method = options.method || 'GET'; calls.push({ url, ...options, method });
    const handler = handlers[`${method} ${url}`]; let value;
    if (handler) value = typeof handler === 'function' ? await handler(options) : handler;
    else if (url === '/api/session') value = session;
    else if (url === '/api/csrf') value = { token: 'csrf', headerName: 'X-CSRF-TOKEN' };
    else if (url === '/api/session/login') value = session.authenticated ? session : staff;
    else if (url === '/api/cart/merge') value = emptyCart;
    else if (url === '/api/orders/101/payments') value = paymentSummary;
    else if (url === '/api/admin/payments/31') value = staffPaymentSummary;
    else if (url.startsWith('/api/admin/payments?')) value = paymentList;
    else throw new Error(`Unexpected HTTP ${method} ${url}`);
    if (value instanceof Response) return value;
    return new Response(JSON.stringify(value.body ?? value), { status: Object.hasOwn(value, 'body') ? value.status : 200, headers: { 'Content-Type': 'application/json' } });
  }));
  return { ...render(<MemoryRouter initialEntries={[path]}><App /></MemoryRouter>), calls, user: userEvent.setup() };
}
async function prepareProof(user) {
  await user.type(await screen.findByLabelText('Mã chứng từ / tham chiếu'), 'DEMO-RECEIPT-001');
  await user.type(screen.getByLabelText('Ghi chú xác minh nội bộ'), 'Đã kiểm tra chứng từ giả');
  await user.click(screen.getByLabelText('Tôi đã đối chiếu chứng từ và xác nhận thực thu đủ số tiền này.'));
  await user.click(screen.getByRole('button', { name: 'Kiểm tra xác nhận' }));
}

it('requires login for customer payments and staff privileges for private payment screens', async () => {
  const first = mount('/orders/101/payments', {}, guest);
  await screen.findByRole('heading', { name: 'Đăng nhập để tiếp tục' });
  expect(first.calls).toHaveLength(1); first.unmount();
  const next = mount('/admin/payments/31');
  await screen.findByRole('heading', { name: 'Khu vực dành cho nhân viên' });
  expect(next.calls.some(call => call.url.startsWith('/api/admin/payments'))).toBe(false);
});

it('shows the checkout-created pending attempt, demo bank guidance and server amounts without creating a duplicate', async () => {
  const { calls } = mount('/orders/101/payments', { 'GET /api/orders/101/payments': { ...paymentSummary, createBlockReason: 'PAYMENT_PENDING_EXISTS' } });
  await screen.findByText('DEMO-KHONG-CHUYEN-TIEN');
  expect(screen.getByTestId('payment-remaining')).toHaveTextContent('2.550.000 ₫');
  expect(screen.getByRole('heading', { name: 'DEMO — không chuyển tiền thật' })).toBeInTheDocument();
  expect(screen.getByText('Đang có yêu cầu thanh toán chờ xử lý.')).toBeInTheDocument();
  expect(screen.queryByText('PAYMENT_PENDING_EXISTS')).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Tạo yêu cầu thanh toán' })).not.toBeInTheDocument();
  expect(calls.some(call => call.method === 'POST')).toBe(false);
});

it('refreshes to the server-collected state and never exposes staff verification notes to the customer', async () => {
  let reads = 0;
  const { user } = mount('/orders/101/payments', { 'GET /api/orders/101/payments': () => ++reads === 1 ? paymentSummary : paidSummary });
  await screen.findByText('DEMO-KHONG-CHUYEN-TIEN');
  await user.click(screen.getByRole('button', { name: 'Làm mới thanh toán' }));
  await waitFor(() => expect(screen.getByTestId('payment-remaining')).toHaveTextContent('0 ₫'));
  expect(within(screen.getByRole('region', { name: 'Lịch sử thanh toán' })).getByText('Đã thu tiền')).toBeInTheDocument();
  expect(screen.queryByText('Đã kiểm tra chứng từ giả')).not.toBeInTheDocument();
  expect(screen.queryByLabelText('Ghi chú xác minh nội bộ')).not.toBeInTheDocument();
});

it.each(['UNCONFIGURED', 'EXPIRED'])('does not present usable bank instructions for %s', async mode => {
  mount('/orders/101/payments', { 'GET /api/orders/101/payments': mode === 'EXPIRED' ? { ...paymentSummary, items: [{ ...payment, isExpired: true, status: 'EXPIRED' }] } : { ...paymentSummary, bankTransfer: { mode, configured: false, isDemo: false, notice: 'Chưa cấu hình ngân hàng.', bankName: null, accountNumber: null, accountHolder: null, transferContent: 'NN-101' } } });
  await screen.findByRole('heading', { name: 'Thanh toán đơn NN-101' });
  expect(screen.queryByText('DEMO-KHONG-CHUYEN-TIEN')).not.toBeInTheDocument();
});

it('only creates an allowed retry with a method body and server-selected amount', async () => {
  const { user, calls } = mount('/orders/101/payments', { 'GET /api/orders/101/payments': { ...paymentSummary, canCreate: true, createBlockReason: null, items: [{ ...payment, status: 'FAILED' }] }, 'POST /api/orders/101/payments': { status: 201, body: paymentSummary } });
  await screen.findByRole('button', { name: 'Tạo yêu cầu thanh toán' });
  await user.selectOptions(screen.getByLabelText('Phương thức yêu cầu mới'), 'BANK_TRANSFER');
  await user.click(screen.getByRole('button', { name: 'Tạo yêu cầu thanh toán' }));
  await screen.findByText('Đã tạo yêu cầu thanh toán. Chưa xác nhận đã thu tiền.');
  const call = calls.find(call => call.method === 'POST' && call.url.endsWith('/payments'));
  expect(JSON.parse(call.body)).toEqual({ method: 'BANK_TRANSFER' });
  expect(call.headers['Idempotency-Key']).toMatch(/^[A-Za-z0-9_-]{8,64}$/);
});

it.each([401, 403, 429])('preserves an uncertain creation and its exact key/body through status %s and reload', async status => {
  let posts = 0;
  const initial = mount('/orders/101/payments', { 'GET /api/orders/101/payments': { ...paymentSummary, canCreate: true, items: [] }, 'POST /api/orders/101/payments': () => { if (++posts === 1) throw new TypeError('lost response'); return { status, body: { code: 'ACCESS_OR_RATE_LIMIT', message: 'Thử lại phiên sau.', fieldErrors: {} } }; } });
  await screen.findByRole('button', { name: 'Tạo yêu cầu thanh toán' });
  await initial.user.click(screen.getByRole('button', { name: 'Tạo yêu cầu thanh toán' }));
  await screen.findByRole('button', { name: 'Thử lại yêu cầu thanh toán' });
  await initial.user.click(screen.getByRole('button', { name: 'Thử lại yêu cầu thanh toán' }));
  await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('Thử lại phiên sau.'));
  const original = initial.calls.find(call => call.method === 'POST' && call.url.endsWith('/payments')); initial.unmount();
  const replay = mount('/orders/101/payments', { 'POST /api/orders/101/payments': { status: 201, body: paymentSummary } });
  await screen.findByRole('button', { name: 'Thử lại yêu cầu thanh toán' });
  await replay.user.click(screen.getByRole('button', { name: 'Thử lại yêu cầu thanh toán' }));
  await screen.findByText('Đã tạo yêu cầu thanh toán. Chưa xác nhận đã thu tiền.');
  const retried = replay.calls.find(call => call.method === 'POST' && call.url.endsWith('/payments'));
  expect(retried.body).toBe(original.body); expect(retried.headers['Idempotency-Key']).toBe(original.headers['Idempotency-Key']);
  expect(sessionStorage.length).toBe(0);
});

it('reconciles an explicit pending-attempt rejection without sending a new request automatically', async () => {
  let reads = 0;
  const { user, calls } = mount('/orders/101/payments', { 'GET /api/orders/101/payments': () => ++reads === 1 ? { ...paymentSummary, canCreate: true, items: [] } : paymentSummary, 'POST /api/orders/101/payments': { status: 409, body: { code: 'PAYMENT_PENDING_EXISTS', message: 'Đã có khoản đang chờ.', fieldErrors: {} } } });
  await screen.findByRole('button', { name: 'Tạo yêu cầu thanh toán' }); await user.click(screen.getByRole('button', { name: 'Tạo yêu cầu thanh toán' }));
  await screen.findByText('Đã có khoản đang chờ.');
  expect(screen.queryByRole('button', { name: 'Thử lại yêu cầu thanh toán' })).not.toBeInTheDocument();
  expect(calls.filter(call => call.method === 'POST' && call.url.endsWith('/payments'))).toHaveLength(1);
  expect(sessionStorage.length).toBe(0);
});

it('keeps the creation key after an unreadable HTTP201 response and replays it exactly', async () => {
  let posts = 0;
  const { user, calls } = mount('/orders/101/payments', { 'GET /api/orders/101/payments': { ...paymentSummary, canCreate: true, items: [] }, 'POST /api/orders/101/payments': () => ++posts === 1 ? new Response('{"orderId":', { status: 201 }) : { status: 201, body: paymentSummary } });
  await screen.findByRole('button', { name: 'Tạo yêu cầu thanh toán' }); await user.click(screen.getByRole('button', { name: 'Tạo yêu cầu thanh toán' }));
  await screen.findByRole('button', { name: 'Thử lại yêu cầu thanh toán' });
  expect(screen.queryByText('Đã tạo yêu cầu thanh toán. Chưa xác nhận đã thu tiền.')).not.toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: 'Thử lại yêu cầu thanh toán' })); await screen.findByText('Đã tạo yêu cầu thanh toán. Chưa xác nhận đã thu tiền.');
  const sent = calls.filter(call => call.method === 'POST' && call.url.endsWith('/payments'));
  expect(sent[1].body).toBe(sent[0].body); expect(sent[1].headers['Idempotency-Key']).toBe(sent[0].headers['Idempotency-Key']);
});

it('filters and pages staff payments using exact status/method/literal-query parameters', async () => {
  const { user, calls } = mount('/admin/payments', { 'GET /api/admin/payments?page=0&size=10&status=&method=&query=': { ...paymentList, totalElements: 11, totalPages: 2 } }, staff);
  await screen.findByRole('link', { name: 'Xác minh khoản 31' });
  await user.click(screen.getByRole('button', { name: 'Trang sau' }));
  await waitFor(() => expect(calls.some(call => call.url === '/api/admin/payments?page=1&size=10&status=&method=&query=')).toBe(true));
  await user.selectOptions(screen.getByLabelText('Trạng thái thanh toán'), 'PENDING');
  await user.selectOptions(screen.getByLabelText('Phương thức thanh toán'), 'BANK_TRANSFER');
  await user.type(screen.getByLabelText('Tìm khoản thanh toán'), 'REF%_');
  await user.click(screen.getByRole('button', { name: 'Tìm kiếm' }));
  await waitFor(() => expect(calls.some(call => call.url === '/api/admin/payments?page=0&size=10&status=PENDING&method=BANK_TRANSFER&query=REF%25_')).toBe(true));
});

it('ignores an obsolete payment-list request failure after newer filters have loaded', async () => {
  let rejectOld;
  const { user } = mount('/admin/payments', {
    'GET /api/admin/payments?page=0&size=10&status=&method=BANK_TRANSFER&query=': () => new Promise((resolve, reject) => { rejectOld = reject; }),
    'GET /api/admin/payments?page=0&size=10&status=PENDING&method=BANK_TRANSFER&query=': paymentList,
  }, staff);
  await screen.findByRole('link', { name: 'Xác minh khoản 31' });
  await user.selectOptions(screen.getByLabelText('Phương thức thanh toán'), 'BANK_TRANSFER');
  await waitFor(() => expect(rejectOld).toBeTypeOf('function'));
  await user.selectOptions(screen.getByLabelText('Trạng thái thanh toán'), 'PENDING');
  await screen.findByRole('link', { name: 'Xác minh khoản 31' });
  await act(async () => { rejectOld(new TypeError('obsolete request failed after abort')); });
  expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  expect(screen.getByRole('link', { name: 'Xác minh khoản 31' })).toBeInTheDocument();
});

it('requires two-step attestation and sends proof only, with no amount/status/verifier inputs', async () => {
  const { user, calls } = mount('/admin/payments/31', { 'POST /api/admin/payments/31/confirm': paidSummary }, staff);
  await screen.findByLabelText('Mã chứng từ / tham chiếu');
  expect(screen.getByRole('button', { name: 'Kiểm tra xác nhận' })).toBeDisabled();
  expect(screen.queryByLabelText(/Số tiền|Người xác minh|Trạng thái/)).not.toBeInTheDocument();
  await prepareProof(user);
  expect(calls.some(call => call.url.endsWith('/confirm'))).toBe(false);
  await user.click(screen.getByRole('button', { name: 'Xác nhận đã thu tiền' }));
  await screen.findByText('Đã ghi nhận chứng từ và khoản thu.');
  expect(JSON.parse(calls.find(call => call.url.endsWith('/confirm')).body)).toEqual({ reference: 'DEMO-RECEIPT-001', note: 'Đã kiểm tra chứng từ giả', acknowledged: true });
});

it.each([0, 401, 403, 429])('replays exact staff proof after an uncertain response/status %s and reload, even if already paid', async status => {
  let posts = 0;
  const first = mount('/admin/payments/31', { 'POST /api/admin/payments/31/confirm': () => { if (++posts === 1 || !status) throw new TypeError('lost response'); return { status, body: { code: 'ACCESS_OR_RATE_LIMIT', message: 'Giữ nguyên chứng từ để kiểm tra lại.', fieldErrors: {} } }; } }, staff);
  await prepareProof(first.user); await first.user.click(screen.getByRole('button', { name: 'Xác nhận đã thu tiền' }));
  await screen.findByRole('button', { name: 'Thử lại xác nhận đã gửi' });
  if (status) { await first.user.click(screen.getByRole('button', { name: 'Thử lại xác nhận đã gửi' })); await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('Giữ nguyên chứng từ để kiểm tra lại.')); }
  const proof = first.calls.find(call => call.url.endsWith('/confirm')).body; first.unmount();
  const replay = mount('/admin/payments/31', { 'GET /api/admin/payments/31': paidSummary, 'POST /api/admin/payments/31/confirm': paidSummary }, staff);
  await screen.findByRole('button', { name: 'Thử lại xác nhận đã gửi' });
  await replay.user.click(screen.getByRole('button', { name: 'Thử lại xác nhận đã gửi' }));
  await screen.findByText('Đã ghi nhận chứng từ và khoản thu.');
  expect(replay.calls.find(call => call.url.endsWith('/confirm')).body).toBe(proof);
});

it('preserves reference and note on a conflicting proof and displays the finance-reconciliation error', async () => {
  const { user } = mount('/admin/payments/31', { 'POST /api/admin/payments/31/confirm': { status: 409, body: { code: 'PAYMENT_REFERENCE_IN_USE', message: 'Chứng từ đã được dùng, cần đối soát.', fieldErrors: {} } } }, staff);
  await prepareProof(user); await user.click(screen.getByRole('button', { name: 'Xác nhận đã thu tiền' }));
  await screen.findByText('Chứng từ đã được dùng, cần đối soát.');
  expect(screen.getByLabelText('Mã chứng từ / tham chiếu')).toHaveValue('DEMO-RECEIPT-001');
  expect(screen.getByLabelText('Ghi chú xác minh nội bộ')).toHaveValue('Đã kiểm tra chứng từ giả');
});

it('retains a definitively rejected staff proof as an editable draft across reload', async () => {
  const first = mount('/admin/payments/31', { 'POST /api/admin/payments/31/confirm': { status: 409, body: { code: 'PAYMENT_REFERENCE_IN_USE', message: 'Chứng từ cần đối soát lại.', fieldErrors: {} } } }, staff);
  await prepareProof(first.user); await first.user.click(screen.getByRole('button', { name: 'Xác nhận đã thu tiền' }));
  await screen.findByText('Chứng từ cần đối soát lại.'); first.unmount();
  mount('/admin/payments/31', {}, staff);
  const input = await screen.findByLabelText('Mã chứng từ / tham chiếu');
  expect(input).toHaveValue('DEMO-RECEIPT-001'); expect(input).toBeEnabled();
  expect(screen.getByLabelText('Ghi chú xác minh nội bộ')).toHaveValue('Đã kiểm tra chứng từ giả');
});

it('scopes an uncertain customer payment attempt to its authenticated user and order', async () => {
  const first = mount('/orders/101/payments', { 'GET /api/orders/101/payments': { ...paymentSummary, canCreate: true, items: [] }, 'POST /api/orders/101/payments': () => { throw new TypeError('lost response'); } });
  await screen.findByRole('button', { name: 'Tạo yêu cầu thanh toán' }); await first.user.click(screen.getByRole('button', { name: 'Tạo yêu cầu thanh toán' }));
  await screen.findByRole('button', { name: 'Thử lại yêu cầu thanh toán' }); first.unmount();
  const otherOrder = mount('/orders/102/payments', { 'GET /api/orders/102/payments': { ...paymentSummary, orderId: 102, orderNumber: 'NN-102' } });
  await screen.findByRole('heading', { name: 'Thanh toán đơn NN-102' });
  expect(screen.queryByRole('button', { name: 'Thử lại yêu cầu thanh toán' })).not.toBeInTheDocument(); otherOrder.unmount();
  mount('/orders/101/payments', {}, { ...customerSession, user: { ...customerSession.user, id: 16 } });
  await screen.findByRole('heading', { name: 'Thanh toán đơn NN-101' });
  expect(screen.queryByRole('button', { name: 'Thử lại yêu cầu thanh toán' })).not.toBeInTheDocument();
});

it('disables creating a bank-transfer attempt when public bank details are unconfigured', async () => {
  mount('/orders/101/payments', { 'GET /api/orders/101/payments': { ...paymentSummary, canCreate: true, items: [], bankTransfer: { mode: 'UNCONFIGURED', configured: false, isDemo: false, notice: 'Chưa cấu hình ngân hàng.', bankName: null, accountNumber: null, accountHolder: null, transferContent: 'NN-101' } } });
  await screen.findByLabelText('Phương thức yêu cầu mới');
  expect(screen.getByRole('option', { name: 'Chuyển khoản ngân hàng' })).toBeDisabled();
});

it('warns that late collection creates a pending refund rather than a returned transfer', async () => {
  mount('/admin/payments/31', { 'GET /api/admin/payments/31': { ...staffPaymentSummary, orderStatus: 'CANCELLED', remainingAmount: '0.00', items: [{ ...staffPayment, status: 'CANCELLED', isLateCollection: true }] } }, staff);
  await screen.findByText('Khoản thu đến sau khi đơn đã hủy');
  expect(screen.getByText(/Yêu cầu hoàn sẽ ở trạng thái chờ xử lý, chưa phải đã hoàn tiền/)).toBeInTheDocument();
});

it('does not offer manual confirmation for a gateway or expired blocked receipt', async () => {
  mount('/admin/payments/31', { 'GET /api/admin/payments/31': { ...staffPaymentSummary, items: [{ ...staffPayment, method: 'GATEWAY', provider: 'OTHER_GATEWAY', canConfirm: false, isExpired: true, confirmationMessage: 'Không hỗ trợ xác nhận thủ công khoản này.' }] } }, staff);
  await screen.findByText('Không hỗ trợ xác nhận thủ công khoản này.');
  expect(screen.queryByLabelText('Mã chứng từ / tham chiếu')).not.toBeInTheDocument();
});

it.each(['/orders/101/payments', '/admin/payments/31'])('returns to payment route %s after reauthentication', async path => {
  const { user } = mount(path, { 'POST /api/session/login': path.startsWith('/admin') ? staff : customerSession }, guest);
  const link = within(screen.getByRole('main'));
  await user.click(await link.findByRole('link', { name: path.startsWith('/admin') ? 'Đăng nhập tài khoản khác' : 'Đăng nhập' }));
  await user.type(screen.getByLabelText('Tên đăng nhập'), 'test-user'); await user.type(screen.getByLabelText('Mật khẩu', { exact: true }), 'test-password');
  await user.click(screen.getByRole('button', { name: 'Đăng nhập' }));
  await screen.findByRole('heading', { name: path.startsWith('/admin') ? 'Xác minh khoản #31' : 'Thanh toán đơn NN-101' });
});
