import React from 'react';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import App from './App';
import { admin, cart, chair, coupon, emptyCart, guest, page, staff } from './test/fixtures';

afterEach(() => vi.unstubAllGlobals());
function mount(path, handlers = {}, session = guest) {
  const calls = [];
  vi.stubGlobal('fetch', vi.fn(async (url, options = {}) => {
    const method = options.method || 'GET';
    calls.push({ url, ...options, method });
    const handler = handlers[`${method} ${url}`];
    let body;
    let status = 200;
    if (handler) { const result = typeof handler === 'function' ? await handler(options) : handler; body = result.body ?? result; status = result.status ?? 200; }
    else if (url === '/api/session') body = session;
    else if (url === '/api/csrf') body = { token: 'csrf', headerName: 'X-CSRF-TOKEN' };
    else if (url === '/api/cart') body = cart;
    else if (url.startsWith('/api/catalog/categories?')) body = [{ id: 1, name: 'Ghế ăn', slug: 'demo-ghe-an' }];
    else if (url.startsWith('/api/catalog/products?')) body = page([{ id: 51, slug: 'demo-ghe-an-06-01', name: 'Ghế ăn Mộc An', shortDescription: 'Ghế ăn gọn nhẹ', categoryName: 'Ghế ăn', categorySlug: 'demo-ghe-an', variantId: 51, sku: 'NN-DEMO-06-01', price: '1890000.00', compareAtPrice: null, imageUrl: '/images/gallery-dining-room.jpg', availableQuantity: 12 }]);
    else if (url === '/api/catalog/products/demo-ghe-an-06-01') body = { id: 51, slug: 'demo-ghe-an-06-01', name: 'Ghế ăn Mộc An', shortDescription: 'Ghế ăn gọn nhẹ', description: 'Ghế ăn gọn nhẹ cho bữa cơm gia đình.', careInstructions: 'Lau bằng khăn mềm.', categoryName: 'Ghế ăn', categorySlug: 'demo-ghe-an', brandName: 'Nếp Nhà Studio', warrantyMonths: 12, assemblyRequired: false, images: [{ url: '/images/gallery-dining-room.jpg', altText: 'Ảnh minh họa Ghế ăn Mộc An' }], specifications: [{ name: 'Chiều rộng', value: '480', unit: 'mm' }], variants: [{ id: 51, sku: 'NN-DEMO-06-01', name: 'Mộc An', widthMm: 480, depthMm: 520, heightMm: 790, price: '1890000.00', compareAtPrice: null, leadTimeDays: 3, availableQuantity: 12, materials: ['Gỗ sồi'] }] };
    else if (url.startsWith('/api/commerce/catalog/variants')) body = page([chair]);
    else if (url === '/api/commerce/home/gallery') body = { items: [] };
    else if (url.startsWith('/api/commerce/content/banners')) body = [];
    else if (url.startsWith('/api/commerce/content/articles')) body = page([]);
    else if (url.startsWith('/api/commerce/products/') && url.includes('/reviews')) body = { productId: 51, averageRating: 0, totalReviews: 0, items: [], page: 0, size: 10, totalPages: 0 };
    else if (url.startsWith('/api/commerce/recommendations')) body = { items: [] };
    else if (url === '/api/wishlist' && method === 'GET') body = [];
    else if (url === '/api/tracking/events' && method === 'POST') { status = 202; body = null; }
    else if (url.startsWith('/api/admin/coupons?')) body = page([coupon]);
    else if (url.startsWith('/api/admin/users?')) body = page([]);
    else throw new Error(`Unexpected HTTP ${method} ${url}`);
    return new Response(status === 204 ? null : JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
  }));
  render(<MemoryRouter initialEntries={[path]}><App /></MemoryRouter>);
  return { user: userEvent.setup(), calls };
}

it('shows live catalog names, prices and image URLs on the home page', async () => {
  const featured = [
    { id: 11, slug: 'demo-sofa-01-04', name: 'Sofa vải Trầm Thu', categoryName: 'Sofa', sku: 'NN-DEMO-01-04', price: '13600000.00', imageUrl: '/images/catalog/NN-DEMO-01-04.jpg' },
    { id: 12, slug: 'demo-ban-tra-03-05', name: 'Bàn trà gỗ Bình Minh', categoryName: 'Bàn trà', sku: 'NN-DEMO-03-05', price: '4390000.00', imageUrl: '/images/catalog/NN-DEMO-03-05.jpg' },
    { id: 13, slug: 'demo-ghe-thu-gian-04-04', name: 'Ghế bành Trầm Thu', categoryName: 'Ghế thư giãn', sku: 'NN-DEMO-04-04', price: '4990000.00', imageUrl: '/images/catalog/NN-DEMO-04-04.jpg' },
  ];
  const { calls, user } = mount('/', { 'GET /api/catalog/products?size=100': page(featured) });
  expect(await screen.findByRole('heading', { name: 'Sofa vải Trầm Thu' })).toBeInTheDocument();
  expect(screen.getByText('13.600.000 ₫')).toBeInTheDocument();
  const photos = within(document.querySelector('.home-product-grid')).getAllByRole('img');
  expect(photos.map(photo => photo.getAttribute('src'))).toEqual(featured.map(item => item.imageUrl));
  expect(photos.map(photo => photo.getAttribute('src'))).not.toContain('/images/sofa-modena.jpg');
  expect(screen.getByRole('link', { name: /Sofa vải Trầm Thu/ })).toHaveAttribute('href', '/products/demo-sofa-01-04');
  expect(calls.some(call => call.url === '/api/cart')).toBe(false);
  await user.click(screen.getByRole('link', { name: 'Xem tất cả' }));
  expect(await screen.findByRole('heading', { name: 'Sale & tất cả sản phẩm.' })).toBeInTheDocument();
  expect(await screen.findByRole('heading', { name: 'Ghế ăn Mộc An' })).toBeInTheDocument();
  expect(calls.some(call => call.url === '/api/cart')).toBe(false);
});

it('filters Sale and opens a product detail with add-to-cart', async () => {
  const { user, calls } = mount('/sale', { 'POST /api/cart/items': cart });
  expect(await screen.findByRole('heading', { name: 'Ghế ăn Mộc An' })).toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: 'Ghế ăn' }));
  await waitFor(() => expect(calls.some(call => call.url.includes('/api/catalog/products?') && new URLSearchParams(call.url.split('?')[1]).get('category') === 'demo-ghe-an')).toBe(true));
  await user.click(screen.getByRole('link', { name: /Ghế ăn Mộc An/ }));
  expect(await screen.findByText('Ghế ăn gọn nhẹ cho bữa cơm gia đình.')).toBeInTheDocument();
  expect(screen.getByText('NN-DEMO-06-01')).toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: 'Thêm vào giỏ' }));
  await waitFor(() => expect(calls.some(call => call.url === '/api/cart/items' && JSON.parse(call.body).variantId === 51)).toBe(true));
  expect(screen.getByText(/Đã thêm 1 Ghế ăn Mộc An/, { selector: '.detail-notice' })).toBeInTheDocument();
});

