import React from 'react';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import App from './App';
import { cart, guest, staff, emptyCart } from './test/fixtures';
import { address, order, orderSummary, quote } from './test/checkoutFixtures';

afterEach(() => { vi.unstubAllGlobals(); sessionStorage.clear(); });
function mount(path = '/checkout', handlers = {}, session = staff) {
  const calls = [];
  vi.stubGlobal('fetch', vi.fn(async (url, options = {}) => {
    const method = options.method || 'GET'; calls.push({ url, ...options, method });
    const handler = handlers[`${method} ${url}`];
    let result;
    if (handler) result = typeof handler === 'function' ? await handler(options) : handler;
    else if (url === '/api/session') result = session;
    else if (url === '/api/csrf') result = { token: 'csrf', headerName: 'X-CSRF-TOKEN' };
    else if (url === '/api/cart') result = cart;
    else if (url === '/api/checkout/addresses') result = { items: [address] };
    else if (url === '/api/orders/101') result = order;
    else if (url === '/api/tracking/events') result = { status: 202, body: {} };
    else throw new Error(`Unexpected HTTP ${method} ${url}`);
    return new Response(JSON.stringify(result.body ?? result), { status: Object.hasOwn(result, 'body') ? result.status : 200, headers: { 'Content-Type': 'application/json' } });
  }));
  const rendered = render(<MemoryRouter initialEntries={[path]}><App /></MemoryRouter>);
  return { ...rendered, user: userEvent.setup(), calls };
}
async function requestQuote(user) { await screen.findByRole('button', { name: 'Xem trước đơn hàng' }); await user.click(screen.getByRole('button', { name: 'Xem trước đơn hàng' })); await screen.findByTestId('checkout-total'); }
async function accept(user) { await user.click(screen.getByLabelText('Tôi đã kiểm tra thông tin và tổng tiền.')); }

it.each([401, 403, 429])('preserves the original unresolved attempt on middleware HTTP %s', async status => {
  const first = mount('/checkout', { 'POST /api/checkout/quote': quote, 'POST /api/checkout/orders': () => { throw new TypeError('Response lost'); } });
  await requestQuote(first.user); await accept(first.user); await first.user.click(screen.getByRole('button', { name: 'Đặt hàng' }));
  await screen.findByRole('button', { name: 'Thử lại yêu cầu đặt hàng' });
  const original = first.calls.find(call => call.url === '/api/checkout/orders'); first.unmount();
  const next = mount('/checkout', { 'GET /api/cart': emptyCart, 'POST /api/checkout/orders': { status, body: { code: status === 401 ? 'AUTHENTICATION_REQUIRED' : status === 403 ? 'CSRF_INVALID' : 'RATE_LIMITED', message: 'Thử lại sau khi xác thực.' } } });
  await next.user.click(await screen.findByRole('button', { name: 'Thử lại yêu cầu đặt hàng' }));
  await screen.findByText(/Thử lại sau khi xác thực\./);
  expect(sessionStorage.length).toBe(1);
  expect(screen.getByRole('button', { name: 'Thử lại yêu cầu đặt hàng' })).toBeInTheDocument();
  expect(next.calls.find(call => call.url === '/api/checkout/orders').headers['Idempotency-Key']).toBe(original.headers['Idempotency-Key']);
});

it('recovers the same order after lost response, expired session, reload and re-login', async () => {
  const first = mount('/checkout', { 'POST /api/checkout/quote': quote, 'POST /api/checkout/orders': () => { throw new TypeError('Response lost'); } });
  await requestQuote(first.user); await accept(first.user); await first.user.click(screen.getByRole('button', { name: 'Đặt hàng' }));
  await screen.findByRole('button', { name: 'Thử lại yêu cầu đặt hàng' });
  const original = first.calls.find(call => call.url === '/api/checkout/orders'); first.unmount();
  const expired = mount('/checkout', { 'GET /api/cart': emptyCart, 'POST /api/checkout/orders': { status: 401, body: { code: 'AUTHENTICATION_REQUIRED', message: 'Phiên đã hết hạn.' } } });
  await expired.user.click(await screen.findByRole('button', { name: 'Thử lại yêu cầu đặt hàng' })); await screen.findByText(/Phiên đã hết hạn\./); expired.unmount();
  const login = mount('/checkout', { 'POST /api/session/login': staff, 'POST /api/cart/merge': emptyCart, 'GET /api/cart': emptyCart, 'POST /api/checkout/orders': { status: 201, body: order } }, guest);
  await login.user.click(await within(await screen.findByRole('main')).findByRole('link', { name: 'Đăng nhập', exact: true }));
  await login.user.type(screen.getByLabelText('Tên đăng nhập', { exact: true }), 'staff'); await login.user.type(screen.getByLabelText('Mật khẩu', { exact: true }), 'test-password');
  await login.user.click(screen.getByRole('button', { name: 'Đăng nhập', exact: true }));
  await login.user.click(await screen.findByRole('button', { name: 'Thử lại yêu cầu đặt hàng' })); await screen.findByRole('heading', { name: order.orderNumber });
  const replay = login.calls.find(call => call.url === '/api/checkout/orders'); expect(replay.headers['Idempotency-Key']).toBe(original.headers['Idempotency-Key']); expect(replay.body).toBe(original.body);
});

