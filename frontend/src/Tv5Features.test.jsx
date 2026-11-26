import React from 'react';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import App from './App';
import { guest, page, staff } from './test/fixtures';

const customer = { authenticated: true, user: { id: 7, username: 'khach', fullName: 'Minh Anh', roles: ['CUSTOMER'] } };

afterEach(() => {
  vi.unstubAllGlobals();
  sessionStorage.clear();
});

function mount(path, handlers = {}, session = guest) {
  const calls = [];
  vi.stubGlobal('fetch', vi.fn(async (url, options = {}) => {
    const method = options.method || 'GET';
    calls.push({ url, method, body: options.body });
    const handler = handlers[`${method} ${url}`];
    let body;
    let status = 200;
    if (handler) {
      const result = typeof handler === 'function' ? handler(options) : handler;
      body = result.body !== undefined ? result.body : result;
      status = typeof result.status === 'number' ? result.status : 200;
    } else if (url === '/api/session') body = session;
    else if (url === '/api/csrf') body = { token: 'csrf', headerName: 'X-CSRF-TOKEN' };
    else if (url === '/api/notifications/unread-count') body = { unreadCount: 0 };
    else if (url.startsWith('/api/commerce/recommendations?')) body = { items: [{ productId: 99, productName: 'Đèn sàn Bắc Âu', productSlug: 'den-san', categoryName: 'Đèn', defaultVariantId: 99, minPrice: '1800000.00', imageUrl: null, totalAvailable: 5, reason: 'Phối hợp đẹp cùng Ghế' }] };
    else throw new Error(`Unexpected HTTP ${method} ${url}`);
    return new Response(status === 204 ? null : JSON.stringify(body), {
      status,
      headers: { 'Content-Type': 'application/json' },
    });
  }));
  render(<MemoryRouter initialEntries={[path]}><App /></MemoryRouter>);
  return { user: userEvent.setup(), calls };
}

it('keeps TV5 content, engagement and admin pages reachable from the new navbar', async () => {
  const { user } = mount('/login', {}, staff);
  expect(await screen.findByRole('link', { name: 'Bài viết' })).toHaveAttribute('href', '/articles');
  await user.click(screen.getByRole('button', { name: 'Quản trị' }));
  for (const [name, path] of [['Thống kê', '/admin/dashboard'], ['Nội dung', '/admin/content'], ['Duyệt đánh giá', '/admin/reviews']]) {
    expect(screen.getByRole('link', { name })).toHaveAttribute('href', path);
  }
  await user.click(screen.getByRole('button', { name: 'Tài khoản' }));
  expect(screen.getByRole('link', { name: 'Yêu thích' })).toHaveAttribute('href', '/wishlist');
  expect(screen.getByRole('link', { name: 'Đánh giá' })).toHaveAttribute('href', '/reviews');
});

it('renders public articles list and detail without raw HTML execution', async () => {
  const summary = {
    id: 1,
    title: 'Chọn bàn trà gỗ sồi cho phòng khách nhỏ',
    slug: 'chon-ban-tra-go-soi',
    summary: 'Gợi ý kích thước và cách phối màu sáng.',
    coverImageUrl: '/images/ban-tra-oslo.jpg',
    status: 'PUBLISHED',
    authorName: 'Nếp Nhà',
    publishedAt: '2026-11-25T10:00:00Z',
    updatedAt: '2026-11-25T10:00:00Z',
  };
  const { user } = mount('/articles', {
    'GET /api/commerce/content/articles?query=&page=0&size=9': page([summary]),
    'GET /api/commerce/content/articles/chon-ban-tra-go-soi': {
      ...summary,
      content: 'Đoạn một giới thiệu kích thước.\n\n<script>alert(1)</script> được hiển thị như văn bản thường.',
    },
  });

  await screen.findByText('Chọn bàn trà gỗ sồi cho phòng khách nhỏ');
  await user.click(screen.getByRole('link', { name: /Chọn bàn trà gỗ sồi/ }));
  await screen.findByText(/Đoạn một giới thiệu kích thước/);
  expect(screen.getByText(/<script>alert\(1\)<\/script>/)).toBeInTheDocument();
});