it('keeps the product grid in place while a category filter is loading', async () => {
  let finishFilter;
  const delayed = new Promise(resolve => { finishFilter = resolve; });
  const filteredUrl = '/api/catalog/products?query=&category=demo-ghe-an&room=&sort=newest&page=0&size=12';
  const { user } = mount('/sale', { [`GET ${filteredUrl}`]: () => delayed });
  expect(await screen.findByRole('heading', { name: 'Ghế ăn Mộc An' })).toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: 'Ghế ăn' }));
  expect(screen.getByRole('heading', { name: 'Ghế ăn Mộc An' })).toBeInTheDocument();
  expect(screen.getByText('Đang cập nhật sản phẩm…')).toBeInTheDocument();
  finishFilter(page([{ id: 52, slug: 'demo-ghe-an-06-02', name: 'Ghế ăn Sương Mai', shortDescription: 'Ghế ăn sáng màu', categoryName: 'Ghế ăn', categorySlug: 'demo-ghe-an', variantId: 52, sku: 'NN-DEMO-06-02', price: '2790000.00', imageUrl: '/images/catalog/NN-DEMO-06-02.jpg', availableQuantity: 12 }]));
  expect(await screen.findByRole('heading', { name: 'Ghế ăn Sương Mai' })).toBeInTheDocument();
});

it('loads Home gallery images from the content API with accessible descriptions', async () => {
  const gallery = { items: [{ id: 91, title: 'Góc đọc sách', imageUrl: '/images/gallery-reading-corner.jpg', altText: 'Ghế thư giãn bên tủ sách gỗ' }] };
  const { calls } = mount('/', { 'GET /api/commerce/home/gallery': gallery });
  expect(await screen.findByRole('img', { name: 'Ghế thư giãn bên tủ sách gỗ' })).toHaveAttribute('src', '/images/gallery-reading-corner.jpg');
  expect(screen.getByRole('region', { name: 'Ảnh cảm hứng không gian sống' })).toBeInTheDocument();
  expect(calls.some(call => call.url === '/api/commerce/home/gallery')).toBe(true);
});