it('restores submitted form data when a replay definitively reports no order was created', async () => {
  const first = mount('/checkout', { 'POST /api/checkout/quote': quote, 'POST /api/checkout/orders': () => { throw new TypeError('Response lost'); } });
  await first.user.type(await screen.findByLabelText('Ghi chú cho cửa hàng'), 'Giữ nguyên ghi chú');
  await requestQuote(first.user); await accept(first.user); await first.user.click(screen.getByRole('button', { name: 'Đặt hàng' })); await screen.findByRole('button', { name: 'Thử lại yêu cầu đặt hàng' }); first.unmount();
  const retry = mount('/checkout', { 'POST /api/checkout/orders': { status: 409, body: { code: 'STOCK_UNAVAILABLE', message: 'Không đủ hàng để tạo đơn.' } } });
  await retry.user.click(await screen.findByRole('button', { name: 'Thử lại yêu cầu đặt hàng' })); await screen.findByText('Không đủ hàng để tạo đơn.');
  expect(screen.getByLabelText('Ghi chú cho cửa hàng')).toHaveValue('Giữ nguyên ghi chú'); expect(screen.queryByTestId('checkout-total')).not.toBeInTheDocument(); expect(sessionStorage.length).toBe(0);
});

it.each(['/checkout', '/orders', '/orders/101'])('requires authentication before loading private route %s', async path => {
  const { calls } = mount(path, {}, guest);
  await screen.findByRole('heading', { name: 'Đăng nhập để tiếp tục' });
  expect(calls.map(call => call.url)).toEqual(['/api/session']);
});

it('quotes a saved address and places a deliberate order with server amounts and an idempotency header', async () => {
  const { user, calls } = mount('/checkout', { 'POST /api/checkout/quote': quote, 'POST /api/checkout/orders': { status: 201, body: order } });
  await requestQuote(user);
  expect(screen.getByTestId('checkout-total')).toHaveTextContent('2.550.000 ₫');
  expect(screen.getByRole('button', { name: 'Đặt hàng' })).toBeDisabled();
  await accept(user); await user.click(screen.getByRole('button', { name: 'Đặt hàng' }));
  await screen.findByRole('heading', { name: order.orderNumber });
  const placed = calls.find(call => call.url === '/api/checkout/orders');
  expect(placed.headers['Idempotency-Key']).toMatch(/^[A-Za-z0-9_-]{8,64}$/);
  expect(JSON.parse(placed.body)).toEqual({ cartId: 4, addressId: 9, shippingAddress: null, couponCode: null, paymentMethod: 'COD', assemblyRequested: false, customerNote: null, quoteFingerprint: 'quote-v1' });
});

it('submits manual address, coupon, assembly and bank transfer and invalidates the quote after edits', async () => {
  const { user, calls } = mount('/checkout', { 'POST /api/checkout/quote': quote });
  await screen.findByLabelText('Nhập địa chỉ mới'); await user.click(screen.getByLabelText('Nhập địa chỉ mới'));
  for (const [label, value] of [['Họ tên người nhận', 'Trần Bình'], ['Số điện thoại', '0911111111'], ['Địa chỉ', '25 Đường Sen'], ['Phường / xã', 'Phường 2'], ['Quận / huyện', 'Quận 3'], ['Tỉnh / thành phố', 'Hồ Chí Minh']]) await user.type(screen.getByLabelText(label, { exact: true }), value);
  await user.type(screen.getByLabelText('Mã ưu đãi'), 'nha10');
  await user.type(screen.getByLabelText('Ghi chú cho cửa hàng'), 'Gọi trước');
  await user.click(screen.getByLabelText('Chuyển khoản ngân hàng'));
  await user.click(screen.getByLabelText('Yêu cầu lắp đặt'));
  await requestQuote(user);
  const body = JSON.parse(calls.find(call => call.url === '/api/checkout/quote').body);
  expect(body).toMatchObject({ addressId: null, shippingAddress: { recipientName: 'Trần Bình', phone: '0911111111', addressLine1: '25 Đường Sen', countryCode: 'VN' }, couponCode: 'NHA10', customerNote: 'Gọi trước', paymentMethod: 'BANK_TRANSFER', assemblyRequested: true });
  await user.type(screen.getByLabelText('Ghi chú cho cửa hàng'), ' khi đến');
  expect(screen.queryByRole('button', { name: 'Đặt hàng' })).not.toBeInTheDocument();
}, 12000);