it('guards TV5 admin dashboard for non-staff and displays net revenue and cache stats for staff', async () => {
  const first = mount('/admin/dashboard', {}, customer);
  await screen.findByRole('heading', { name: 'Khu vực dành cho nhân viên' });
  expect(first.calls.some(c => c.url.startsWith('/api/admin/dashboard'))).toBe(false);

  const summary = {
    days: 30,
    fromUtc: '2026-10-27T00:00:00Z',
    toUtc: '2026-11-26T00:00:00Z',
    revenue: { grossCollected: '15000000.00', totalRefunded: '2000000.00', netRevenue: '13000000.00', currency: 'VND' },
    totalOrders: 4,
    ordersByStatus: { COMPLETED: 3, PENDING_CONFIRMATION: 1 },
    topProducts: [{ productId: 10, productName: 'Sofa Modena', unitsSold: 3, grossMerchandise: '15000000.00' }],
    lowStockVariants: [{ warehouseId: 1, warehouseCode: 'MAIN', variantId: 20, sku: 'SOFA-KEM', productName: 'Sofa Modena', variantName: 'Kem', availableQuantity: 2 }],
    funnel: { PRODUCT_VIEW: 25, ADD_TO_CART: 6, ORDER_PLACED: 4 },
    cacheStats: { enabled: true, hits: 12, misses: 4, hitRatePercent: 75, entriesByNamespace: {} },
  };
  const { user, calls } = mount('/admin/dashboard', {
    'GET /api/admin/dashboard/summary?days=30': summary,
    'POST /api/admin/dashboard/cache/clear': { enabled: true, hits: 0, misses: 0, hitRatePercent: 0, entriesByNamespace: {} },
  }, staff);

  await screen.findByRole('heading', { name: /Bảng điều khiển/ });
  expect(await screen.findByText('13.000.000 ₫')).toBeInTheDocument();
  expect(screen.getByText('SOFA-KEM')).toBeInTheDocument();
  expect(screen.getByText('75%')).toBeInTheDocument();

  await user.click(screen.getByRole('button', { name: 'Xóa cache TV5' }));
  await screen.findByText(/Đã xóa cache TV5/);
  expect(calls.some(c => c.method === 'POST' && c.url === '/api/admin/dashboard/cache/clear')).toBe(true);
});

it('opens chat widget on demand and displays live product sources', async () => {
  const { user, calls } = mount('/articles', {
    'GET /api/commerce/content/articles?query=&page=0&size=9': page([]),
    'POST /api/commerce/chat': {
      conversationId: '11111111-1111-4111-8111-111111111111',
      aiConfigured: false,
      model: 'UNCONFIGURED',
      reply: 'Trợ lý AI hiện chưa được kích hoạt khóa API.',
      sources: [{
        productId: 1, productName: 'Bàn trà Oslo', sku: 'BT-OSLO',
        variantName: 'Gỗ sồi', categoryName: 'Bàn trà', price: '3200000.00', availableQuantity: 4, imageUrl: null,
      }],
      createdAt: '2026-11-26T00:00:00Z',
    },
  });

  await screen.findByText('Chưa có bài viết phù hợp');
  expect(calls.some(c => c.url.includes('/commerce/chat'))).toBe(false);

  await user.click(screen.getByRole('button', { name: 'Tư vấn nội thất' }));
  await user.type(screen.getByLabelText('Nhập câu hỏi tư vấn'), 'Tư vấn bàn trà dưới 5 triệu');
  await user.click(screen.getByRole('button', { name: 'Gửi' }));

  await screen.findByText(/Trợ lý AI hiện chưa được kích hoạt/);
  expect(screen.getByText(/Bàn trà Oslo/)).toBeInTheDocument();
});

it('allows authenticated customer to view wishlist and remove an item', async () => {
  const { user } = mount('/wishlist', {
    'GET /api/wishlist': [{
      productId: 5, productName: 'Ghế thư giãn Aalto', productSlug: 'ghe-aalto',
      categoryName: 'Ghế', imageUrl: '/images/ghe-aalto.jpg', defaultVariantId: 55,
      minPrice: '4500000.00', totalAvailable: 3, active: true, addedAt: '2026-11-25T10:00:00Z',
    }],
    'DELETE /api/wishlist/5': { status: 204, body: null },
  }, customer);

  await screen.findByText('Ghế thư giãn Aalto');
  expect(await screen.findByText('Đèn sàn Bắc Âu')).toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: 'Bỏ lưu' }));
  await screen.findByText('Danh sách yêu thích đang trống');
});