it('preserves TV5 content and customer links in the redesigned header', async () => {
  const { user } = mount('/cart', {}, staff);
  expect(await screen.findByRole('link', { name: 'Bài viết', exact: true })).toHaveAttribute('href', '/articles');
  await user.click(screen.getByRole('button', { name: 'Tài khoản', exact: true }));
  const account = within(document.getElementById('store-account-panel'));
  expect(account.getByRole('link', { name: 'Yêu thích', exact: true })).toHaveAttribute('href', '/wishlist');
  expect(account.getByRole('link', { name: 'Đánh giá', exact: true })).toHaveAttribute('href', '/reviews');
  await user.click(screen.getByRole('button', { name: 'Quản trị', exact: true }));
  const admin = within(document.getElementById('store-menu-admin'));
  expect(admin.getByRole('link', { name: 'Thống kê', exact: true })).toHaveAttribute('href', '/admin/dashboard');
  expect(admin.getByRole('link', { name: 'Nội dung', exact: true })).toHaveAttribute('href', '/admin/content');
  expect(admin.getByRole('link', { name: 'Duyệt đánh giá', exact: true })).toHaveAttribute('href', '/admin/reviews');
  expect(admin.queryByRole('link', { name: 'Quản lý tài khoản', exact: true })).not.toBeInTheDocument();
});

it('lands an admin on the management overview and hides storefront controls', async () => {
  const { user, calls } = mount('/login', {
    'POST /api/session/login': admin,
  });
  await user.type(await screen.findByLabelText('Tên đăng nhập'), 'admin');
  await user.type(screen.getByLabelText('Mật khẩu', { exact: true }), 'secret');
  await user.click(screen.getByRole('button', { name: 'Đăng nhập' }));

  expect(await screen.findByRole('heading', { name: 'Tổng quan quản trị.' })).toBeInTheDocument();
  expect(document.title).toBe('Tổng quan quản trị · Nếp Nhà');
  expect(screen.queryByRole('link', { name: 'Trang chủ', exact: true })).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Tìm kiếm sản phẩm' })).not.toBeInTheDocument();
  expect(screen.queryByRole('link', { name: 'Giỏ hàng' })).not.toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: 'Quản trị', exact: true }));
  expect(within(document.getElementById('store-menu-admin')).getByRole('link', { name: 'Quản lý tài khoản' })).toHaveAttribute('href', '/admin/users');
  expect(calls.some(call => call.url === '/api/cart')).toBe(false);
  expect(calls.some(call => call.url === '/api/cart/merge')).toBe(false);
});

it('redirects an admin away from customer storefront routes', async () => {
  const { calls } = mount('/cart', {}, admin);
  expect(await screen.findByRole('heading', { name: 'Tổng quan quản trị.' })).toBeInTheDocument();
  expect(calls.some(call => call.url === '/api/cart')).toBe(false);
});

it('lets admins lock and unlock accounts from the admin navigation', async () => {
  let customerStatus = 'ACTIVE';
  let customerRole = 'CUSTOMER';
  const handlers = {
    'GET /api/admin/users?query=&role=&status=&page=0&size=20': () => page([
      { id: 8, username: 'admin', fullName: 'Quản trị viên', email: 'admin@example.invalid', phone: null, status: 'ACTIVE', roles: ['ADMIN'] },
      { id: 9, username: 'demo.customer', fullName: 'Khách thử', email: 'customer@example.invalid', phone: '0900000000', status: customerStatus, roles: [customerRole] },
    ]),
    'PATCH /api/admin/users/9/status': options => {
      customerStatus = JSON.parse(options.body).status;
      return { status: 200, body: { id: 9, username: 'demo.customer', fullName: 'Khách thử', email: 'customer@example.invalid', phone: '0900000000', status: customerStatus, roles: [customerRole] } };
    },
    'PATCH /api/admin/users/9/role': options => {
      customerRole = JSON.parse(options.body).roleCode;
      return { status: 200, body: { id: 9, username: 'demo.customer', fullName: 'Khách thử', email: 'customer@example.invalid', phone: '0900000000', status: customerStatus, roles: [customerRole] } };
    },
  };
  const { user, calls } = mount('/admin/users', handlers, admin);
  expect(await screen.findByRole('heading', { name: /Quản lý tài khoản/ })).toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: 'Quản trị', exact: true }));
  expect(within(document.getElementById('store-menu-admin')).getByRole('link', { name: 'Quản lý tài khoản', exact: true })).toHaveAttribute('href', '/admin/users');

  let customerRow = await screen.findByRole('row', { name: /demo\.customer/ });
  expect(within(customerRow).queryByRole('button', { name: 'Chuyển thành STAFF' })).toBeInTheDocument();
  await user.click(within(customerRow).getByRole('button', { name: 'Chuyển thành STAFF' }));
  await user.click(screen.getByRole('button', { name: 'Xác nhận đổi vai trò' }));
  expect(await screen.findByText('Đã chuyển tài khoản demo.customer thành STAFF.')).toBeInTheDocument();
  customerRow = await screen.findByRole('row', { name: /demo\.customer/ });
  await user.click(within(customerRow).getByRole('button', { name: 'Chuyển thành CUSTOMER' }));
  await user.click(screen.getByRole('button', { name: 'Xác nhận đổi vai trò' }));
  expect(await screen.findByText('Đã chuyển tài khoản demo.customer thành CUSTOMER.')).toBeInTheDocument();
  expect(calls.filter(call => call.url === '/api/admin/users/9/role' && call.method === 'PATCH')
    .map(call => JSON.parse(call.body).roleCode)).toEqual(['STAFF', 'CUSTOMER']);

  customerRow = await screen.findByRole('row', { name: /demo\.customer/ });
  await user.click(within(customerRow).getByRole('button', { name: 'Khóa' }));
  expect(screen.getByRole('alert')).toHaveTextContent('không thể đăng nhập');
  await user.click(screen.getByRole('button', { name: 'Xác nhận' }));
  expect(await screen.findByText('Đã khóa tài khoản demo.customer.')).toBeInTheDocument();
  expect(calls.some(call => call.url === '/api/admin/users/9/status' && call.method === 'PATCH' && JSON.parse(call.body).status === 'LOCKED')).toBe(true);

  const lockedRow = await screen.findByRole('row', { name: /demo\.customer/ });
  await user.click(within(lockedRow).getByRole('button', { name: 'Mở khóa' }));
  await user.click(screen.getByRole('button', { name: 'Xác nhận' }));
  expect(await screen.findByText('Đã mở khóa tài khoản demo.customer.')).toBeInTheDocument();
  expect(calls.filter(call => call.url === '/api/admin/users/9/status' && call.method === 'PATCH').map(call => JSON.parse(call.body).status)).toEqual(['LOCKED', 'ACTIVE']);
});

