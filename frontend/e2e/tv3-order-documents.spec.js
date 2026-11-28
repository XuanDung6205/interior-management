import { test, expect } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { readFile } from 'node:fs/promises';

async function api(page, path, method = 'GET', data, key) {
  const headers = {};
  if (method !== 'GET') { const csrf = await (await page.request.get('/api/csrf')).json(); headers[csrf.headerName] = csrf.token; }
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
function parseCsv(text) {
  const rows = []; let row = []; let cell = ''; let quoted = false;
  for (let index = 0; index < text.length; index++) {
    const char = text[index];
    if (char === '"') { if (quoted && text[index + 1] === '"') { cell += '"'; index++; } else quoted = !quoted; }
    else if (!quoted && char === ',') { row.push(cell); cell = ''; }
    else if (!quoted && char === '\r' && text[index + 1] === '\n') { row.push(cell); rows.push(row); row = []; cell = ''; index++; }
    else cell += char;
  }
  if (cell || row.length) rows.push([...row, cell]);
  return rows;
}
const allowedPrintFields = ['id', 'orderNumber', 'status', 'currency', 'placedAt', 'subtotal', 'discountAmount', 'shippingFee', 'assemblyFee', 'taxAmount', 'grandTotal', 'couponCode', 'assemblyRequested', 'customerNote', 'shippingAddress', 'items', 'payments', 'refunds', 'collectedAmount', 'refundedAmount', 'pendingRefundAmount'];
async function noOverflow(page) { expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true); }

