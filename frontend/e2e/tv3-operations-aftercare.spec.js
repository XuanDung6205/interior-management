import { test, expect } from '@playwright/test';
import { randomUUID } from 'node:crypto';

async function api(page, path, method = 'GET', data, key) {
  const headers = {};
  if (method !== 'GET') {
    const csrf = await (await page.request.get('/api/csrf')).json(); headers[csrf.headerName] = csrf.token;
  }
  if (key) headers['Idempotency-Key'] = key;
  const response = await page.request.fetch(`/api${path}`, { method, headers, ...(data !== undefined ? { data } : {}) });
  expect(response.ok(), `${method} ${path}: ${await response.text()}`).toBeTruthy();
  return response.status() === 204 ? null : response.json();
}
// ADMIN accounts land on /admin after login; customers and staff return to the cart.
const landingAfterLogin = username => username === (process.env.TV3_ADMIN_USER || 'tv3.admin') ? /\/admin$/ : /\/cart$/;
async function login(page, username) {
  await page.goto('/login');
  await page.getByLabel('Tên đăng nhập', { exact: true }).fill(username);
  await page.getByLabel('Mật khẩu', { exact: true }).fill(process.env.TV3_DEMO_PASSWORD);
  await page.getByRole('button', { name: 'Đăng nhập', exact: true }).click();
  await expect(page).toHaveURL(landingAfterLogin(username));
}
async function noOverflow(page) { expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true); }