it('searches the catalog from the header and keeps the query in the catalog field', async () => {
  const { user, calls } = mount('/');
  await user.click(screen.getByRole('button', { name: 'Tìm kiếm sản phẩm' }));
  await user.type(screen.getByLabelText('Tìm món đồ cho nhà bạn'), 'Sofa');
  await user.click(screen.getByRole('button', { name: 'Tìm kiếm', exact: true }));
  expect(await screen.findByLabelText('Tìm sản phẩm')).toHaveValue('Sofa');
  expect(screen.getByRole('heading', { name: 'Sale & tất cả sản phẩm.' })).toBeInTheDocument();
  await waitFor(() => expect(calls.some(call => call.url.includes('/api/catalog/products?') && new URLSearchParams(call.url.split('?')[1]).get('query') === 'Sofa')).toBe(true));
  expect(calls.some(call => call.url === '/api/cart')).toBe(false);
});

it('leaves cart for product search from the header', async () => {
  const { user, calls } = mount('/cart?query=Sofa#catalog');
  const field = await screen.findByLabelText('Tìm sản phẩm');
  await user.clear(field); await user.type(field, 'Bàn');
  await user.click(screen.getByRole('button', { name: 'Tìm kiếm', exact: true }));
  const lastQuery = () => new URLSearchParams(calls.filter(call => call.url.startsWith('/api/commerce/catalog/variants?')).at(-1).url.split('?')[1]).get('query');
  await waitFor(() => expect(lastQuery()).toBe('Bàn'));
  await user.click(screen.getByRole('button', { name: 'Tìm kiếm sản phẩm' }));
  await user.type(screen.getByLabelText('Tìm món đồ cho nhà bạn'), 'Sofa');
  await user.click(within(screen.getByRole('search')).getByRole('button', { name: 'Tìm kiếm', exact: true }));
  expect(await screen.findByRole('heading', { name: 'Sale & tất cả sản phẩm.' })).toBeInTheDocument();
  expect(screen.getByLabelText('Tìm sản phẩm')).toHaveValue('Sofa');
  expect(screen.queryByRole('heading', { name: 'Giỏ hàng của bạn.' })).not.toBeInTheDocument();
});

it('opens a room menu and filters the product listing through its category link', async () => {
  const { user, calls } = mount('/');
  await user.click(screen.getByRole('button', { name: 'Phòng khách' }));
  await user.click(screen.getByRole('link', { name: 'Bàn trà' }));
  expect(await screen.findByRole('heading', { name: 'Phòng khách.' })).toBeInTheDocument();
  await waitFor(() => expect(calls.some(call => call.url.includes('/api/catalog/products?') && new URLSearchParams(call.url.split('?')[1]).get('category') === 'demo-ban-tra' && new URLSearchParams(call.url.split('?')[1]).get('room') === 'phong-khach')).toBe(true));
  expect(calls.some(call => call.url === '/api/cart')).toBe(false);
});

