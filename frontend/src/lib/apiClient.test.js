import { describe, expect, it, vi } from 'vitest';
import { createApiClient } from './apiClient';

const response = (body, status = 200) => new Response(body == null ? null : JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

describe('session and CSRF boundary', () => {
  it('preserves an intentional abort while reading a response body', async () => {
    const aborted = new DOMException('The operation was aborted.', 'AbortError');
    const api = createApiClient(async () => ({ status: 200, ok: true, json: async () => { throw aborted; } }));
    await expect(api.request('/admin/payments')).rejects.toBe(aborted);
  });
  it.each([200, 201])('rejects unreadable successful HTTP%s without claiming a mutation succeeded', async status => {
    const api = createApiClient(async () => new Response('{"orderId":', { status, headers: { 'Content-Type': 'application/json' } }));
    await expect(api.request('/orders/101/payments')).rejects.toMatchObject({ status, code: 'INVALID_RESPONSE' });
  });
  it('keeps non-JSON HTTP error status and accepts a legitimate 204 without a body', async () => {
    const api = createApiClient(async url => url.endsWith('/empty') ? new Response(null, { status: 204 }) : new Response('upstream unavailable', { status: 503 }));
    await expect(api.request('/broken')).rejects.toMatchObject({ status: 503 });
    await expect(api.request('/empty')).resolves.toBeNull();
  });
  it('sends order idempotency headers and exposes the new quote from a conflict response', async () => {
    const calls = [];
    const api = createApiClient(async (url, options) => { calls.push({ url, ...options }); return url === '/api/csrf' ? response({ token: 't', headerName: 'X-CSRF-TOKEN' }) : response({ code: 'QUOTE_CHANGED', message: 'Giá đổi', fieldErrors: {}, quote: { quoteFingerprint: 'v2', grandTotal: '100.00' } }, 409); });
    await expect(api.request('/checkout/orders', { method: 'POST', headers: { 'Idempotency-Key': 'order-attempt-1' }, body: { quoteFingerprint: 'v1' } })).rejects.toMatchObject({ code: 'QUOTE_CHANGED', quote: { quoteFingerprint: 'v2', grandTotal: '100.00' } });
    expect(calls[1].headers['Idempotency-Key']).toBe('order-attempt-1');
    expect(calls[1].headers['X-CSRF-TOKEN']).toBe('t');
  });
  it('uses the renewed CSRF token for the explicit post-login cart merge and refreshes again after logout', async () => {
    const calls = [];
    let token = 'guest-token';
    const fetcher = vi.fn(async (url, options) => {
      calls.push({ url, ...options });
      if (url === '/api/csrf') return response({ token, headerName: 'X-CSRF-TOKEN' });
      if (url === '/api/session/login') { token = 'user-token'; return response({ authenticated: true, user: { id: 1, username: 'customer', fullName: 'An', roles: ['CUSTOMER'] } }); }
      if (url === '/api/session/logout') { token = 'next-guest-token'; return response({ authenticated: false, user: null }); }
      if (url === '/api/cart/merge') return response({ id: 1, itemCount: 0, subtotal: '0.00', currency: 'VND', items: [], warnings: [] });
      return response({});
    });
    const api = createApiClient(fetcher);
    await api.login('an@example.com', 'a&b=c');
    await api.mergeCart();
    await api.logout();
    await api.request('/cart/items', { method: 'POST', body: { variantId: 12, quantity: 1 } });
    expect(calls.map(c => c.url)).toEqual(['/api/csrf', '/api/session/login', '/api/csrf', '/api/cart/merge', '/api/session/logout', '/api/csrf', '/api/cart/items']);
    expect(calls[1].headers['X-CSRF-TOKEN']).toBe('guest-token');
    expect(new URLSearchParams(calls[1].body).get('password')).toBe('a&b=c');
    expect(calls[3].headers['X-CSRF-TOKEN']).toBe('user-token');
    expect(calls[6].headers['X-CSRF-TOKEN']).toBe('next-guest-token');
    expect(calls.every(c => c.credentials === 'include')).toBe(true);
  });

  it('retains structured field errors and never silently retries a rejected mutation', async () => {
    const fetcher = vi.fn(async url => url === '/api/csrf' ? response({ token: 't', headerName: 'X-CSRF-TOKEN' }) : response({ code: 'COUPON_IN_USE', message: 'Hãy tắt mã thay vì xóa.', fieldErrors: { code: 'Mã đã được tham chiếu' } }, 409));
    const api = createApiClient(fetcher);
    await expect(api.request('/admin/coupons/4', { method: 'DELETE' })).rejects.toMatchObject({ status: 409, code: 'COUPON_IN_USE', fieldErrors: { code: 'Mã đã được tham chiếu' } });
    expect(fetcher).toHaveBeenCalledTimes(2);
  });
});
