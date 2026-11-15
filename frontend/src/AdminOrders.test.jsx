import React from 'react';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import App from './App';
import { Status } from './features/orders/OrderParts';
import { emptyCart, guest, staff } from './test/fixtures';
import { adminOrder, adminSummary } from './test/adminOrderFixtures';

afterEach(() => vi.unstubAllGlobals());
function mount(path = '/admin/orders/101', handlers = {}, session = staff) {
  const calls = [];
  vi.stubGlobal('fetch', vi.fn(async (url, options = {}) => {
    const method = options.method || 'GET'; calls.push({ url, ...options, method });
    const handler = handlers[`${method} ${url}`];
    let result;
    if (handler) result = typeof handler === 'function' ? await handler(options) : handler;
    else if (url === '/api/session') result = session;
    else if (url === '/api/csrf') result = { token: 'csrf', headerName: 'X-CSRF-TOKEN' };
    else if (url === '/api/admin/orders/101') result = adminOrder;
    else if (url.startsWith('/api/admin/orders?')) result = { items: [adminSummary], page: 0, size: 10, totalElements: 1, totalPages: 1 };
    else throw new Error(`Unexpected HTTP ${method} ${url}`);
    return new Response(JSON.stringify(result.body ?? result), { status: Object.hasOwn(result, 'body') ? result.status : 200, headers: { 'Content-Type': 'application/json' } });
  }));
  render(<MemoryRouter initialEntries={[path]}><App /></MemoryRouter>);
  return { user: userEvent.setup(), calls };
}

it.each(['/admin/orders', '/admin/orders/101'])('keeps guest and customer sessions out of staff route %s', async path => {
  const { calls } = mount(path, {}, guest);
  await screen.findByRole('heading', { name: 'Khu vực dành cho nhân viên' });
  expect(calls.some(call => call.url.startsWith('/api/admin/orders'))).toBe(false);
});
it('does not expose admin order navigation or data to a customer session', async () => {
  const { calls } = mount('/admin/orders', {}, { authenticated: true, user: { id: 15, username: 'customer', fullName: 'An', roles: ['CUSTOMER'] } });
  await screen.findByRole('heading', { name: 'Khu vực dành cho nhân viên' });
  expect(screen.queryByRole('link', { name: 'Quản lý đơn hàng' })).not.toBeInTheDocument();
  expect(calls.some(call => call.url.startsWith('/api/admin/orders'))).toBe(false);
});

it('searches literal text and filters/pages the staff list with exact server queries', async () => {
  const firstPage = { items: [adminSummary], page: 0, size: 10, totalElements: 11, totalPages: 2 };
  const { user, calls } = mount('/admin/orders', { 'GET /api/admin/orders?page=0&size=10&status=&query=': firstPage });
  await screen.findByRole('link', { name: `Quản lý đơn ${adminOrder.orderNumber}` });
  expect(screen.getByText('customer@example.test')).toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: 'Trang sau' }));
  await waitFor(() => expect(calls.some(call => call.url === '/api/admin/orders?page=1&size=10&status=&query=')).toBe(true));
  await user.type(screen.getByLabelText('Tìm đơn hàng'), 'An%_');
  await user.click(screen.getByRole('button', { name: 'Tìm kiếm' }));
  await waitFor(() => expect(calls.some(call => call.url === '/api/admin/orders?page=0&size=10&status=&query=An%25_')).toBe(true));
  await user.selectOptions(screen.getByLabelText('Trạng thái đơn hàng'), 'PROCESSING');
  await waitFor(() => expect(calls.some(call => call.url === '/api/admin/orders?page=0&size=10&status=PROCESSING&query=An%25_')).toBe(true));
});

it('shows historical guest recipients without inventing a customer account', async () => {
  mount('/admin/orders', { 'GET /api/admin/orders?page=0&size=10&status=&query=': { items: [{ ...adminSummary, customer: { id: null, username: null, fullName: null, email: null } }], page: 0, size: 10, totalElements: 1, totalPages: 1 } });
  await screen.findByText('Khách không có tài khoản');
  expect(screen.getByText(/Người nhận: Nguyễn An/)).toBeInTheDocument();
});