it.each([['Phòng khách', 'phong-khach'], ['Phòng ăn', 'phong-an'], ['Phòng ngủ', 'phong-ngu']])('opens %s products without showing the cart', async (label, room) => {
  const { user, calls } = mount('/');
  await user.click(screen.getByRole('button', { name: label }));
  await user.click(screen.getByRole('link', { name: `Tất cả ${label.toLowerCase()}` }));
  expect(await screen.findByRole('heading', { name: `${label}.` })).toBeInTheDocument();
  await waitFor(() => expect(calls.some(call => call.url.includes('/api/catalog/products?') && new URLSearchParams(call.url.split('?')[1]).get('room') === room)).toBe(true));
  expect(screen.queryByRole('heading', { name: 'Giỏ hàng của bạn.' })).not.toBeInTheDocument();
  expect(calls.some(call => call.url === '/api/cart')).toBe(false);
});

it('rotates the Home gallery ring when the mouse enters and supports keyboard navigation', async () => {
  mount('/', { 'GET /api/commerce/home/gallery': { items: [
    { id: 1, title: 'Phòng khách', imageUrl: '/images/gallery-living-room.jpg', altText: 'Sofa kem' },
    { id: 2, title: 'Bàn ăn', imageUrl: '/images/gallery-dining-room.jpg', altText: 'Bàn gỗ' },
    { id: 3, title: 'Phòng ngủ', imageUrl: '/images/gallery-bedroom.jpg', altText: 'Giường gỗ' },
  ] } });
  const stage = await screen.findByRole('region', { name: 'Ảnh cảm hứng không gian sống' });
  expect(within(stage).getByRole('img', { name: 'Sofa kem' })).toBeInTheDocument();
  fireEvent.pointerEnter(stage, { pointerType: 'mouse' });
  expect(within(stage).getByRole('img', { name: 'Bàn gỗ' })).toBeInTheDocument();
  expect(stage.querySelector('.home-gallery-ring')).toHaveStyle({ transform: 'rotateY(-120deg)' });
  expect(screen.queryByRole('button', { name: 'Ảnh tiếp theo' })).not.toBeInTheDocument();
  fireEvent.keyDown(stage, { key: 'ArrowLeft' });
  expect(within(stage).getByRole('img', { name: 'Sofa kem' })).toBeInTheDocument();
  fireEvent.pointerDown(stage, { clientX: 160, pointerType: 'touch' });
  fireEvent.pointerUp(stage, { clientX: 80, pointerType: 'touch' });
  expect(within(stage).getByRole('img', { name: 'Bàn gỗ' })).toBeInTheDocument();
});

it('renders server totals, updates line quantity and supports removal', async () => {
  const { user, calls } = mount('/cart', {
    'PATCH /api/cart/items/8': { ...cart, itemCount: 3, subtotal: '3750000.00', items: [{ ...cart.items[0], quantity: 3, lineTotal: '3750000.00' }] },
    'DELETE /api/cart/items/8': emptyCart,
  });
  await within(await screen.findByRole('region', { name: 'Sản phẩm trong giỏ' })).findByRole('heading', { name: 'Ghế gỗ sồi' });
  expect(screen.getByTestId('cart-subtotal')).toHaveTextContent('2.500.000 ₫');
  await user.click(screen.getByRole('button', { name: 'Tăng số lượng Ghế gỗ sồi' }));
  await waitFor(() => expect(screen.getByTestId('cart-subtotal')).toHaveTextContent('3.750.000 ₫'));
  expect(JSON.parse(calls.find(c => c.method === 'PATCH').body)).toEqual({ quantity: 3 });
  await user.click(screen.getByRole('button', { name: 'Xóa Ghế gỗ sồi' }));
  await screen.findByText('Giỏ hàng đang chờ bạn');
});

it('searches the real catalog bridge and adds the chosen variant', async () => {
  const { user, calls } = mount('/cart', { 'POST /api/cart/items': cart }, guest);
  await screen.findByRole('button', { name: 'Thêm Ghế gỗ sồi vào giỏ' });
  await user.type(screen.getByLabelText('Tìm sản phẩm'), 'gỗ');
  await user.click(screen.getByRole('button', { name: 'Tìm kiếm' }));
  await waitFor(() => expect(calls.some(c => c.url.includes('query=g%E1%BB%97'))).toBe(true));
  await user.click(screen.getByRole('button', { name: 'Thêm Ghế gỗ sồi vào giỏ' }));
  await waitFor(() => expect(calls.some(c => c.method === 'POST' && c.url === '/api/cart/items')).toBe(true));
  expect(JSON.parse(calls.find(c => c.method === 'POST' && c.url === '/api/cart/items').body)).toEqual({ variantId: 12, quantity: 1 });
});

