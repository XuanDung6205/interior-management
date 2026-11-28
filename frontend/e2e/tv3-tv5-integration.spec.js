import { test, expect } from '@playwright/test';
import { createHmac, randomUUID } from 'node:crypto';

async function api(page, path, method = 'GET', data, key) {
  const headers = {};
  if (method !== 'GET') { const csrf = await (await page.request.get('/api/csrf')).json(); headers[csrf.headerName] = csrf.token; }
  if (key) headers['Idempotency-Key'] = key;
  const response = await page.request.fetch(`/api${path}`, { method, headers, ...(data === undefined ? {} : { data }) });
  expect(response.ok(), `${method} ${path}: ${await response.text()}`).toBeTruthy();
  return response.status() === 204 ? null : response.json();
}
// ADMIN accounts land on /admin after login; customers and staff return to the cart.
const landingAfterLogin = username => username === (process.env.TV3_ADMIN_USER || 'tv3.admin') ? /\/admin$/ : /\/cart$/;
async function login(page, username) {
  await page.goto('/login'); await page.getByLabel('Tên đăng nhập', { exact: true }).fill(username);
  await page.getByLabel('Mật khẩu', { exact: true }).fill(process.env.TV3_DEMO_PASSWORD);
  await page.getByRole('button', { name: 'Đăng nhập', exact: true }).click(); await expect(page).toHaveURL(landingAfterLogin(username));
}
async function checkout(customer, method) {
  const catalog = await api(customer, '/commerce/catalog/variants?query=Oslo&page=0&size=12');
  const variant = catalog.items.find(item => item.active && item.availableQuantity >= 2); expect(variant).toBeTruthy();
  await api(customer, '/cart/items', 'DELETE');
  const cart = await api(customer, '/cart/items', 'POST', { variantId: variant.id, quantity: 2 });
  const body = { cartId: cart.id, paymentMethod: method, assemblyRequested: false, shippingAddress: { recipientName: 'Khách tích hợp TV3 TV5', phone: '0900000000', addressLine1: '12 Đường Kiểm Thử', provinceName: 'Hồ Chí Minh', countryCode: 'VN' } };
  const quote = await api(customer, '/checkout/quote', 'POST', body);
  const order = await api(customer, '/checkout/orders', 'POST', { ...body, quoteFingerprint: quote.quoteFingerprint }, randomUUID());
  const summary = await api(customer, `/orders/${order.id}/payments`);
  return { order, payment: summary.items[0], line: order.items[0], amount: Number(summary.totalAmount) };
}
const dashboard = staff => api(staff, '/admin/dashboard/summary?days=30');
function assertRevenue(current, before, collected, refunded) {
  expect(Number(current.revenue.grossCollected)).toBe(Number(before.revenue.grossCollected) + collected);
  expect(Number(current.revenue.totalRefunded)).toBe(Number(before.revenue.totalRefunded) + refunded);
  expect(Number(current.revenue.netRevenue)).toBe(Number(before.revenue.netRevenue) + collected - refunded);
}
async function partialRefund(staff, orderId, paymentId, returnRequestId) {
  const body = { paymentId, amount: '100000.00', reason: 'Hoàn một phần trong demo tích hợp, không có tiền thật.', ...(returnRequestId ? { returnRequestId } : {}) };
  const pending = await api(staff, `/admin/orders/${orderId}/refunds`, 'POST', body, randomUUID());
  const refund = pending.items.find(item => item.status === 'PENDING' && item.paymentId === paymentId); expect(refund).toBeTruthy();
  return refund;
}
async function confirmRefund(staff, id) {
  const proof = { reference: `DEMO-TV3TV5-${randomUUID()}`, note: 'Chứng từ minh họa trong DB cô lập.', acknowledged: true };
  await api(staff, `/admin/refunds/${id}/confirm`, 'POST', proof);
  await api(staff, `/admin/refunds/${id}/confirm`, 'POST', proof);
}

