import React from 'react';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, expect, it, vi } from 'vitest';
import { MemoryRouter, useLocation } from 'react-router-dom';
import App from './App';
import { emptyCart, guest, staff } from './test/fixtures';

afterEach(() => { vi.unstubAllGlobals(); sessionStorage.clear(); });
const pages = [
  ['receipts', 'Nhập kho', 'Nhập hàng'],
  ['operations', 'Điều chỉnh và chuyển kho', 'Điều chỉnh & chuyển kho'],
  ['dispatch', 'Xuất kho giao hàng', 'Xuất kho theo đơn'],
  ['shipments', 'Theo dõi vận đơn', 'Theo dõi vận đơn'],
];
function CurrentPath() { const location = useLocation(); return <output aria-label="Đường dẫn hiện tại">{location.pathname}{location.search}</output>; }
function mount(path, session = guest) {
  vi.stubGlobal('fetch', vi.fn(async (url, options = {}) => {
    let body;
    if (url === '/api/session') body = session;
    else if (url === '/api/csrf') body = { token: 'csrf', headerName: 'X-CSRF-TOKEN' };
    else if (url === '/api/session/login') body = staff;
    else if (url === '/api/cart/merge' || url === '/api/cart') body = emptyCart;
    else if (url === '/api/account/addresses') body = [{ id: 8, label: 'Nhà riêng', recipientName: 'Minh An', phone: '0900000000', addressLine1: '12 Nguyễn Trãi', wardName: 'Phường 1', districtName: 'Quận 5', provinceName: 'TP. Hồ Chí Minh', countryCode: 'VN', isDefault: true }];
    else if (url === '/api/admin/inventory/warehouses') body = [];
    else if (url.startsWith('/api/commerce/catalog/variants?') || (url.startsWith('/api/admin/inventory/') && (options.method || 'GET') === 'GET')) body = { items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 };
    else throw new Error(`Unexpected HTTP ${options.method || 'GET'} ${url}`);
    return new Response(JSON.stringify(body), { headers: { 'Content-Type': 'application/json' } });
  }));
  render(<MemoryRouter initialEntries={[path]}><CurrentPath /><App /></MemoryRouter>);
  return userEvent.setup();
}

it.each(pages)('keeps the incoming %s inventory route behind the shared staff guard', async route => {
  mount(`/admin/inventory/${route}`);
  await screen.findByRole('heading', { name: 'Khu vực dành cho nhân viên' });
  expect(fetch.mock.calls.some(([url]) => url.startsWith('/api/admin/inventory/'))).toBe(false);
});

it('requires sign-in for account pages and loads the signed-in user address book', async () => {
  mount('/account');
  await screen.findByRole('heading', { name: 'Đăng nhập để tiếp tục' });
  expect(fetch.mock.calls.some(([url]) => url === '/api/account/addresses')).toBe(false);

  const user = mount('/account', staff);
  expect(await screen.findByRole('heading', { name: 'Tài khoản của bạn' })).toBeInTheDocument();
  await user.click(screen.getByRole('tab', { name: 'Sổ địa chỉ' }));
  expect(await screen.findByText('12 Nguyễn Trãi, Phường 1, Quận 5, TP. Hồ Chí Minh, Việt Nam')).toBeInTheDocument();
  expect(screen.getByText('Mặc định')).toBeInTheDocument();
});

it.each(pages)('returns to the intended %s inventory route after login without losing existing admin navigation', async (route, title, heading) => {
  const user = mount(`/admin/inventory/${route}`);
  await screen.findByRole('heading', { name: 'Khu vực dành cho nhân viên' });
  await user.click(screen.getByRole('link', { name: 'Đăng nhập tài khoản khác' }));
  await user.type(screen.getByLabelText('Tên đăng nhập'), 'staff');
  await user.type(screen.getByLabelText('Mật khẩu', { exact: true }), 'test-password');
  await user.click(screen.getByRole('button', { name: 'Đăng nhập' }));
  await waitFor(() => expect(screen.getByLabelText('Đường dẫn hiện tại')).toHaveTextContent(`/admin/inventory/${route}`));
  expect(screen.getByRole('heading', { name: `${heading}.` })).toBeInTheDocument();
  await waitFor(() => expect(document.title).toBe(`${title} · Nếp Nhà`));
  const nav = screen.getByRole('navigation', { name: 'Điều hướng chính' });
  await user.click(screen.getByRole('button', { name: 'Quản trị' }));
  expect(nav.querySelectorAll('#store-menu-admin a')).toHaveLength(14);
  expect(screen.getByRole('link', { name: 'Tổng quan quản trị' })).toHaveAttribute('href', '/admin');
  expect(screen.getByRole('link', { name: 'Sản phẩm & catalog' })).toHaveAttribute('href', '/admin/catalog');
  expect(screen.getByRole('link', { name: 'Quản lý đơn hàng' })).toHaveAttribute('href', '/admin/orders');
  expect(screen.getByRole('link', { name: 'Quản lý ưu đãi' })).toHaveAttribute('href', '/admin/coupons');
  await user.click(screen.getByRole('button', { name: 'Tài khoản' }));
  expect(screen.getByRole('link', { name: 'Hồ sơ & sổ địa chỉ' })).toHaveAttribute('href', '/account');
});