it('keeps excess-stock lines visible with server warnings and allows reducing quantity', async () => {
  mount('/cart', { 'GET /api/cart': { ...cart, warnings: [{ code: 'INSUFFICIENT_STOCK', message: 'Chỉ còn 1 sản phẩm trong kho.', itemId: 8 }], items: [{ ...cart.items[0], availableQuantity: 1, issues: ['INSUFFICIENT_STOCK'] }] } });
  await screen.findByText('Chỉ còn 1 sản phẩm trong kho.');
  expect(screen.getByRole('button', { name: 'Tăng số lượng Ghế gỗ sồi' })).toBeDisabled();
  expect(screen.getByRole('button', { name: 'Giảm số lượng Ghế gỗ sồi' })).toBeEnabled();
});

it('logs in, explicitly merges the guest cart, then shows the merged server state', async () => {
  const { user, calls } = mount('/login', { 'POST /api/session/login': staff, 'POST /api/cart/merge': cart });
  await screen.findByLabelText('Tên đăng nhập');
  await user.type(screen.getByLabelText('Tên đăng nhập'), 'staff');
  await user.type(screen.getByLabelText('Mật khẩu', { exact: true }), 'secret');
  await user.click(screen.getByRole('button', { name: 'Đăng nhập' }));
  await screen.findByRole('heading', { name: /Giỏ hàng của bạn/ });
  expect(calls.filter(c => c.url === '/api/cart/merge')).toHaveLength(1);
  await user.click(screen.getByRole('button', { name: 'Quản trị' }));
  expect(screen.getByRole('link', { name: 'Quản lý ưu đãi' })).toBeInTheDocument();
});

it('registers a customer, returns to login, and preserves the guest cart until sign-in', async () => {
  const customer = { authenticated: true, user: { id: 91, username: 'mai.an', fullName: 'Mai An', roles: ['CUSTOMER'] } };
  const { user, calls } = mount('/login', {
    'POST /api/session/register': { status: 201, body: { id: 91, username: 'mai.an', email: 'mai@example.com', fullName: 'Mai An' } },
    'POST /api/session/login': customer,
    'POST /api/cart/merge': cart,
  });
  await user.click(await screen.findByRole('link', { name: 'Đăng ký ngay' }));
  expect(screen.getByRole('heading', { name: 'Đăng ký' })).toBeInTheDocument();
  await user.type(screen.getByLabelText('Họ và tên'), 'Mai An');
  await user.type(screen.getByLabelText('Tên đăng nhập'), 'mai.an');
  await user.type(screen.getByLabelText('Email'), 'mai@example.com');
  await user.type(screen.getByLabelText('Mật khẩu', { exact: true }), 'secret-pass-123');
  await user.type(screen.getByLabelText('Nhập lại mật khẩu'), 'secret-pass-123');
  await user.click(screen.getByRole('button', { name: 'Tạo tài khoản' }));
  expect(await screen.findByText('Tài khoản đã được tạo. Đăng nhập để tiếp tục.')).toBeInTheDocument();
  expect(screen.getByLabelText('Tên đăng nhập')).toHaveValue('mai.an');
  expect(calls.filter(call => call.url === '/api/cart/merge')).toHaveLength(0);
  const registration = calls.find(call => call.url === '/api/session/register');
  expect(JSON.parse(registration.body)).toMatchObject({ username: 'mai.an', email: 'mai@example.com', password: 'secret-pass-123' });
  expect(registration.headers['X-CSRF-TOKEN']).toBe('csrf');
  await user.type(screen.getByLabelText('Mật khẩu', { exact: true }), 'secret-pass-123');
  await user.click(screen.getByRole('button', { name: 'Đăng nhập' }));
  await screen.findByRole('heading', { name: /Giỏ hàng của bạn/ });
  expect(calls.filter(call => call.url === '/api/cart/merge')).toHaveLength(1);
});

it('shows duplicate email on the registration form without losing entered details', async () => {
  const { user, calls } = mount('/register', {
    'POST /api/session/register': { status: 409, body: { code: 'ACCOUNT_EXISTS', message: 'Tên đăng nhập hoặc email đã được sử dụng.', fieldErrors: { email: 'Email đã được sử dụng.' } } },
  });
  await user.type(await screen.findByLabelText('Họ và tên'), 'Mai An');
  await user.type(screen.getByLabelText('Tên đăng nhập'), 'mai.an');
  await user.type(screen.getByLabelText('Email'), 'mai@example.com');
  await user.type(screen.getByLabelText('Mật khẩu', { exact: true }), 'secret-pass-123');
  await user.type(screen.getByLabelText('Nhập lại mật khẩu'), 'secret-pass-123');
  await user.click(screen.getByRole('button', { name: 'Tạo tài khoản' }));
  expect(await screen.findByText('Email đã được sử dụng.')).toBeInTheDocument();
  expect(screen.getByLabelText('Email')).toHaveValue('mai@example.com');
  expect(screen.getByLabelText('Email')).toHaveAttribute('aria-invalid', 'true');
  expect(calls.filter(call => call.url === '/api/session/register')).toHaveLength(1);
});