test('COD delivery authorizes the actual buyer review; pending and completed partial refunds update dashboard once', async ({ page: customer, browser, baseURL }) => {
  test.setTimeout(120000); test.skip(!process.env.TV3_DEMO_PASSWORD, 'Use isolated demo credentials.');
  expect((await api(customer, '/commerce/runtime')).isolatedData).toBe(true);
  const context = await browser.newContext({ baseURL, viewport: { width: 1440, height: 1000 } }); const staff = await context.newPage();
  try {
    await login(customer, 'tv3.customer'); await login(staff, 'tv3.admin'); const before = await dashboard(staff);
    const { order, payment, line, amount } = await checkout(customer, 'COD');
    assertRevenue(await dashboard(staff), before, 0, 0);
    expect((await api(customer, '/reviews/eligible')).some(item => item.orderItemId === line.id)).toBe(false);
    const csrf = await (await customer.request.get('/api/csrf')).json();
    expect((await customer.request.post('/api/reviews', { headers: { [csrf.headerName]: csrf.token }, data: { orderItemId: line.id, rating: 5, title: 'Chưa giao', content: 'Yêu cầu trước khi giao phải bị từ chối.', imageUrls: [] } })).status()).toBe(409);
    await api(staff, `/admin/orders/${order.id}/transitions`, 'POST', { fromStatus: 'PENDING_CONFIRMATION', toStatus: 'CONFIRMED', note: 'Xác nhận demo tích hợp' });
    const holds = await api(staff, `/admin/inventory/orders/${order.id}/holds`); const warehouseId = holds.holds.find(item => item.remainingQuantity > 0).warehouseId;
    const dispatched = await api(staff, `/admin/inventory/orders/${order.id}/dispatch`, 'POST', { warehouseId, carrier: 'DEMO', trackingNumber: `TV3TV5-${Date.now()}` }, randomUUID());
    await api(staff, `/admin/inventory/shipments/${dispatched.shipmentId}/status`, 'POST', { targetStatus: 'DELIVERED', reason: 'Giao hàng demo hợp lệ' }, randomUUID());
    expect((await api(customer, '/reviews/eligible')).some(item => item.orderItemId === line.id)).toBe(true);
    const collection = { reference: `DEMO-COD-${randomUUID()}`, note: 'Chứng từ giả cho đồ án.', acknowledged: true };
    await api(staff, `/admin/payments/${payment.id}/confirm`, 'POST', collection); await api(staff, `/admin/payments/${payment.id}/confirm`, 'POST', collection);
    assertRevenue(await dashboard(staff), before, amount, 0);

    await customer.goto('/reviews');
    await customer.locator('.payment-record').filter({ hasText: order.orderNumber }).getByRole('button', { name: 'Viết đánh giá', exact: true }).click();
    await customer.getByLabel('Tiêu đề ngắn', { exact: true }).fill('Đánh giá sau khi nhận hàng');
    await customer.getByLabel('Cảm nhận của bạn', { exact: true }).fill('Bàn trà đã nhận đúng mẫu. Đây là dữ liệu kiểm thử tích hợp.');
    const created = customer.waitForResponse(response => response.url().endsWith('/api/reviews') && response.request().method() === 'POST');
    await customer.getByRole('button', { name: 'Gửi đánh giá', exact: true }).click(); const reviewResponse = await created; expect(reviewResponse.status()).toBe(201); const review = await reviewResponse.json();
    await api(staff, `/admin/reviews/${review.id}/moderate`, 'PATCH', { status: 'APPROVED', staffReply: 'Đã đối chiếu đơn giao thành công.' });
    const publicReviews = await api(customer, `/commerce/products/${line.productId}/reviews`); expect(publicReviews.items.some(item => item.id === review.id)).toBe(true);

    const requested = await api(customer, `/orders/${order.id}/returns`, 'POST', { reason: 'Trả một sản phẩm trong demo', items: [{ orderItemId: line.id, quantity: 1 }] }, randomUUID());
    const returned = requested.returns[0]; await api(staff, `/admin/aftercare/returns/${returned.id}/decision`, 'POST', { decision: 'APPROVE', reason: 'Đã kiểm tra yêu cầu' });
    const aftercare = await api(staff, `/admin/aftercare/orders/${order.id}`); const approved = aftercare.returns.find(item => item.id === returned.id);
    await api(staff, `/admin/aftercare/returns/${returned.id}/receive`, 'POST', { warehouseId, acknowledged: true, items: [{ returnItemId: approved.items[0].id, receivedQuantity: 1, restockQuantity: 1, conditionNote: 'Hàng demo nguyên vẹn' }] });
    const refund = await partialRefund(staff, order.id, payment.id, returned.id); assertRevenue(await dashboard(staff), before, amount, 0);
    await confirmRefund(staff, refund.id); const final = await dashboard(staff); assertRevenue(final, before, amount, 100000);
    await staff.goto('/admin/dashboard'); await expect(staff.getByRole('heading', { name: /Tổng quan tài chính/ })).toBeVisible();
    await staff.setViewportSize({ width: 390, height: 844 }); expect(await staff.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true);
    await staff.screenshot({ path: 'test-results/tv3-tv5-dashboard-mobile.png', fullPage: true });
  } finally { await context.close(); }
});

