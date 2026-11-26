import React from 'react';
import { render, screen, waitFor, within, cleanup } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import App from './App';
import { staff, guest, emptyCart } from './test/fixtures';

const customer = { authenticated: true, user: { id: 32, username: 'owner', roles: ['CUSTOMER'] } };
const summary = { orderId: 42, orderNumber: 'NN42', orderStatus: 'COMPLETED', items: [{ id: 13, sku: 'OAK', productName: 'Ghế sồi', quantity: 3, deliveredQuantity: 2, claimedQuantity: 1, returnableQuantity: 1 }], shipments: [{ id: 71, status: 'DELIVERED', installationRequired: true, installationStatus: 'PENDING', installationCompletedAt: null, canCompleteInstallation: true }], returns: [{ id: 9, returnNumber: 'RT9', status: 'APPROVED', resolution: 'REFUND', reason: 'Lỗi bề mặt', requestedAt: '2026-11-19T00:00:00Z', canDecide: false, canReceive: true, items: [{ id: 90, orderItemId: 13, sku: 'OAK', productName: 'Ghế sồi', quantity: 1, receivedQuantity: 0, restockedQuantity: 0 }] }] };
const operations = { pool: { active: 1, idle: 3, total: 4, awaiting: 0, max: 10, minIdle: 2, available: true }, jobs: { enabled: true, expiryEnabled: true, notificationEnabled: true, confirmationTtlSeconds: 86400 }, outbox: { pending: 2, retry: 1, dead: 0, sent: 8 }, expiryAttention: [] };
afterEach(() => { cleanup(); vi.unstubAllGlobals(); sessionStorage.clear(); });
function mount(path, session = customer, handler = () => undefined) {
  const calls = [];
  vi.stubGlobal('fetch', vi.fn(async (url, options = {}) => {
    calls.push({ url, ...options });
    const custom = await handler(url, options);
    if (custom instanceof Response) return custom;
    let body = custom;
    if (body === undefined) {
      if (url === '/api/session') body = session;
      else if (url === '/api/csrf') body = { token: 'csrf', headerName: 'X-CSRF-TOKEN' };
      else if (url === '/api/notifications/unread-count') body = { unreadCount: 2 };
      else if (url.startsWith('/api/notifications?')) body = { items: [{ id: 8, orderId: 42, orderNumber: 'NN42', type: 'RETURN_APPROVED', title: 'Đã duyệt trả hàng', body: 'Cửa hàng đã duyệt yêu cầu.', href: '/orders/42', createdAt: '2026-11-19T00:00:00Z', readAt: null }], page: 0, size: 10, totalElements: 1, totalPages: 1, unreadCount: 2 };
      else if (url === '/api/notifications/8/read') body = { id: 8, readAt: '2026-11-19T01:00:00Z' };
      else if (url === '/api/admin/operations') body = operations;
      else if (url === '/api/admin/operations/expiry/run') body = { scanned: 3, expired: 2, skipped: 1, failed: 0 };
      else if (url === '/api/admin/operations/notifications/run') body = { scanned: 2, delivered: 2, skipped: 0, failed: 0 };
      else if (url === '/api/admin/inventory/warehouses') body = [{ id: 11, name: 'Kho thật', active: true }];
      else if (url === '/api/orders/42/returns' || url === '/api/admin/aftercare/orders/42') body = summary;
      else throw new Error(`Unexpected ${options.method} ${url}`);
    }
    return new Response(JSON.stringify(body), { headers: { 'Content-Type': 'application/json' } });
  }));
  const result = render(<MemoryRouter initialEntries={[path]}><App /></MemoryRouter>);
  return { ...result, calls, user: userEvent.setup() };
}
it('protects notifications and operations without making private API requests', async () => {
  const { calls } = mount('/notifications', guest);
  await screen.findByRole('heading', { name: 'Đăng nhập để tiếp tục' });
  expect(calls.some(c => c.url.startsWith('/api/notifications'))).toBe(false);
});
it('shows the owner inbox, marks read through CSRF, and preserves customer navigation', async () => {
  const { user, calls } = mount('/notifications');
  await screen.findByText('Đã duyệt trả hàng');
  expect(within(screen.getByRole('navigation', { name: 'Điều hướng chính' })).getAllByRole('link')).toHaveLength(7);
  await user.click(screen.getByRole('button', { name: 'Đánh dấu đã đọc' }));
  await waitFor(() => expect(calls.some(c => c.url === '/api/notifications/8/read' && c.headers['X-CSRF-TOKEN'] === 'csrf')).toBe(true));
  expect(screen.getByRole('link', { name: 'Xem đơn NN42' })).toHaveAttribute('href', '/orders/42');
});
it('guards staff operations and only triggers bounded jobs explicitly', async () => {
  const { user, calls } = mount('/admin/operations', staff);
  await screen.findByRole('heading', { name: 'Vận hành hệ thống' });
  expect(await screen.findByText('86400 giây')).toBeInTheDocument();
  expect(calls.filter(c => c.method === 'POST')).toHaveLength(0);
  await user.click(screen.getByRole('button', { name: 'Chạy kiểm tra hết hạn COD' }));
  await screen.findByText('Đã hết hạn: 2');
  expect(within(screen.getByRole('navigation', { name: 'Điều hướng chính' })).getAllByRole('link')).toHaveLength(19);
});
it('uses delivered returnable quantities and keeps exact actor-scoped request across an uncertain reload', async () => {
  let attempt;
  const handler = (url, options) => { if (url === '/api/orders/42/returns' && options.method === 'POST') { attempt = { body: JSON.parse(options.body), key: options.headers['Idempotency-Key'] }; return new Response('{', { status: 201 }); } };
  const first = mount('/orders/42/returns', customer, handler);
  await screen.findByRole('heading', { name: 'Trả hàng đơn NN42' });
  const quantity = screen.getByLabelText('Số lượng trả Ghế sồi');
  expect(quantity).toHaveAttribute('max', '1');
  await first.user.type(quantity, '1');
  await first.user.type(screen.getByLabelText('Lý do trả hàng'), 'Lỗi bề mặt');
  await first.user.click(screen.getByRole('button', { name: 'Gửi yêu cầu trả hàng' }));
  await screen.findByRole('button', { name: 'Thử lại đúng yêu cầu đã gửi' });
  expect(attempt.body).toEqual({ reason: 'Lỗi bề mặt', items: [{ orderItemId: 13, quantity: 1 }] });
  first.unmount();
  let replay;
  const second = mount('/orders/42/returns', customer, (url, options) => { if (url === '/api/orders/42/returns' && options.method === 'POST') { replay = { body: JSON.parse(options.body), key: options.headers['Idempotency-Key'] }; return summary; } });
  await second.user.click(await screen.findByRole('button', { name: 'Thử lại đúng yêu cầu đã gửi' }));
  await screen.findByText('Đã ghi nhận yêu cầu trả hàng.');
  expect(replay).toEqual(attempt);
});
it('receives only explicit actual return lines in a server-provided warehouse with attestation', async () => {
  let body;
  const { user } = mount('/admin/orders/42/aftercare', staff, (url, options) => { if (url === '/api/admin/aftercare/returns/9/receive') { body = JSON.parse(options.body); return { ...summary, returns: [{ ...summary.returns[0], status: 'RECEIVED', canReceive: false }] }; } });
  await screen.findByRole('heading', { name: 'Hậu mãi đơn NN42' });
  await user.selectOptions(screen.getByLabelText('Kho nhận RT9'), '11');
  await user.type(screen.getByLabelText('Thực nhận Ghế sồi RT9'), '1');
  await user.type(screen.getByLabelText('Nhập lại kho Ghế sồi RT9'), '1');
  expect(screen.getByRole('button', { name: 'Ghi nhận nhận hàng RT9' })).toBeDisabled();
  await user.click(screen.getByLabelText('Tôi đã kiểm đếm hàng thực nhận RT9'));
  await user.click(screen.getByRole('button', { name: 'Ghi nhận nhận hàng RT9' }));
  await screen.findByText('Đã ghi nhận bằng chứng hậu mãi.');
  expect(body).toEqual({ warehouseId: 11, items: [{ returnItemId: 90, receivedQuantity: 1, restockQuantity: 1, conditionNote: '' }], acknowledged: true });
});
it.each(['/admin/operations', '/admin/orders/42/aftercare'])('blocks customer access to %s without exposing protected data', async path => {
  const { calls } = mount(path);
  await screen.findByRole('heading', { name: 'Khu vực dành cho nhân viên' });
  expect(calls.some(c => c.url.startsWith('/api/admin/'))).toBe(false);
});
it('keeps installation proof frozen across authentication failure and replays after reload even if capability is gone', async () => {
  let firstProof;
  const first = mount('/admin/orders/42/aftercare', staff, (url, options) => {
    if (url === '/api/admin/aftercare/shipments/71/installation') { firstProof = JSON.parse(options.body); return new Response(JSON.stringify({ code: 'UNAUTHORIZED', message: 'Hết phiên' }), { status: 401 }); }
  });
  await first.user.type(await screen.findByLabelText('Chứng từ lắp đặt #71'), 'DEMO_INSTALL_71');
  await first.user.type(screen.getByLabelText('Ghi chú lắp đặt nội bộ #71'), 'Private installation note');
  await first.user.click(screen.getByLabelText('Tôi xác nhận đã thực hiện lắp đặt vận đơn #71'));
  await first.user.click(screen.getByRole('button', { name: 'Hoàn tất lắp đặt vận đơn #71' }));
  await screen.findByRole('button', { name: 'Thử lại đúng yêu cầu đã gửi' });
  expect(screen.getByLabelText('Chứng từ lắp đặt #71')).toBeDisabled();
  first.unmount();
  let replay;
  const second = mount('/admin/orders/42/aftercare', staff, (url, options) => {
    if (url === '/api/admin/aftercare/orders/42') return { ...summary, shipments: [{ ...summary.shipments[0], canCompleteInstallation: false, installationStatus: 'COMPLETED' }] };
    if (url === '/api/admin/aftercare/shipments/71/installation') { replay = JSON.parse(options.body); return summary; }
  });
  await second.user.click(await screen.findByRole('button', { name: 'Thử lại đúng yêu cầu đã gửi' }));
  await screen.findByText('Đã ghi nhận bằng chứng hậu mãi.');
  expect(replay).toEqual(firstProof);
});
it('isolates saved return attempts from another authenticated actor', async () => {
  sessionStorage.setItem('tv3.aftercare.99.order.42.request', JSON.stringify({ pending: { key: 'foreign-key', body: { reason: 'private', items: [{ orderItemId: 13, quantity: 1 }] } }, draft: { reason: 'private', quantities: { 13: 1 } } }));
  mount('/orders/42/returns');
  await screen.findByLabelText('Lý do trả hàng');
  expect(screen.queryByText('private')).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Thử lại đúng yêu cầu đã gửi' })).not.toBeInTheDocument();
});
it('only offers retry for a server-listed DEAD event and sends the reviewed reason', async () => {
  let retry;
  const { user } = mount('/admin/operations', staff, (url, options) => {
    if (url === '/api/admin/operations') return { ...operations, deadEvents: [{ id: 52, orderId: 42, orderNumber: 'NN42', type: 'RETURN_RECEIVED', attempts: 5, lastError: 'DELIVERY_FAILED', updatedAt: '2026-11-19T00:00:00Z' }] };
    if (url === '/api/admin/operations/notifications/52/retry') { retry = JSON.parse(options.body); return operations; }
  });
  await user.type(await screen.findByLabelText('Lý do xếp lại sự kiện #52'), 'Đã kiểm tra kết nối');
  await user.click(screen.getByRole('button', { name: 'Xếp lại sự kiện #52' }));
  await waitFor(() => expect(retry).toEqual({ reason: 'Đã kiểm tra kết nối' }));
});
it('requires fresh server state before explicitly releasing a conflicting saved request', async () => {
  let submitted = false;
  const { user, calls } = mount('/orders/42/returns', customer, (url, options) => {
    if (url === '/api/orders/42/returns' && options.method === 'POST') { submitted = true; return new Response(JSON.stringify({ code: 'RETURN_QUANTITY_EXCEEDED', message: 'Số lượng đã thay đổi' }), { status: 409 }); }
    if (url === '/api/orders/42/returns' && submitted) return { ...summary, items: [{ ...summary.items[0], returnableQuantity: 0 }] };
  });
  await user.type(await screen.findByLabelText('Số lượng trả Ghế sồi'), '1');
  await user.type(screen.getByLabelText('Lý do trả hàng'), 'Lỗi bề mặt');
  await user.click(screen.getByRole('button', { name: 'Gửi yêu cầu trả hàng' }));
  await screen.findByText('Số lượng đã thay đổi');
  expect(screen.queryByRole('button', { name: 'Tôi đã đối chiếu trạng thái, mở lại biểu mẫu' })).not.toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: 'Tải lại trạng thái để đối chiếu' }));
  await user.click(await screen.findByRole('button', { name: 'Tôi đã đối chiếu trạng thái, mở lại biểu mẫu' }));
  await screen.findByText('Không còn số lượng đủ điều kiện yêu cầu trả hàng.');
  expect(calls.filter(c => c.url === '/api/orders/42/returns' && c.method === 'POST')).toHaveLength(1);
});
it.each([
  ['/notifications', 'Thông báo của bạn', 'Thông báo'],
  ['/admin/operations', 'Vận hành hệ thống', 'Vận hành hệ thống'],
  ['/orders/42/returns', 'Trả hàng đơn NN42', 'Trả hàng'],
  ['/admin/orders/42/aftercare', 'Hậu mãi đơn NN42', 'Hậu mãi đơn hàng'],
])('returns to authorized %s after login with the matching document title', async (path, heading, title) => {
  const { user } = mount(path, guest, url => url === '/api/session/login' ? staff : url === '/api/cart/merge' ? emptyCart : undefined);
  const staffOnly = path.startsWith('/admin/');
  await screen.findByRole('heading', { name: staffOnly ? 'Khu vực dành cho nhân viên' : 'Đăng nhập để tiếp tục' });
  await user.click(within(screen.getByRole('main')).getByRole('link', { name: staffOnly ? 'Đăng nhập tài khoản khác' : 'Đăng nhập', exact: true }));
  await user.type(screen.getByLabelText('Tên đăng nhập'), 'staff');
  await user.type(screen.getByLabelText('Mật khẩu', { exact: true }), 'test-password');
  await user.click(screen.getByRole('button', { name: 'Đăng nhập', exact: true }));
  await screen.findByRole('heading', { name: heading, exact: true });
  await waitFor(() => expect(document.title).toBe(`${title} · Nếp Nhà`));
});
