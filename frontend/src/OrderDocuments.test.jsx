import React from 'react';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, expect, it, vi } from 'vitest';
import { MemoryRouter, Route, Routes, useNavigate } from 'react-router-dom';
import App from './App';
import { guest, staff } from './test/fixtures';
import { order } from './test/checkoutFixtures';
import { adminSummary } from './test/adminOrderFixtures';
import { SessionContext } from './lib/session';
import OrderPrintPage from './features/orders/OrderPrintPage';

const customer = { authenticated: true, user: { id: 15, username: 'customer', roles: ['CUSTOMER'] } };
const printable = { ...order, collectedAmount: '150000.00', refundedAmount: '10000.00', pendingRefundAmount: '20000.00', customerNote: '<img src=x onerror=alert(1)>', internalNote: 'PRIVATE STAFF NOTE' };
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });
function mount(path, session = customer, handler) {
  const calls = [];
  vi.stubGlobal('fetch', vi.fn(async (url, options = {}) => {
    calls.push({ url, ...options });
    if (url === '/api/session') return Response.json(session);
    if (url === '/api/notifications/unread-count') return Response.json({ unreadCount: 0 });
    const result = handler ? await handler(url, options) : Response.json(printable);
    return result;
  }));
  render(<MemoryRouter initialEntries={[path]}><App /></MemoryRouter>);
  return { calls, user: userEvent.setup() };
}
it.each([['/orders/101/print', customer, '/api/orders/101/print'], ['/admin/orders/101/print', staff, '/api/admin/orders/101/print']])('loads fresh safe print snapshot at %s and prints only on request', async (path, session, endpoint) => {
  const print = vi.spyOn(window, 'print').mockImplementation(() => {});
  const { calls, user } = mount(path, session);
  await screen.findByRole('heading', { name: 'Phiếu đơn hàng' });
  expect(calls.find(call => call.url === endpoint)).toMatchObject({ credentials: 'include', cache: 'no-store' });
  expect(screen.getByText('<img src=x onerror=alert(1)>')).toBeInTheDocument();
  expect(document.querySelector('.order-print-sheet img')).toBeNull();
  expect(screen.queryByText('PRIVATE STAFF NOTE')).not.toBeInTheDocument();
  expect(screen.getByText('Đã thu')).toBeInTheDocument();
  expect(screen.getByText('Đang chờ hoàn')).toBeInTheDocument();
  expect(print).not.toHaveBeenCalled();
  await user.click(screen.getByRole('button', { name: 'In / Lưu PDF' }));
  expect(print).toHaveBeenCalledOnce();
});
it.each([['/orders/101/print', guest, 'Đăng nhập để tiếp tục'], ['/admin/orders/101/print', customer, 'Khu vực dành cho nhân viên']])('guards %s before requesting any document', async (path, session, heading) => {
  const { calls } = mount(path, session);
  await screen.findByRole('heading', { name: heading });
  expect(calls.some(call => call.url.endsWith('/print'))).toBe(false);
});
it('shows a failed print read without a print button and retries explicitly', async () => {
  let reads = 0;
  const { user } = mount('/orders/101/print', customer, () => ++reads === 1 ? Response.json({ message: 'Chưa tải được phiếu.' }, { status: 503 }) : Response.json(printable));
  await screen.findByText('Chưa tải được phiếu.');
  expect(screen.queryByRole('button', { name: 'In / Lưu PDF' })).not.toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: 'Thử lại' }));
  await screen.findByRole('button', { name: 'In / Lưu PDF' });
});
it('aborts old actor/id reads and immediately hides the prior snapshot', async () => {
  const pending = [];
  const api = { request: (path, options) => new Promise(resolve => pending.push({ path, ...options, resolve })) };
  function SwitchOrder() { const navigate = useNavigate(); return <button onClick={() => navigate('/orders/102/print')}>Other order</button>; }
  function View({ session }) { return <SessionContext.Provider value={{ api, session }}><MemoryRouter initialEntries={['/orders/101/print']}><SwitchOrder /><Routes><Route path="/orders/:id/print" element={<OrderPrintPage />} /></Routes></MemoryRouter></SessionContext.Provider>; }
  const { rerender } = render(<View session={customer} />);
  pending[0].resolve(printable);
  await screen.findByText(order.orderNumber);
  await userEvent.click(screen.getByRole('button', { name: 'Other order' }));
  expect(pending[0].signal.aborted).toBe(true);
  expect(screen.queryByText(order.orderNumber)).not.toBeInTheDocument();
  rerender(<View session={{ ...customer, user: { ...customer.user, id: 99 } }} />);
  expect(pending[1].signal.aborted).toBe(true);
  pending[1].resolve({ ...printable, id: 102, orderNumber: 'OBSOLETE-ACTOR' });
  pending[2].resolve({ ...printable, id: 102, orderNumber: 'CURRENT-ACTOR' });
  await screen.findByText('CURRENT-ACTOR');
  expect(screen.queryByText('OBSOLETE-ACTOR')).not.toBeInTheDocument();
});
it('rejects malformed or mismatched print snapshots', async () => {
  mount('/orders/101/print', customer, () => Response.json({ ...printable, id: 102 }));
  await screen.findByText('Chưa đọc được phiếu đơn hàng hợp lệ.');
  expect(screen.queryByRole('button', { name: 'In / Lưu PDF' })).not.toBeInTheDocument();
});
it.each(['/orders/101/print', '/admin/orders/101/print'])('returns to %s after sign-in', async path => {
  const { user } = mount(path, guest, url => url === '/api/csrf' ? Response.json({ headerName: 'X-CSRF-TOKEN', token: 't' }) : url === '/api/session/login' ? Response.json(staff) : url === '/api/cart/merge' ? Response.json({ items: [] }) : Response.json(printable));
  await screen.findByRole('link', { name: path.startsWith('/admin') ? 'Đăng nhập tài khoản khác' : 'Đăng nhập', exact: true });
  await user.click(screen.getByRole('main').querySelector('a'));
  await user.type(screen.getByLabelText('Tên đăng nhập'), 'staff'); await user.type(screen.getByLabelText('Mật khẩu', { exact: true }), 'password');
  await user.click(screen.getByRole('button', { name: 'Đăng nhập', exact: true }));
  await screen.findByRole('heading', { name: 'Phiếu đơn hàng' });
});
it('exports applied URL filters and dates instead of a draft search, then revokes the download URL', async () => {
  const create = vi.fn(() => 'blob:test'); const revoke = vi.fn();
  vi.stubGlobal('URL', Object.assign(URL, { createObjectURL: create, revokeObjectURL: revoke }));
  vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
  const { calls, user } = mount('/admin/orders?status=PROCESSING&query=An%25_&fromDate=2026-11-01&toDate=2026-11-21', staff, url => url.includes('export.csv') ? new Response('\ufeff"Mã đơn"\r\n', { headers: { 'Content-Type': 'text/csv' } }) : Response.json({ items: [adminSummary], page: 0, size: 10, totalElements: 1, totalPages: 1 }));
  await screen.findByRole('link', { name: `Quản lý đơn ${order.orderNumber}` });
  expect(calls.some(call => call.url === '/api/admin/orders?page=0&size=10&status=PROCESSING&query=An%25_&fromDate=2026-11-01&toDate=2026-11-21')).toBe(true);
  await user.clear(screen.getByLabelText('Tìm đơn hàng')); await user.type(screen.getByLabelText('Tìm đơn hàng'), 'draft');
  await user.click(screen.getByRole('button', { name: 'Xuất CSV' }));
  await waitFor(() => expect(revoke).toHaveBeenCalledWith('blob:test'));
  expect(calls.some(call => call.url === '/api/admin/orders/export.csv?status=PROCESSING&query=An%25_&fromDate=2026-11-01&toDate=2026-11-21')).toBe(true);
  expect(create).toHaveBeenCalledOnce();
});
it('applies date drafts together and surfaces a failed export without a download', async () => {
  const { calls, user } = mount('/admin/orders', staff, url => url.includes('export.csv') ? Response.json({ code: 'EXPORT_TOO_LARGE', message: 'Thu hẹp bộ lọc để xuất tối đa 5000 đơn.' }, { status: 400 }) : Response.json({ items: [], page: 0, size: 10, totalElements: 0, totalPages: 0 }));
  await screen.findByRole('button', { name: 'Xuất CSV' });
  await user.type(screen.getByLabelText('Từ ngày (giờ Việt Nam)'), '2026-11-01');
  await user.type(screen.getByLabelText('Đến ngày (giờ Việt Nam)'), '2026-11-21');
  await user.click(screen.getByRole('button', { name: 'Tìm kiếm' }));
  await waitFor(() => expect(calls.some(call => call.url.endsWith('query=&fromDate=2026-11-01&toDate=2026-11-21'))).toBe(true));
  await user.click(screen.getByRole('button', { name: 'Xuất CSV' }));
  await screen.findByText('Thu hẹp bộ lọc để xuất tối đa 5000 đơn.');
});
