import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { it, expect, vi } from 'vitest';
import { SessionContext } from './lib/session';
import CatalogAdminPage from './features/catalog/CatalogAdminPage';
import SalePage from './features/catalog/SalePage';
import ProductDetailPage from './features/catalog/ProductDetailPage';
const options = { categories: [{ id: 1, name: 'Bàn', slug: 'ban', active: true }], rooms: [], collections: [], brands: [], materials: [{ id: 4, name: 'Sồi', slug: 'oak', active: true }] };
const full = { id: 9, version: 3, name: 'Bàn Mộc', slug: 'ban-moc', status: 'ACTIVE', categoryId: 1, brandId: null, warrantyMonths: 24, assemblyRequired: false, roomIds: [], collectionIds: [], variants: [{ id: 15, sku: 'BAN-15', variantKey: 'oak', name: 'Sồi 120', status: 'ACTIVE', price: '1200000.00', compareAtPrice: null, leadTimeDays: 2, materialIds: [4] }], images: [{ url: '/images/table.jpg', altText: 'Bàn gỗ', variantId: null }], specifications: [] };
function mount(ui, request, path = '/') { render(<MemoryRouter initialEntries={[path]}><SessionContext.Provider value={{ api: { request }, session: { authenticated: false } }}>{ui}</SessionContext.Provider></MemoryRouter>); return userEvent.setup(); }
it('retains SKU IDs, version and material associations when updating price', async () => {
  const request = vi.fn(async (path, init) => path === '/admin/catalog/options' ? options : path.includes('/products?') ? { items: [full], page: 0, totalPages: 1 } : init?.method === 'PUT' ? { ...init.body, id: 9, version: 4 } : full);
  const user = mount(<CatalogAdminPage />, request);
  await user.click(await screen.findByRole('button', { name: 'Sửa Bàn Mộc' }));
  const price = await screen.findByLabelText('Giá bán (₫) *');
  await user.clear(price); await user.type(price, '1450000.50');
  await user.click(screen.getByRole('button', { name: 'Lưu sản phẩm' }));
  await screen.findByText('Đã lưu sản phẩm và các phiên bản.');
  const payload = request.mock.calls.find(([, init]) => init?.method === 'PUT')[1].body;
  expect(payload.version).toBe(3); expect(payload.variants[0].id).toBe(15);
  expect(payload.variants[0].price).toBe('1450000.50'); expect(payload.variants[0].materialIds).toEqual([4]);
});
it('creates a child category through the shared API client', async () => {
  const request = vi.fn(async (path, init) => path === '/admin/catalog/options' ? options : path.includes('/products?') ? { items: [], totalPages: 0 } : { id: 20, ...init?.body });
  const user = mount(<CatalogAdminPage />, request);
  await user.click(screen.getByRole('button', { name: 'Phân loại', exact: true }));
  await user.type(screen.getByLabelText('Tên phân loại *'), 'Bàn ăn');
  await user.click(screen.getByLabelText('Đường dẫn phân loại *'));
  await user.selectOptions(screen.getByLabelText('Danh mục cha'), '1');
  await user.click(screen.getByRole('button', { name: 'Lưu phân loại' }));
  await screen.findByText('Đã lưu phân loại.');
  const payload = request.mock.calls.find(([, init]) => init?.method === 'POST')[1].body;
  expect(payload.slug).toBe('ban-an'); expect(payload.parentId).toBe(1);
});
it('sends material and dimension filters while resetting pagination', async () => {
  const request = vi.fn(async path => path === '/catalog/facets' ? { ...options, colors: [], sizes: [] } : path.startsWith('/catalog/categories?') ? options.categories : path.startsWith('/catalog/products?') ? { items: [], page: 0, totalElements: 0, totalPages: 0 } : null);
  const user = mount(<SalePage />, request, '/sale?page=3');
  await screen.findByRole('option', { name: 'Sồi' });
  await user.selectOptions(screen.getByLabelText('Chất liệu'), 'oak');
  await user.click(screen.getByText('Số đo phù hợp không gian'));
  await user.type(screen.getByLabelText('Rộng đến (mm)'), '1400');
  await user.click(screen.getByRole('button', { name: 'Áp dụng bộ lọc' }));
  await waitFor(() => expect(request.mock.calls.some(([path]) => path.includes('/products?') && path.includes('material=oak') && path.includes('maxWidth=1400') && path.includes('page=0'))).toBe(true));
});
it('changes SKU price, cart ID and gallery together', async () => {
  const detail = { ...full, categoryName: 'Bàn', categorySlug: 'ban', images: [{ url: '/images/common.jpg', altText: 'Ảnh chung' }, { url: '/images/red.jpg', altText: 'Màu đỏ', variantId: 15 }, { url: '/images/blue.jpg', altText: 'Màu xanh', variantId: 16 }], variants: [{ ...full.variants[0], availableQuantity: 5, materials: ['Sồi'] }, { ...full.variants[0], id: 16, name: 'Xanh 140', price: '1500000.00', availableQuantity: 7, materials: ['Sồi'] }] };
  const request = vi.fn(async path => path === '/catalog/products/ban-moc' ? detail : path === '/cart/items' ? {} : path.includes('/reviews') ? { items: [], totalReviews: 0 } : path.includes('/recommendations') ? { items: [] } : null);
  const user = mount(<Routes><Route path="/products/:slug" element={<ProductDetailPage />} /></Routes>, request, '/products/ban-moc');
  await user.click(await screen.findByRole('button', { name: 'Xanh 140' }));
  expect(screen.getByText('1.500.000 ₫')).toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: 'Xem ảnh 2' }));
  expect(screen.getByRole('img', { name: 'Màu xanh' })).toHaveAttribute('src', '/images/blue.jpg');
  expect(screen.queryByRole('img', { name: 'Màu đỏ' })).not.toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: 'Thêm vào giỏ' }));
  await waitFor(() => expect(request).toHaveBeenCalledWith('/cart/items', expect.objectContaining({ body: { variantId: 16, quantity: 1 } })));
});