it('shows snapshots, selected assembly service, public history and blocked evidence without fake payment/shipping actions', async () => {
  mount('/admin/orders/101', { 'GET /api/admin/orders/101': { ...adminOrder, status: 'PROCESSING', allowedTransitions: ['CANCELLED'], blockedTransitions: [{ toStatus: 'COMPLETED', reasons: ['Chưa đủ tiền đã thu.', 'Chưa giao đủ sản phẩm.'] }] } });
  await screen.findByRole('heading', { name: adminOrder.orderNumber });
  expect(screen.getByText('Chưa đủ tiền đã thu.')).toBeInTheDocument();
  expect(screen.getByText('Chưa giao đủ sản phẩm.')).toBeInTheDocument();
  expect(screen.getByText('Có chọn dịch vụ lắp đặt.')).toBeInTheDocument();
  expect(screen.getByText('Không cần lắp đặt theo kỹ thuật.')).toBeInTheDocument();
  expect(screen.getByText('60 × 55 × 80 cm')).toBeInTheDocument();
  expect(screen.getByRole('region', { name: 'Lịch sử hiển thị cho khách hàng' })).toContainElement(screen.getByText('Đơn hàng đã tạo'));
  expect(screen.queryByRole('button', { name: 'Hoàn tất đơn' })).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: /thu tiền|đã giao/i })).not.toBeInTheDocument();
});

it('sends the observed fromStatus and the public message for an allowed confirmation', async () => {
  const next = { ...adminOrder, status: 'CONFIRMED', allowedTransitions: ['PROCESSING', 'CANCELLED'], history: [...adminOrder.history, { fromStatus: 'PENDING_CONFIRMATION', toStatus: 'CONFIRMED', note: 'Cửa hàng đã xác nhận.', changedAt: '2026-11-15T03:00:00Z' }] };
  const { user, calls } = mount('/admin/orders/101', { 'POST /api/admin/orders/101/transitions': next });
  await screen.findByRole('button', { name: 'Xác nhận đơn' });
  await user.click(screen.getByRole('button', { name: 'Xác nhận đơn' }));
  await user.type(screen.getByLabelText('Thông điệp cho khách hàng'), 'Cửa hàng đã xác nhận.');
  await user.click(screen.getByRole('button', { name: 'Xác nhận chuyển trạng thái' }));
  await screen.findByRole('button', { name: 'Bắt đầu xử lý' });
  expect(JSON.parse(calls.find(call => call.method === 'POST' && call.url.endsWith('/transitions')).body)).toEqual({ fromStatus: 'PENDING_CONFIRMATION', toStatus: 'CONFIRMED', note: 'Cửa hàng đã xác nhận.' });
  expect(within(screen.getByRole('region', { name: 'Lịch sử hiển thị cho khách hàng' })).getByText('Cửa hàng đã xác nhận.')).toBeInTheDocument();
});

it('requires a public cancellation reason and a separate final confirmation before cancellation', async () => {
  const { user, calls } = mount('/admin/orders/101', { 'GET /api/admin/orders/101': { ...adminOrder, status: 'PROCESSING', allowedTransitions: ['CANCELLED'] }, 'POST /api/admin/orders/101/transitions': { ...adminOrder, status: 'CANCELLED', allowedTransitions: [], canCancel: false } });
  await screen.findByRole('button', { name: 'Hủy đơn' }); await user.click(screen.getByRole('button', { name: 'Hủy đơn' }));
  expect(screen.getByRole('button', { name: 'Kiểm tra yêu cầu hủy' })).toBeDisabled();
  await user.type(screen.getByLabelText('Lý do hủy đơn (khách hàng nhìn thấy)'), 'Khách yêu cầu đổi kích thước.');
  await user.click(screen.getByRole('button', { name: 'Kiểm tra yêu cầu hủy' }));
  expect(calls.some(call => call.url.endsWith('/transitions'))).toBe(false);
  await user.click(screen.getByRole('button', { name: 'Xác nhận hủy đơn' }));
  await screen.findByText('Không còn thao tác chuyển trạng thái cho đơn này.');
  expect(JSON.parse(calls.find(call => call.url.endsWith('/transitions')).body)).toEqual({ fromStatus: 'PROCESSING', toStatus: 'CANCELLED', note: 'Khách yêu cầu đổi kích thước.' });
});

it('preserves the public message on a stale status conflict and requires an explicit refresh', async () => {
  const { user, calls } = mount('/admin/orders/101', { 'POST /api/admin/orders/101/transitions': { status: 409, body: { code: 'ORDER_STATE_CHANGED', message: 'Trạng thái đã đổi, hãy tải lại.', fieldErrors: {} } } });
  await screen.findByRole('button', { name: 'Xác nhận đơn' }); await user.click(screen.getByRole('button', { name: 'Xác nhận đơn' }));
  await user.type(screen.getByLabelText('Thông điệp cho khách hàng'), 'Thông điệp đang soạn');
  await user.click(screen.getByRole('button', { name: 'Xác nhận chuyển trạng thái' }));
  await screen.findByText('Trạng thái đã đổi, hãy tải lại.');
  expect(screen.getByLabelText('Thông điệp cho khách hàng')).toHaveValue('Thông điệp đang soạn');
  expect(screen.getByRole('button', { name: 'Xác nhận chuyển trạng thái' })).toBeDisabled();
  expect(screen.getByRole('button', { name: 'Tải lại trạng thái để kiểm tra' })).toBeEnabled();
  expect(calls.filter(call => call.url.endsWith('/transitions'))).toHaveLength(1);
});