test('real COD delivery, assembly proof, partial return/restock/refund and customer web notifications', async ({ page: customer, browser, baseURL }) => {
  test.setTimeout(180000);
  test.skip(!process.env.TV3_DEMO_PASSWORD, 'Use isolated demo credentials from the environment.');
  const runtime = await api(customer, '/commerce/runtime');
  expect(runtime.application).toBe('tv3-commerce'); expect(runtime.isolatedData).toBe(true);
  const staffContext = await browser.newContext({ baseURL, viewport: { width: 1440, height: 1000 }, timezoneId: 'Asia/Bangkok' });
  const staff = await staffContext.newPage(); const errors = [];
  customer.on('pageerror', error => errors.push(error.message)); staff.on('pageerror', error => errors.push(error.message));
  try {
    await login(customer, process.env.TV3_CHECKOUT_USER || 'tv3.customer');
    await login(staff, process.env.TV3_ADMIN_USER || 'tv3.admin');
    const catalog = await api(customer, '/commerce/catalog/variants?query=&page=0&size=50');
    const address = { recipientName: 'Khách E2E hậu mãi', phone: '0900000000', addressLine1: '12 Đường Kiểm Thử', wardName: 'Phường 1', districtName: 'Quận 3', provinceName: 'Hồ Chí Minh', countryCode: 'VN' };
    let quote; let intent;
    for (const variant of catalog.items.filter(item => item.active && item.availableQuantity >= 2)) {
      await api(customer, '/cart/items', 'DELETE');
      const cart = await api(customer, '/cart/items', 'POST', { variantId: variant.id, quantity: 2 });
      intent = { cartId: cart.id, shippingAddress: address, paymentMethod: 'COD', assemblyRequested: true, customerNote: 'Isolated E2E TV3 operations/aftercare; no real money.' };
      const candidate = await api(customer, '/checkout/quote', 'POST', intent);
      if (candidate.items.some(item => item.assemblyRequired)) { quote = candidate; break; }
    }
    expect(quote, 'Isolated fixture needs an available product requiring assembly').toBeTruthy();
    const order = await api(customer, '/checkout/orders', 'POST', { ...intent, quoteFingerprint: quote.quoteFingerprint }, randomUUID());
    expect(order.confirmationExpiresAt).toBeTruthy();
    const confirmed = await api(staff, `/admin/orders/${order.id}/transitions`, 'POST', { fromStatus: 'PENDING_CONFIRMATION', toStatus: 'CONFIRMED', note: 'E2E xác nhận đơn COD' });
    expect(confirmed.status).toBe('CONFIRMED');
    const holds = await api(staff, `/admin/inventory/orders/${order.id}/holds`);
    const warehouseId = holds.holds.find(item => item.remainingQuantity > 0).warehouseId;
    const dispatched = await api(staff, `/admin/inventory/orders/${order.id}/dispatch`, 'POST', { warehouseId, carrier: 'E2E nội bộ', trackingNumber: `E2E_AFTERCARE_${Date.now()}`, note: 'Isolated delivery evidence' }, randomUUID());
    await api(staff, `/admin/inventory/shipments/${dispatched.shipmentId}/status`, 'POST', { targetStatus: 'DELIVERED', reason: 'E2E đã giao thực tế trong dữ liệu cô lập' }, randomUUID());
    const payments = await api(customer, `/orders/${order.id}/payments`);
    const payment = payments.items.find(item => item.method === 'COD' && item.status === 'PENDING'); expect(payment).toBeTruthy();
    await api(staff, `/admin/payments/${payment.id}/confirm`, 'POST', { reference: `DEMO_COD_${Date.now()}`, note: 'Isolated educational evidence; no real funds.', acknowledged: true });

    await staff.goto(`/admin/orders/${order.id}/aftercare`);
    await expect(staff.getByRole('heading', { name: `Hậu mãi đơn ${order.orderNumber}`, exact: true })).toBeVisible();
    const shipmentId = dispatched.shipmentId; const installationReference = `DEMO_ASSEMBLY_${Date.now()}`;
    await staff.getByLabel(`Chứng từ lắp đặt #${shipmentId}`, { exact: true }).fill(installationReference);
    await staff.getByLabel(`Ghi chú lắp đặt nội bộ #${shipmentId}`, { exact: true }).fill('Private assembly evidence');
    await staff.getByLabel(`Tôi xác nhận đã thực hiện lắp đặt vận đơn #${shipmentId}`, { exact: true }).check();
    let installationProof; let installationAttempts = 0;
    await staff.route(`**/api/admin/aftercare/shipments/${shipmentId}/installation`, async route => {
      installationAttempts++;
      if (installationAttempts === 1) { installationProof = route.request().postDataJSON(); const response = await route.fetch(); expect(response.ok()).toBeTruthy(); await route.abort('failed'); }
      else { expect(route.request().postDataJSON()).toEqual(installationProof); await route.continue(); }
    });
    await staff.getByRole('button', { name: `Hoàn tất lắp đặt vận đơn #${shipmentId}`, exact: true }).click();
    await expect(staff.getByRole('button', { name: 'Thử lại đúng yêu cầu đã gửi', exact: true })).toBeVisible();
    await staff.reload(); await staff.getByRole('button', { name: 'Thử lại đúng yêu cầu đã gửi', exact: true }).click();
    await expect(staff.getByText('Đã ghi nhận bằng chứng hậu mãi.', { exact: true })).toBeVisible();
    expect((await api(customer, `/orders/${order.id}`)).status).toBe('COMPLETED');

    await customer.goto(`/orders/${order.id}/returns`);
    const before = await api(customer, `/orders/${order.id}/returns`); const line = before.items.find(item => item.returnableQuantity >= 2); expect(line).toBeTruthy();
    await customer.getByLabel(`Số lượng trả ${line.productName}`, { exact: true }).fill('1');
    await customer.getByLabel('Lý do trả hàng', { exact: true }).fill('E2E trả một phần do lỗi bề mặt');
    let requestIntent; let requestKey; let requestAttempts = 0;
    await customer.route(`**/api/orders/${order.id}/returns`, async route => {
      if (route.request().method() !== 'POST') return route.continue();
      requestAttempts++;
      if (requestAttempts === 1) { requestIntent = route.request().postDataJSON(); requestKey = route.request().headers()['idempotency-key']; const response = await route.fetch(); expect(response.status()).toBe(201); await route.abort('failed'); }
      else { expect(route.request().postDataJSON()).toEqual(requestIntent); expect(route.request().headers()['idempotency-key']).toBe(requestKey); await route.continue(); }
    });
    await customer.getByRole('button', { name: 'Gửi yêu cầu trả hàng', exact: true }).click();
    await expect(customer.getByRole('button', { name: 'Thử lại đúng yêu cầu đã gửi', exact: true })).toBeVisible();
    await customer.reload(); await customer.getByRole('button', { name: 'Thử lại đúng yêu cầu đã gửi', exact: true }).click();
    await expect(customer.getByText('Đã ghi nhận yêu cầu trả hàng.', { exact: true })).toBeVisible(); expect(requestAttempts).toBe(2);
    const requested = await api(customer, `/orders/${order.id}/returns`); const entry = requested.returns[0];
    await staff.reload();
    await staff.getByLabel(`Lý do nội bộ ${entry.returnNumber}`, { exact: true }).fill('Private approval evidence');
    await staff.getByRole('button', { name: `Ghi nhận quyết định ${entry.returnNumber}`, exact: true }).click();
    await expect(staff.getByLabel(`Kho nhận ${entry.returnNumber}`, { exact: true })).toBeVisible();
    const warehouses = await api(staff, '/admin/inventory/warehouses'); expect(warehouses.some(item => item.id === warehouseId)).toBe(true);
    await staff.getByLabel(`Kho nhận ${entry.returnNumber}`, { exact: true }).selectOption(String(warehouseId));
    await staff.getByLabel(`Thực nhận ${line.productName} ${entry.returnNumber}`, { exact: true }).fill('1');
    await staff.getByLabel(`Nhập lại kho ${line.productName} ${entry.returnNumber}`, { exact: true }).fill('1');
    await staff.getByLabel(`Tình trạng nội bộ ${line.productName} ${entry.returnNumber}`, { exact: true }).fill('Private condition evidence');
    await staff.getByLabel(`Tôi đã kiểm đếm hàng thực nhận ${entry.returnNumber}`, { exact: true }).check();
    await staff.getByRole('button', { name: `Ghi nhận nhận hàng ${entry.returnNumber}`, exact: true }).click();
    await expect(staff.getByRole('link', { name: 'Đối chiếu hoàn tiền sau nhận hàng', exact: true })).toBeVisible();
    await staff.getByRole('link', { name: 'Đối chiếu hoàn tiền sau nhận hàng', exact: true }).click();
    await staff.getByLabel('Số tiền đề nghị hoàn', { exact: true }).fill('1.00');
    await staff.getByLabel('Phiếu trả hàng đã nhận', { exact: true }).selectOption(String(entry.id));
    await staff.getByLabel('Lý do đề nghị hoàn', { exact: true }).fill('E2E hoàn một phần cho hàng thực nhận');
    await staff.getByRole('button', { name: 'Kiểm tra yêu cầu hoàn', exact: true }).click();
    const refundCreation = staff.waitForResponse(response => response.url().endsWith(`/api/admin/orders/${order.id}/refunds`) && response.request().method() === 'POST');
    await staff.getByRole('button', { name: 'Tạo yêu cầu hoàn', exact: true }).click();
    const refundSummary = await (await refundCreation).json(); const refundId = refundSummary.selectedRefundId;
    await staff.getByRole('link', { name: `Đối chiếu hoàn ${refundId}`, exact: true }).click();
    await staff.getByLabel('Tham chiếu hoàn tiền', { exact: true }).fill(`DEMO_PARTIAL_${Date.now()}`);
    await staff.getByLabel('Tôi đã đối chiếu chứng từ và xác nhận đã trả đủ số tiền này.', { exact: true }).check();
    await staff.getByRole('button', { name: 'Kiểm tra chứng từ hoàn', exact: true }).click();
    await staff.getByRole('button', { name: 'Xác nhận đã hoàn tiền', exact: true }).click();
    await expect(staff.getByText('Đã ghi nhận chứng từ hoàn tiền.', { exact: true })).toBeVisible();
    const money = await api(customer, `/orders/${order.id}/payments`); expect(money.refundedAmount).toBe('1.00');
    await staff.goto('/admin/operations');
    await expect(staff.getByRole('heading', { name: 'Vận hành hệ thống', exact: true })).toBeVisible();
    await staff.getByRole('button', { name: 'Chạy chuyển thông báo vào hộp thư', exact: true }).click();
    await expect(staff.getByText(/Đã đưa vào hộp thư:/)).toBeVisible();
    await customer.goto('/notifications');
    await expect(customer.getByRole('heading', { name: 'Thông báo của bạn', exact: true })).toBeVisible();
    const inbox = await api(customer, '/notifications?page=0&size=100&unreadOnly=false');
    expect(inbox.items.some(item => item.orderId === order.id && item.type === 'REFUND_CONFIRMED')).toBe(true);
    expect(JSON.stringify(inbox)).not.toContain('Private');
    await customer.getByRole('button', { name: 'Đánh dấu đã đọc', exact: true }).first().click();
    await customer.screenshot({ path: 'test-results/tv3-notifications-desktop.png', fullPage: true });
    await customer.setViewportSize({ width: 390, height: 844 }); await noOverflow(customer);
    await customer.screenshot({ path: 'test-results/tv3-notifications-mobile.png', fullPage: true });
    await staff.setViewportSize({ width: 390, height: 844 }); await noOverflow(staff);
    await staff.screenshot({ path: 'test-results/tv3-operations-mobile.png', fullPage: true });
    await customer.goto(`/orders/${order.id}/returns`); await noOverflow(customer);
    await expect(customer.getByText('Private condition evidence', { exact: true })).toHaveCount(0);
    const after = await api(customer, `/orders/${order.id}/returns`);
    expect(after.items.find(item => item.id === line.id).returnableQuantity).toBe(1);
    expect(after.returns[0].items[0].restockedQuantity).toBe(1);
    expect(Object.hasOwn(after.returns[0], 'staffNote')).toBe(false);
    expect(errors).toEqual([]);
  } finally { await staffContext.close(); }
});