it('persists the exact uncertain order attempt and replays the same key/body after a reload even if the cart is now empty', async () => {
  const first = mount('/checkout', { 'POST /api/checkout/quote': quote, 'POST /api/checkout/orders': () => { throw new TypeError('connection lost'); } });
  await first.user.type(await screen.findByLabelText('Ghi chú cho cửa hàng'), 'Gọi trước khi giao yêu cầu đang chờ');
  await requestQuote(first.user); await accept(first.user); await first.user.click(screen.getByRole('button', { name: 'Đặt hàng' }));
  await screen.findByRole('button', { name: 'Thử lại yêu cầu đặt hàng' });
  const original = first.calls.find(call => call.url === '/api/checkout/orders'); first.unmount();
  const next = mount('/checkout', { 'GET /api/cart': emptyCart, 'POST /api/checkout/orders': { status: 201, body: order } });
  await screen.findByRole('button', { name: 'Thử lại yêu cầu đặt hàng' });
  expect(screen.getByText('Gọi trước khi giao yêu cầu đang chờ')).toBeInTheDocument();
  await next.user.click(screen.getByRole('button', { name: 'Thử lại yêu cầu đặt hàng' }));
  await screen.findByRole('heading', { name: order.orderNumber });
  const retried = next.calls.find(call => call.url === '/api/checkout/orders');
  expect(retried.headers['Idempotency-Key']).toBe(original.headers['Idempotency-Key']);
  expect(retried.body).toBe(original.body);
  expect(sessionStorage.length).toBe(0);
});

it('requires a new review and a new key after a QUOTE_CHANGED response', async () => {
  let attempts = 0;
  const { user, calls } = mount('/checkout', { 'POST /api/checkout/quote': quote, 'POST /api/checkout/orders': () => ++attempts === 1 ? { status: 409, body: { code: 'QUOTE_CHANGED', message: 'Giá đã thay đổi.', fieldErrors: {}, quote: { ...quote, quoteFingerprint: 'quote-v2', grandTotal: '2600000.00' } } } : { status: 201, body: order } });
  await requestQuote(user); await accept(user); await user.click(screen.getByRole('button', { name: 'Đặt hàng' }));
  await screen.findByText('Giá đã thay đổi.');
  expect(screen.getByTestId('checkout-total')).toHaveTextContent('2.600.000 ₫');
  expect(screen.getByRole('button', { name: 'Đặt hàng' })).toBeDisabled();
  await accept(user); await user.click(screen.getByRole('button', { name: 'Đặt hàng' }));
  await screen.findByRole('heading', { name: order.orderNumber });
  const placed = calls.filter(call => call.url === '/api/checkout/orders');
  expect(placed[1].headers['Idempotency-Key']).not.toBe(placed[0].headers['Idempotency-Key']);
  expect(JSON.parse(placed[1].body).quoteFingerprint).toBe('quote-v2');
});

it('displays checkout field errors next to manual address inputs and preserves entries', async () => {
  const { user } = mount('/checkout', { 'GET /api/checkout/addresses': { items: [] }, 'POST /api/checkout/quote': { status: 400, body: { code: 'INVALID_ADDRESS', message: 'Kiểm tra địa chỉ.', fieldErrors: { 'shippingAddress.phone': 'Số điện thoại chưa hợp lệ.' } } } });
  await screen.findByLabelText('Họ tên người nhận');
  for (const [label, value] of [['Họ tên người nhận', 'An'], ['Số điện thoại', '123'], ['Địa chỉ', '12 Sen'], ['Phường / xã', 'P1'], ['Quận / huyện', 'Q3'], ['Tỉnh / thành phố', 'HCM']]) await user.type(screen.getByLabelText(label, { exact: true }), value);
  await user.click(screen.getByRole('button', { name: 'Xem trước đơn hàng' }));
  await waitFor(() => expect(screen.getByLabelText('Số điện thoại')).toHaveAccessibleDescription('Số điện thoại chưa hợp lệ.'));
  expect(screen.getByLabelText('Số điện thoại')).toHaveValue('123');
  expect(screen.getByLabelText('Số điện thoại')).toHaveAccessibleDescription('Số điện thoại chưa hợp lệ.');
});