it('shows a recoverable merge failure after successful login without losing the authenticated session', async () => {
  const { user } = mount('/login', { 'POST /api/session/login': staff, 'POST /api/cart/merge': { status: 409, body: { code: 'QUANTITY_OVERFLOW', message: 'Số lượng sau khi gộp quá lớn.', fieldErrors: {} } } });
  await screen.findByLabelText('Tên đăng nhập');
  await user.type(screen.getByLabelText('Tên đăng nhập'), 'staff');
  await user.type(screen.getByLabelText('Mật khẩu', { exact: true }), 'secret');
  await user.click(screen.getByRole('button', { name: 'Đăng nhập' }));
  await screen.findByText('Số lượng sau khi gộp quá lớn.');
  expect(screen.getByRole('button', { name: 'Thử gộp lại' })).toBeEnabled();
  await user.click(screen.getByRole('button', { name: 'Quản trị' }));
  expect(screen.getByRole('link', { name: 'Quản lý ưu đãi' })).toBeInTheDocument();
});

it('protects the coupon screen for non-staff sessions', async () => {
  const { calls } = mount('/admin/coupons');
  await screen.findByText('Khu vực dành cho nhân viên');
  expect(calls.some(c => c.url.startsWith('/api/admin/coupons'))).toBe(false);
});

it('edits all coupon fields, preserves UTC precision and clears nullable limits', async () => {
  const { user, calls } = mount('/admin/coupons', { 'PATCH /api/admin/coupons/5': coupon }, staff);
  await screen.findByText('NHA10');
  await user.click(screen.getByRole('button', { name: 'Sửa NHA10' }));
  const form = screen.getByRole('form', { name: 'Thông tin mã ưu đãi' });
  await user.clear(within(form).getByLabelText('Giảm tối đa (₫)'));
  await user.clear(within(form).getByLabelText('Tổng lượt sử dụng'));
  await user.clear(within(form).getByLabelText('Lượt mỗi tài khoản'));
  await user.click(within(form).getByRole('button', { name: 'Lưu thay đổi' }));
  await waitFor(() => expect(calls.some(c => c.method === 'PATCH')).toBe(true));
  expect(JSON.parse(calls.find(c => c.method === 'PATCH').body)).toEqual({ code: 'NHA10', name: 'Ưu đãi tổ ấm', discountType: 'PERCENT', discountValue: '10.00', maxDiscountAmount: null, minimumOrderAmount: '1000000.00', totalUsageLimit: null, perUserLimit: null, startsAt: '2026-11-13T08:15:31.125Z', endsAt: '2026-12-13T08:15:31.125Z', isActive: true });
});

it('handles referenced coupon deletion as a conflict and offers deactivation', async () => {
  const { user } = mount('/admin/coupons', { 'DELETE /api/admin/coupons/5': { status: 409, body: { code: 'COUPON_IN_USE', message: 'Mã đã được sử dụng. Hãy tắt mã thay vì xóa.', fieldErrors: {} } } }, staff);
  await screen.findByText('NHA10');
  await user.click(screen.getByRole('button', { name: 'Xóa NHA10' }));
  await user.click(screen.getByRole('button', { name: 'Xác nhận xóa' }));
  await screen.findByText('Mã đã được sử dụng. Hãy tắt mã thay vì xóa.');
  expect(screen.getByRole('button', { name: 'Tắt NHA10' })).toBeEnabled();
});

it('previews a saved coupon using the server calculation, retaining decimal strings', async () => {
  const { user, calls } = mount('/admin/coupons', { 'POST /api/admin/coupons/5/preview': { discountAmount: '100000.00', remainingAmount: '900000.00', currency: 'VND' } }, staff);
  await screen.findByText('NHA10');
  await user.click(screen.getByRole('button', { name: 'Xem thử NHA10' }));
  await user.type(screen.getByLabelText('Tiền hàng để tính thử (₫)'), '1000000.00');
  await user.click(screen.getByRole('button', { name: 'Tính ưu đãi' }));
  await screen.findByText('900.000 ₫');
  expect(JSON.parse(calls.find(c => c.url.endsWith('/preview')).body)).toEqual({ merchandiseAmount: '1000000.00' });
});