it('writes only the separate internal note and the exact expected baseline, including clearing to null', async () => {
  const { user, calls } = mount('/admin/orders/101', { 'PATCH /api/admin/orders/101/internal-note': { ...adminOrder, internalNote: null } });
  const input = await screen.findByLabelText('Ghi chú nội bộ');
  await user.clear(input); await user.click(screen.getByRole('button', { name: 'Lưu ghi chú nội bộ' }));
  await screen.findByText('Đã lưu ghi chú nội bộ.');
  expect(JSON.parse(calls.find(call => call.url.endsWith('/internal-note')).body)).toEqual({ internalNote: null, expectedInternalNote: 'Kho A xác nhận hàng' });
  expect(calls.some(call => call.url.endsWith('/transitions'))).toBe(false);
});

it('keeps a conflicting internal-note draft through explicit reload and rebases only after a deliberate choice', async () => {
  let reads = 0; let writes = 0;
  const { user, calls } = mount('/admin/orders/101', {
    'GET /api/admin/orders/101': () => ++reads === 1 ? adminOrder : { ...adminOrder, internalNote: 'Ghi chú của đồng nghiệp' },
    'PATCH /api/admin/orders/101/internal-note': () => ++writes === 1 ? { status: 409, body: { code: 'INTERNAL_NOTE_CHANGED', message: 'Ghi chú đã được người khác sửa.', fieldErrors: {} } } : { ...adminOrder, internalNote: 'Bản nháp của tôi' },
  });
  const input = await screen.findByLabelText('Ghi chú nội bộ'); await user.clear(input); await user.type(input, 'Bản nháp của tôi');
  await user.click(screen.getByRole('button', { name: 'Lưu ghi chú nội bộ' }));
  await screen.findByText('Ghi chú đã được người khác sửa.');
  expect(input).toHaveValue('Bản nháp của tôi');
  expect(screen.getByRole('button', { name: 'Lưu ghi chú nội bộ' })).toBeDisabled();
  await user.click(screen.getByRole('button', { name: 'Tải ghi chú mới nhất' }));
  await screen.findByText('Ghi chú của đồng nghiệp');
  expect(input).toHaveValue('Bản nháp của tôi');
  await user.click(screen.getByRole('button', { name: 'Tiếp tục với bản nháp' }));
  await user.click(screen.getByRole('button', { name: 'Lưu ghi chú nội bộ' }));
  await screen.findByText('Đã lưu ghi chú nội bộ.');
  const last = calls.filter(call => call.url.endsWith('/internal-note')).at(-1);
  expect(JSON.parse(last.body)).toEqual({ internalNote: 'Bản nháp của tôi', expectedInternalNote: 'Ghi chú của đồng nghiệp' });
});

it('does not replace a dirty internal-note draft when a transition returns another staff edit', async () => {
  const { user } = mount('/admin/orders/101', { 'POST /api/admin/orders/101/transitions': { ...adminOrder, status: 'CONFIRMED', allowedTransitions: ['PROCESSING', 'CANCELLED'], internalNote: 'Thay đổi từ đồng nghiệp' } });
  const input = await screen.findByLabelText('Ghi chú nội bộ'); await user.clear(input); await user.type(input, 'Bản nháp chưa lưu');
  await user.click(screen.getByRole('button', { name: 'Xác nhận đơn' }));
  await user.click(screen.getByRole('button', { name: 'Xác nhận chuyển trạng thái' }));
  await screen.findByRole('button', { name: 'Bắt đầu xử lý' });
  expect(input).toHaveValue('Bản nháp chưa lưu');
});

it.each([['NOT_CREATED', 'Chưa tạo'], ['NOT_REQUIRED', 'Không yêu cầu'], ['PARTIALLY_PAID', 'Đã thu một phần'], ['EXCEPTION', 'Cần kiểm tra'], ['EXPIRED', 'Đã hết hạn']])('localizes persisted evidence state %s', (value, label) => {
  render(<Status value={value} />); expect(screen.getByText(label)).toBeInTheDocument();
});

it('returns to the intended admin detail after sign-in and merge', async () => {
  const { user } = mount('/admin/orders/101', { 'POST /api/session/login': staff, 'POST /api/cart/merge': emptyCart }, guest);
  await screen.findByRole('heading', { name: 'Khu vực dành cho nhân viên' });
  await user.click(screen.getByRole('link', { name: 'Đăng nhập tài khoản khác' }));
  await user.type(screen.getByLabelText('Tên đăng nhập'), 'staff'); await user.type(screen.getByLabelText('Mật khẩu', { exact: true }), 'test-password');
  await user.click(screen.getByRole('button', { name: 'Đăng nhập' }));
  await screen.findByRole('heading', { name: adminOrder.orderNumber });
});