it('filters and paginates orders with exact server status values', async () => {
  const result = { items: [orderSummary], page: 0, size: 10, totalElements: 11, totalPages: 2 };
  const { user, calls } = mount('/orders', { 'GET /api/orders?page=0&size=10&status=': result, 'GET /api/orders?page=1&size=10&status=': { ...result, page: 1 }, 'GET /api/orders?page=0&size=10&status=CANCELLED': { ...result, items: [], totalPages: 0, totalElements: 0 } });
  await screen.findByRole('link', { name: `Xem đơn ${order.orderNumber}` });
  await user.click(screen.getByRole('button', { name: 'Trang sau' }));
  await waitFor(() => expect(calls.some(call => call.url === '/api/orders?page=1&size=10&status=')).toBe(true));
  await user.selectOptions(screen.getByLabelText('Trạng thái đơn hàng'), 'CANCELLED');
  await screen.findByText('Chưa có đơn hàng phù hợp');
  expect(calls.some(call => call.url === '/api/orders?page=0&size=10&status=CANCELLED')).toBe(true);
});

it('renders saved order snapshots, payment/shipment/refund state and cancellation history separately', async () => {
  const cancelled = { ...order, status: 'CANCELLED', canCancel: false, cancelReason: 'Thay đổi kế hoạch', cancelledAt: '2026-11-14T04:00:00Z', payments: [{ ...order.payments[0], status: 'SUCCEEDED', paidAt: '2026-11-14T03:30:00Z' }], shipments: [{ id: 4, status: 'CANCELLED', trackingNumber: 'NN123', carrier: 'Nếp Nhà' }], refunds: [{ id: 2, paymentId: 1, status: 'PENDING', amount: '2550000.00', currency: 'VND', reason: 'Hủy đơn đã thu tiền' }], history: [...order.history, { fromStatus: 'PENDING_CONFIRMATION', toStatus: 'CANCELLED', note: 'Thay đổi kế hoạch', changedAt: '2026-11-14T04:00:00Z' }] };
  mount('/orders/101', { 'GET /api/orders/101': cancelled });
  await screen.findByRole('heading', { name: order.orderNumber });
  expect(within(screen.getByRole('region', { name: 'Thanh toán' })).getByText('Đã thu tiền')).toBeInTheDocument();
  expect(within(screen.getByRole('region', { name: 'Hoàn tiền' })).getByText('Đang chờ')).toBeInTheDocument();
  expect(screen.getByText('60 × 55 × 80 cm')).toBeInTheDocument();
  expect(screen.getByText('NN123')).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Hủy đơn hàng' })).not.toBeInTheDocument();
});

it('requires a cancellation reason and shows the updated order returned by the server', async () => {
  const { user, calls } = mount('/orders/101', { 'POST /api/orders/101/cancel': { ...order, status: 'CANCELLED', canCancel: false, cancelReason: 'Đổi kích thước', cancelledAt: '2026-11-14T04:00:00Z' } });
  await screen.findByRole('button', { name: 'Hủy đơn hàng' }); await user.click(screen.getByRole('button', { name: 'Hủy đơn hàng' }));
  expect(screen.getByRole('button', { name: 'Xác nhận hủy đơn' })).toBeDisabled();
  await user.type(screen.getByLabelText('Lý do hủy đơn'), 'Đổi kích thước');
  await user.click(screen.getByRole('button', { name: 'Xác nhận hủy đơn' }));
  await waitFor(() => expect(screen.queryByRole('button', { name: 'Hủy đơn hàng' })).not.toBeInTheDocument());
  expect(JSON.parse(calls.find(call => call.url.endsWith('/cancel')).body)).toEqual({ reason: 'Đổi kích thước' });
  expect(screen.getByText('Đổi kích thước')).toBeInTheDocument();
});

it('distinguishes a completed refund from a successful payment and renders a tax code without a company name', async () => {
  mount('/orders/101', { 'GET /api/orders/101': { ...order, shippingAddress: { ...order.shippingAddress, companyName: null, taxCode: '0100000000' }, refunds: [{ id: 2, paymentId: 1, status: 'SUCCEEDED', amount: '100000.00', currency: 'VND', reason: 'Hoàn tiền đã xác minh' }] } });
  await screen.findByRole('heading', { name: order.orderNumber });
  expect(within(screen.getByRole('region', { name: 'Hoàn tiền' })).getByText('Đã hoàn tiền')).toBeInTheDocument();
  expect(screen.getByText('MST: 0100000000')).toBeInTheDocument();
});