test('real owner/staff order slips refresh safely and CSV downloads all applied date/query matches', async ({ page: owner, browser, baseURL }) => {
  test.setTimeout(120000);
  test.skip(!process.env.TV3_DEMO_PASSWORD, 'Use isolated demo credentials from the environment.');
  const runtime = await api(owner, '/commerce/runtime');
  expect(runtime.application).toBe('tv3-commerce'); expect(runtime.isolatedData, 'Only the isolated development/test database may be mutated').toBe(true);
  const staffContext = await browser.newContext({ baseURL, viewport: { width: 1440, height: 1000 }, timezoneId: 'Asia/Bangkok' });
  const staff = await staffContext.newPage();
  const errors = []; owner.on('pageerror', error => errors.push(error.message)); staff.on('pageerror', error => errors.push(error.message));
  let created;
  try {
    await owner.setViewportSize({ width: 1440, height: 1000 });
    await login(owner, process.env.TV3_CHECKOUT_USER || 'tv3.customer');
    await login(staff, process.env.TV3_ADMIN_USER || 'tv3.admin');
    const catalog = await api(owner, '/commerce/catalog/variants?query=&page=0&size=50');
    const variant = catalog.items.find(item => item.active && item.availableQuantity >= 1);
    expect(variant, 'An available real catalog variant is required').toBeTruthy();
    await api(owner, '/cart/items', 'DELETE');
    const cart = await api(owner, '/cart/items', 'POST', { variantId: variant.id, quantity: 1 });
    const recipientName = `=TV3,"CSV" ${randomUUID()}`;
    const customerNote = `Order document E2E <img src=x onerror=alert(1)> ${'LongToken'.repeat(25)}`;
    const intent = { cartId: cart.id, shippingAddress: { recipientName, phone: '0900000000', addressLine1: '12 Đường Kiểm Thử', wardName: 'Phường 1', districtName: 'Quận 3', provinceName: 'Hồ Chí Minh', countryCode: 'VN' }, paymentMethod: 'COD', assemblyRequested: false, customerNote };
    const quote = await api(owner, '/checkout/quote', 'POST', intent);
    created = await api(owner, '/checkout/orders', 'POST', { ...intent, quoteFingerprint: quote.quoteFingerprint }, randomUUID());
    const internalNote = `PRIVATE_DOC_${randomUUID()}`;
    const adminDetail = await api(staff, `/admin/orders/${created.id}`);
    await api(staff, `/admin/orders/${created.id}/internal-note`, 'PATCH', { internalNote, expectedInternalNote: adminDetail.internalNote });
    const ownerResponse = await owner.request.get(`/api/orders/${created.id}/print`);
    expect(ownerResponse.headers()['cache-control']).toContain('no-store');
    const ownerSnapshot = await ownerResponse.json();
    expect(Object.keys(ownerSnapshot).sort()).toEqual([...allowedPrintFields].sort());
    expect(ownerSnapshot.collectedAmount).toBe('0.00'); expect(ownerSnapshot.refundedAmount).toBe('0.00'); expect(ownerSnapshot.pendingRefundAmount).toBe('0.00');
    const staffSnapshot = await api(staff, `/admin/orders/${created.id}/print`);
    expect(staffSnapshot).toEqual(ownerSnapshot);
    expect(JSON.stringify(staffSnapshot)).not.toContain(internalNote);
    expect(JSON.stringify(staffSnapshot)).not.toMatch(/confirmationNote|resolutionNote|verifiedBy|processedBy|accountNumber|proofHash/);
    await owner.addInitScript(() => { window.__printCalls = 0; window.print = () => { window.__printCalls++; }; });
    await owner.goto(`/orders/${created.id}`);
    await owner.getByRole('link', { name: 'In phiếu đơn hàng', exact: true }).click();
    await expect(owner.getByRole('heading', { name: 'Phiếu đơn hàng', exact: true })).toBeVisible();
    await expect(owner.getByText(customerNote, { exact: true })).toBeVisible();
    expect(await owner.locator('.order-print-sheet img').count()).toBe(0);
    expect(await owner.evaluate(() => window.__printCalls)).toBe(0);
    await owner.getByRole('button', { name: 'In / Lưu PDF', exact: true }).click();
    expect(await owner.evaluate(() => window.__printCalls)).toBe(1);
    await owner.screenshot({ path: 'test-results/tv3-order-print-owner-desktop.png', fullPage: true });
    await owner.setViewportSize({ width: 390, height: 844 }); await noOverflow(owner);
    await owner.screenshot({ path: 'test-results/tv3-order-print-owner-mobile.png', fullPage: true });
    await owner.reload();
    await expect(owner.getByRole('heading', { name: 'Phiếu đơn hàng', exact: true })).toBeVisible();
    await noOverflow(owner);
    await staff.goto(`/admin/orders/${created.id}`);
    await staff.getByRole('link', { name: 'In phiếu đơn hàng', exact: true }).click();
    await staff.reload();
    await expect(staff.getByRole('heading', { name: 'Phiếu đơn hàng', exact: true })).toBeVisible();
    await expect(staff.getByText(internalNote, { exact: true })).toHaveCount(0);
    await staff.emulateMedia({ media: 'print' });
    await expect(staff.locator('.site-header')).toBeHidden(); await expect(staff.locator('.site-footer')).toBeHidden(); await expect(staff.locator('.order-print-actions')).toBeHidden();
    expect(await staff.locator('.order-print-table thead').evaluate(element => getComputedStyle(element).display)).toBe('table-header-group');
    await noOverflow(staff);
    await staff.screenshot({ path: 'test-results/tv3-order-print-staff-print-media.png', fullPage: true });
    await staff.emulateMedia({ media: 'screen' });
    const date = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Ho_Chi_Minh', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date(created.placedAt));
    await staff.goto('/admin/orders');
    await staff.getByLabel('Tìm đơn hàng', { exact: true }).fill(created.orderNumber);
    await staff.getByLabel('Từ ngày (giờ Việt Nam)', { exact: true }).fill(date);
    await staff.getByLabel('Đến ngày (giờ Việt Nam)', { exact: true }).fill(date);
    await staff.getByRole('button', { name: 'Tìm kiếm', exact: true }).click();
    await expect(staff.getByRole('link', { name: `Quản lý đơn ${created.orderNumber}`, exact: true })).toBeVisible();
    const list = await api(staff, `/admin/orders?page=0&size=10&status=&query=${encodeURIComponent(created.orderNumber)}&fromDate=${date}&toDate=${date}`);
    expect(list.items.map(item => item.id)).toEqual([created.id]);
    await staff.getByLabel('Tìm đơn hàng', { exact: true }).fill('unapplied draft');
    const downloadEvent = staff.waitForEvent('download');
    await staff.getByRole('button', { name: 'Xuất CSV', exact: true }).click();
    const download = await downloadEvent;
    expect(download.suggestedFilename()).toMatch(/^orders-[A-Za-z0-9_-]+\.csv$/);
    const bytes = await readFile(await download.path());
    expect([...bytes.subarray(0, 3)]).toEqual([239, 187, 191]);
    const rows = parseCsv(bytes.toString('utf8').slice(1));
    expect(rows).toHaveLength(2); expect(rows[0]).toHaveLength(14); expect(rows[1]).toHaveLength(14);
    expect(rows[0][0]).toBe('Mã đơn'); expect(rows[1][0]).toBe(created.orderNumber); expect(rows[1][2]).toBe('PENDING_CONFIRMATION');
    expect(rows[1][3]).toBe(`\t${recipientName}`); expect(rows[1][10]).toBe(created.grandTotal); expect(rows[1][12]).toBe('0.00'); expect(rows[1][13]).toBe('0.00');
    expect(bytes.toString('utf8')).not.toContain(internalNote);
    await staff.setViewportSize({ width: 390, height: 844 }); await noOverflow(staff);
    await staff.screenshot({ path: 'test-results/tv3-order-export-staff-mobile.png', fullPage: true });
    expect(errors).toEqual([]);
  } finally {
    if (created) await api(owner, `/orders/${created.id}/cancel`, 'POST', { reason: 'Release only this isolated order-document E2E hold after verification.' });
    await staffContext.close();
  }
});
