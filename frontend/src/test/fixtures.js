export const guest = { authenticated: false, user: null };
export const staff = { authenticated: true, user: { id: 7, username: 'staff', fullName: 'Minh An', roles: ['STAFF'] } };
export const admin = { authenticated: true, user: { id: 8, username: 'admin', fullName: 'Quản trị viên', roles: ['ADMIN'] } };
export const emptyCart = { id: null, itemCount: 0, subtotal: '0.00', currency: 'VND', items: [], warnings: [] };
export const chair = { id: 12, productId: 3, sku: 'CHAIR-OAK', productName: 'Ghế gỗ sồi', variantName: 'Gỗ tự nhiên', price: '1250000.00', imageUrl: null, active: true, availableQuantity: 6 };
export const cart = { id: 4, itemCount: 2, subtotal: '2500000.00', currency: 'VND', items: [{ id: 8, variantId: 12, productId: 3, sku: 'CHAIR-OAK', productName: 'Ghế gỗ sồi', variantName: 'Gỗ tự nhiên', imageUrl: null, quantity: 2, unitPrice: '1250000.00', lineTotal: '2500000.00', availableQuantity: 6, active: true, issues: [] }], warnings: [] };
export const coupon = { id: 5, code: 'NHA10', name: 'Ưu đãi tổ ấm', discountType: 'PERCENT', discountValue: '10.00', maxDiscountAmount: '500000.00', minimumOrderAmount: '1000000.00', totalUsageLimit: 100, perUserLimit: 2, startsAt: '2026-11-13T08:15:31.125Z', endsAt: '2026-12-13T08:15:31.125Z', isActive: true, createdAt: '2026-11-01T00:00:00Z', updatedAt: '2026-11-01T00:00:00Z' };
export const page = items => ({ items, page: 0, size: 20, totalElements: items.length, totalPages: items.length ? 1 : 0 });