test('signed SePay collection and partial refund keep dashboard consistent without waiting for cache TTL', async ({ page: customer, browser, baseURL, request }) => {
  test.setTimeout(120000); test.skip(!process.env.TV3_DEMO_PASSWORD || !process.env.SEPAY_WEBHOOK_SECRET, 'Use isolated demo credentials and HMAC test secret.');
  expect((await api(customer, '/commerce/runtime')).isolatedData).toBe(true);
  const context = await browser.newContext({ baseURL }); const staff = await context.newPage();
  try {
    await login(customer, 'tv3.customer'); await login(staff, 'tv3.admin'); const before = await dashboard(staff);
    const { order, payment, amount } = await checkout(customer, 'SEPAY');
    const transfer = { id: String(Date.now()), gateway: payment.sepay.bank, transactionDate: new Date().toISOString().slice(0, 19).replace('T', ' '), accountNumber: payment.sepay.accountNumber, subAccount: null, code: payment.sepay.code, content: payment.sepay.transferContent, transferType: 'in', transferAmount: amount, referenceCode: `TV3TV5-${randomUUID()}` };
    const body = JSON.stringify(transfer), timestamp = String(Math.floor(Date.now() / 1000));
    const headers = { 'Content-Type': 'application/json', 'X-SePay-Timestamp': timestamp, 'X-SePay-Signature': 'sha256=' + createHmac('sha256', process.env.SEPAY_WEBHOOK_SECRET).update(timestamp + '.' + body).digest('hex') };
    for (let i = 0; i < 2; i++) expect((await request.post('/api/payments/sepay/webhook', { headers, data: body })).status()).toBe(200);
    assertRevenue(await dashboard(staff), before, amount, 0);
    await api(customer, `/orders/${order.id}/cancel`, 'POST', { reason: 'Hủy đơn đã thu trong dữ liệu demo' });
    const refunds = await api(staff, `/admin/orders/${order.id}/refunds`); const automatic = refunds.items.find(item => item.status === 'PENDING'); expect(automatic).toBeTruthy();
    await api(staff, `/admin/refunds/${automatic.id}/cancel`, 'POST', { reason: 'Đối chiếu lại thành yêu cầu hoàn một phần', acknowledged: true });
    const refund = await partialRefund(staff, order.id, payment.id); assertRevenue(await dashboard(staff), before, amount, 0);
    await confirmRefund(staff, refund.id); assertRevenue(await dashboard(staff), before, amount, 100000);
  } finally { await context.close(); }
});