it('requires confirmation before clearing the cart and replaces totals from the response', async () => {
  const { user, calls } = mount('/cart', { 'DELETE /api/cart/items': emptyCart });
  await screen.findByRole('button', { name: 'Xóa tất cả' });
  await user.click(screen.getByRole('button', { name: 'Xóa tất cả' }));
  expect(calls.some(c => c.method === 'DELETE')).toBe(false);
  await user.click(screen.getByRole('button', { name: 'Đồng ý xóa hết' }));
  await screen.findByText('Giỏ hàng đang chờ bạn');
  expect(screen.getByTestId('cart-subtotal')).toHaveTextContent('0 ₫');
});

it('creates a fixed-value coupon, normalizes its code and leaves optional limits null', async () => {
  const { user, calls } = mount('/admin/coupons', { 'POST /api/admin/coupons': { ...coupon, code: 'NHA_MOI', discountType: 'FIXED' } }, staff);
  await screen.findByText('NHA10');
  await user.click(screen.getByRole('button', { name: 'Tạo mã mới' }));
  const form = within(screen.getByRole('form', { name: 'Thông tin mã ưu đãi' }));
  await user.type(form.getByLabelText('Mã ưu đãi'), 'nha_moi');
  await user.selectOptions(form.getByLabelText('Hình thức giảm'), 'FIXED');
  await user.type(form.getByLabelText('Giá trị giảm (₫)'), '150000.25');
  fireEvent.change(form.getByLabelText('Bắt đầu'), { target: { value: '2027-01-01T10:00' } });
  fireEvent.change(form.getByLabelText('Kết thúc'), { target: { value: '2027-02-01T10:00' } });
  await user.click(form.getByLabelText('Cho phép sử dụng mã'));
  await user.click(form.getByRole('button', { name: 'Tạo mã ưu đãi' }));
  await screen.findByText('Đã lưu mã NHA_MOI.');
  const body = JSON.parse(calls.find(c => c.url === '/api/admin/coupons' && c.method === 'POST').body);
  expect(body).toMatchObject({ code: 'NHA_MOI', name: null, discountType: 'FIXED', discountValue: '150000.25', minimumOrderAmount: '0.00', maxDiscountAmount: null, totalUsageLimit: null, perUserLimit: null, isActive: false });
  expect(new Date(body.startsAt).getHours()).toBe(10);
});

it('retains coupon input and exposes server field validation beside its field', async () => {
  const { user } = mount('/admin/coupons', { 'PATCH /api/admin/coupons/5': { status: 400, body: { code: 'VALIDATION_ERROR', message: 'Kiểm tra thông tin ưu đãi.', fieldErrors: { discountValue: 'Phần trăm phải nhỏ hơn hoặc bằng 100.' } } } }, staff);
  await screen.findByText('NHA10');
  await user.click(screen.getByRole('button', { name: 'Sửa NHA10' }));
  const form = within(screen.getByRole('form', { name: 'Thông tin mã ưu đãi' }));
  await user.clear(form.getByLabelText('Giá trị giảm (%)'));
  await user.type(form.getByLabelText('Giá trị giảm (%)'), '101');
  await user.click(form.getByRole('button', { name: 'Lưu thay đổi' }));
  await screen.findByText('Phần trăm phải nhỏ hơn hoặc bằng 100.');
  expect(form.getByLabelText('Giá trị giảm (%)')).toHaveValue('101');
  expect(form.getByLabelText('Giá trị giảm (%)')).toHaveAttribute('aria-invalid', 'true');
  expect(form.getByLabelText('Giá trị giảm (%)')).toHaveAccessibleDescription('Phần trăm phải nhỏ hơn hoặc bằng 100.');
});

it('deactivates with a partial PATCH instead of overwriting coupon fields', async () => {
  const { user, calls } = mount('/admin/coupons', { 'PATCH /api/admin/coupons/5': { ...coupon, isActive: false } }, staff);
  await screen.findByText('NHA10');
  await user.click(screen.getByRole('button', { name: 'Tắt NHA10' }));
  await screen.findByText('Đã tắt mã NHA10.');
  expect(JSON.parse(calls.find(c => c.method === 'PATCH').body)).toEqual({ isActive: false });
});

it('sends coupon search and pagination as bounded server queries', async () => {
  const { user, calls } = mount('/admin/coupons', { 'GET /api/admin/coupons?query=&page=0&size=20': { ...page([coupon]), totalElements: 21, totalPages: 2 } }, staff);
  await screen.findByText('NHA10');
  await user.click(screen.getByRole('button', { name: 'Trang sau' }));
  await waitFor(() => expect(calls.some(c => c.url === '/api/admin/coupons?query=&page=1&size=20')).toBe(true));
  await user.type(screen.getByLabelText('Tìm mã ưu đãi'), 'NHA');
  await user.click(screen.getByRole('button', { name: 'Tìm kiếm' }));
  await waitFor(() => expect(calls.some(c => c.url === '/api/admin/coupons?query=NHA&page=0&size=20')).toBe(true));
});
