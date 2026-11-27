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
    else if (url === '/api/tracking/events' && method === 'POST') { status = 202; body = null; }
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
    cacheStats: { enabled: true, backend: 'REDIS', hits: 12, misses: 4, hitRatePercent: 75, entriesByNamespace: {} },
  };
  const { user, calls } = mount('/admin/dashboard', {
    'GET /api/admin/dashboard/summary?days=30': summary,
    'POST /api/admin/dashboard/cache/clear': { enabled: true, backend: 'REDIS', hits: 0, misses: 0, hitRatePercent: 0, entriesByNamespace: {} },
  }, staff);

  await screen.findByRole('heading', { name: /Bảng điều khiển/ });
  expect(await screen.findByText('13.000.000 ₫')).toBeInTheDocument();
  expect(screen.getByText('SOFA-KEM')).toBeInTheDocument();
  expect(screen.getByText('Redis')).toBeInTheDocument();
  expect(screen.getByText('75%')).toBeInTheDocument();

  await user.click(screen.getByRole('button', { name: 'Xóa cache TV5' }));
  await screen.findByText(/Đã xóa cache TV5/);
  expect(calls.some(c => c.method === 'POST' && c.url === '/api/admin/dashboard/cache/clear')).toBe(true);
});

it('opens chat widget on demand, loads history and links sources to product detail', async () => {
  const { user, calls } = mount('/articles', {
    'GET /api/commerce/content/articles?query=&page=0&size=9': page([]),
    'GET /api/commerce/chat': [{
      publicId: '22222222-2222-4222-8222-222222222222',
      title: 'Hỏi sofa phòng khách',
      status: 'OPEN',
      updatedAt: '2026-11-26T00:00:00Z',
    }],
    'GET /api/commerce/chat/22222222-2222-4222-8222-222222222222': {
      publicId: '22222222-2222-4222-8222-222222222222',
      title: 'Hỏi sofa phòng khách',
      status: 'OPEN',
      aiConfigured: false,
      messages: [{ id: 1, sequenceNumber: 1, role: 'USER', content: 'Cho mình xem sofa', sources: [], createdAt: '2026-11-26T00:00:00Z' }],
    },
    'POST /api/commerce/chat': {
      conversationId: '11111111-1111-4111-8111-111111111111',
      aiConfigured: false,
      model: 'UNCONFIGURED',
      reply: 'Trợ lý AI hiện chưa được kích hoạt khóa API.',
      sources: [{
        productId: 1, productSlug: 'ban-tra-oslo', productName: 'Bàn trà Oslo', sku: 'BT-OSLO',
        variantName: 'Gỗ sồi', categoryName: 'Bàn trà', price: '3200000.00', availableQuantity: 4,
        imageUrl: null, dimensions: '1200 x 600 x 450 mm', materials: 'Gỗ sồi', rooms: 'Phòng khách',
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
  expect(screen.getByRole('link', { name: /Bàn trà Oslo/ })).toHaveAttribute('href', '/products/ban-tra-oslo');

  await user.click(screen.getByRole('button', { name: 'Lịch sử' }));
  await user.click(await screen.findByRole('button', { name: /Hỏi sofa phòng khách/ }));
  await screen.findByText('Cho mình xem sofa');
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
  expect(screen.getByRole('link', { name: /Đèn sàn Bắc Âu/ })).toHaveAttribute('href', '/products/den-san');
  await user.click(screen.getByRole('button', { name: 'Bỏ lưu' }));
  await screen.findByText('Danh sách yêu thích đang trống');
});

it('wires wishlist toggle, verified reviews, recommendations and view tracking on product detail', async () => {
  const { user, calls } = mount('/products/demo-ghe-an-06-01', {
    'GET /api/catalog/products/demo-ghe-an-06-01': {
      id: 51, slug: 'demo-ghe-an-06-01', name: 'Ghế ăn Mộc An', shortDescription: 'Gỗ sồi nhẹ nhàng',
      description: 'Thiết kế gọn gàng.', careInstructions: null, categoryName: 'Ghế ăn',
      categorySlug: 'demo-ghe-an', warrantyMonths: 12, assemblyRequired: false,
      images: [{ url: '/images/gallery-dining-room.jpg', altText: 'Ghế ăn Mộc An' }],
      specifications: [],
      variants: [{ id: 51, sku: 'NN-DEMO-06-01', name: 'Mộc An', widthMm: 480, depthMm: 520, heightMm: 790, price: '1890000.00', compareAtPrice: null, leadTimeDays: 0, availableQuantity: 10, materials: ['Gỗ sồi'] }],
    },
    'GET /api/wishlist': [],
    'POST /api/wishlist/51': { status: 201, body: [{ productId: 51 }] },
    'GET /api/commerce/products/51/reviews?page=0&size=10': {
      productId: 51, averageRating: 4.8, totalReviews: 1, page: 0, size: 10, totalPages: 1,
      items: [{ id: 9, orderId: 1, orderItemId: 1, productId: 51, productName: 'Ghế ăn Mộc An', authorName: 'Minh Anh', rating: 5, title: 'Rất chắc chắn', content: 'Ngồi êm và nhẹ.', status: 'APPROVED', staffReply: 'Cảm ơn bạn nhé!', repliedByName: 'Nếp Nhà', repliedAt: '2026-11-26T00:00:00Z', createdAt: '2026-11-26T00:00:00Z', images: [] }],
    },
  }, customer);

  await screen.findByRole('heading', { name: 'Ghế ăn Mộc An' });
  expect(await screen.findByText('Ngồi êm và nhẹ.')).toBeInTheDocument();
  expect(screen.getByText('Cảm ơn bạn nhé!')).toBeInTheDocument();
  expect(await screen.findByText('Đèn sàn Bắc Âu')).toBeInTheDocument();
  expect(calls.some(c => c.method === 'POST' && c.url === '/api/tracking/events')).toBe(true);

  await user.click(screen.getByRole('button', { name: '♡ Yêu thích' }));
  await screen.findByRole('button', { name: '♥ Đã yêu thích' });
  expect(calls.some(c => c.method === 'POST' && c.url === '/api/wishlist/51')).toBe(true);
  expect(screen.getByRole('link', { name: 'Xem yêu thích' })).toHaveAttribute('href', '/wishlist');
  expect(screen.queryByRole('link', { name: 'Xem giỏ hàng' })).not.toBeInTheDocument();
});

it('shows staff shortcut to /admin/content on public articles page', async () => {
  mount('/articles', {
    'GET /api/commerce/content/articles?query=&page=0&size=9': page([]),
  }, staff);

  expect(await screen.findByRole('link', { name: /Quản lý \/ Viết bài/ })).toHaveAttribute('href', '/admin/content');
});

it('displays dynamic hero banner and latest articles on home page', async () => {
  mount('/', {
    'GET /api/catalog/products?size=100': page([]),
    'GET /api/commerce/home/gallery': { items: [] },
    'GET /api/commerce/content/banners?placement=HOME_HERO': [{
      id: 1, title: 'Bộ sưu tập gỗ sồi mùa đông', subtitle: 'Ấm áp từng góc nhỏ.',
      imageUrl: '/images/home-hero.jpg', targetUrl: '/sale', placement: 'HOME_HERO', sortOrder: 0, isActive: true,
    }],
    'GET /api/commerce/content/articles?query=&page=0&size=3': page([{
      id: 2, title: 'Bí quyết chọn đèn phòng khách', slug: 'bi-quyet-chon-den',
      summary: 'Ánh sáng vàng giúp không gian ấm hơn.', coverImageUrl: null,
      status: 'PUBLISHED', authorName: 'Nếp Nhà', publishedAt: '2026-11-26T00:00:00Z', updatedAt: '2026-11-26T00:00:00Z',
    }]),
  });

  expect(await screen.findByRole('heading', { name: 'Bộ sưu tập gỗ sồi mùa đông' })).toBeInTheDocument();
  expect(await screen.findByRole('link', { name: /Bí quyết chọn đèn phòng khách/ })).toHaveAttribute('href', '/articles/bi-quyet-chon-den');
});
