import { test, expect } from '@playwright/test';

test.beforeEach(async ({ request }) => {
  const response = await request.get('/api/commerce/runtime');
  expect(response.ok()).toBeTruthy();
  const runtime = await response.json();
  expect(runtime.application).toBe('tv3-commerce');
  expect(runtime.isolatedData, 'Manual payment demo may only mutate an isolated development/test database').toBe(true);
});
async function login(page, username) {
  await page.goto('/login');
  await page.getByLabel('Tên đăng nhập', { exact: true }).fill(username);
  await page.getByLabel('Mật khẩu', { exact: true }).fill(process.env.TV3_DEMO_PASSWORD);
  await page.getByRole('button', { name: 'Đăng nhập', exact: true }).click();
  await expect(page).toHaveURL(/\/cart$/);
}

test('demo pending payment is manually confirmed once, exact proof replays after a lost response, and cancellation queues a refund', async ({ page: customerPage, browser, baseURL }) => {
  test.setTimeout(60000);
  test.skip(!process.env.TV3_DEMO_PASSWORD, 'Provide isolated demo credentials through the environment.');
  const adminContext = await browser.newContext({ baseURL, viewport: { width: 1440, height: 1000 }, timezoneId: 'Asia/Bangkok' });
  const adminPage = await adminContext.newPage();
  customerPage.setDefaultTimeout(10000); adminPage.setDefaultTimeout(10000);
  const errors = []; customerPage.on('pageerror', error => errors.push(error.message)); adminPage.on('pageerror', error => errors.push(error.message));
  let orderId; let paymentId; let cancelled = false;
  try {
    await customerPage.setViewportSize({ width: 1440, height: 1000 });
    await login(customerPage, process.env.TV3_CHECKOUT_USER || 'tv3.customer');
    await expect(customerPage.getByRole('region', { name: 'Sản phẩm trong giỏ', exact: true })).toBeVisible();
    if (await customerPage.getByRole('button', { name: 'Xóa tất cả', exact: true }).isVisible()) {
      await customerPage.getByRole('button', { name: 'Xóa tất cả', exact: true }).click();
      await customerPage.getByRole('button', { name: 'Đồng ý xóa hết', exact: true }).click();
      await expect(customerPage.getByRole('heading', { name: 'Giỏ hàng đang chờ bạn', exact: true })).toBeVisible();
    }
    await customerPage.getByRole('button', { name: 'Thêm Bàn trà Oslo vào giỏ', exact: true }).click();
    await customerPage.getByRole('link', { name: 'Tiếp tục đặt hàng', exact: true }).click();
    await customerPage.getByLabel('Nhập địa chỉ mới', { exact: true }).check();
    for (const [label, value] of [['Họ tên người nhận', 'Khách demo TV3-08'], ['Số điện thoại', '0900000000'], ['Địa chỉ', '12 Đường Kiểm Thử'], ['Phường / xã', 'Phường 1'], ['Quận / huyện', 'Quận 3'], ['Tỉnh / thành phố', 'Hồ Chí Minh']]) await customerPage.getByLabel(label, { exact: true }).fill(value);
    await customerPage.getByLabel('Chuyển khoản ngân hàng', { exact: true }).check();
    await customerPage.getByLabel('Ghi chú cho cửa hàng', { exact: true }).fill('E2E DEMO TV3-08: no real funds; cancel after verification.');
    await customerPage.getByRole('button', { name: 'Xem trước đơn hàng', exact: true }).click();
    await customerPage.getByLabel('Tôi đã kiểm tra thông tin và tổng tiền.', { exact: true }).check();
    const creation = customerPage.waitForResponse(response => response.url().endsWith('/api/checkout/orders') && response.request().method() === 'POST');
    await customerPage.getByRole('button', { name: 'Đặt hàng', exact: true }).click();
    const created = await creation; expect(created.status()).toBe(201); const order = await created.json(); orderId = order.id;
    await expect(customerPage.getByRole('heading', { name: order.orderNumber, exact: true })).toBeVisible();

    let createAttemptCount = 0;
    customerPage.on('request', request => { if (request.url().endsWith(`/api/orders/${orderId}/payments`) && request.method() === 'POST') createAttemptCount++; });
    const reading = customerPage.waitForResponse(response => response.url().endsWith(`/api/orders/${orderId}/payments`) && response.request().method() === 'GET');
    await customerPage.getByRole('link', { name: 'Xem thanh toán & hướng dẫn', exact: true }).click();
    const initial = await (await reading).json();
    expect(initial.bankTransfer.isDemo, 'Never attest a real-bank receipt in this E2E').toBe(true);
    expect(initial.bankTransfer.accountNumber).toBe('DEMO-KHONG-CHUYEN-TIEN');
    expect(initial.items).toHaveLength(1); paymentId = initial.items[0].id;
    expect(initial.items[0].status).toBe('PENDING'); expect(initial.canCreate).toBe(false);
    await expect(customerPage.getByRole('heading', { name: 'DEMO — không chuyển tiền thật', exact: true })).toBeVisible();
    await expect(customerPage.getByText('DEMO-KHONG-CHUYEN-TIEN', { exact: true })).toBeVisible();
    await expect(customerPage.getByRole('button', { name: 'Tạo yêu cầu thanh toán', exact: true })).toHaveCount(0);
    expect(createAttemptCount).toBe(0);
    await customerPage.screenshot({ path: 'test-results/tv3-payment-customer-desktop.png', fullPage: true });
    await customerPage.setViewportSize({ width: 390, height: 844 });
    expect(await customerPage.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true);
    await customerPage.screenshot({ path: 'test-results/tv3-payment-customer-mobile.png', fullPage: true });

    await login(adminPage, process.env.TV3_ADMIN_USER || 'tv3.admin');
    await adminPage.getByRole('button', { name: 'Quản trị', exact: true }).click();
    await adminPage.getByRole('link', { name: 'Khoản thu', exact: true }).click();
    await adminPage.getByLabel('Tìm khoản thanh toán', { exact: true }).fill(order.orderNumber);
    await adminPage.getByRole('button', { name: 'Tìm kiếm', exact: true }).click();
    await adminPage.getByLabel('Phương thức thanh toán', { exact: true }).selectOption('BANK_TRANSFER');
    await adminPage.getByLabel('Trạng thái thanh toán', { exact: true }).selectOption('PENDING');
    await adminPage.getByRole('link', { name: `Xác minh khoản ${paymentId}`, exact: true }).click();
    await expect(adminPage.getByRole('heading', { name: `Xác minh khoản #${paymentId}`, exact: true })).toBeVisible();
    const reference = `DEMO08_${Date.now()}`; const note = 'Chứng từ giả học tập TV3-08; không có chuyển tiền thật.';
    await adminPage.getByLabel('Mã chứng từ / tham chiếu', { exact: true }).fill(reference);
    await adminPage.getByLabel('Ghi chú xác minh nội bộ', { exact: true }).fill(note);
    await adminPage.getByLabel('Tôi đã đối chiếu chứng từ và xác nhận thực thu đủ số tiền này.', { exact: true }).check();
    await adminPage.getByRole('button', { name: 'Kiểm tra xác nhận', exact: true }).click();
    let attempts = 0; let committed; let originalProof;
    await adminPage.route(`**/api/admin/payments/${paymentId}/confirm`, async route => {
      attempts++;
      if (attempts === 1) {
        originalProof = route.request().postDataJSON();
        const response = await route.fetch(); expect(response.ok()).toBeTruthy(); committed = await response.json();
        // The real transaction committed; simulate only its response being lost.
        await route.abort('failed');
      } else await route.continue();
    });
    await adminPage.getByRole('button', { name: 'Xác nhận đã thu tiền', exact: true }).click();
    await expect(adminPage.getByRole('button', { name: 'Thử lại xác nhận đã gửi', exact: true })).toBeVisible();
    await adminPage.reload();
    await expect(adminPage.getByRole('button', { name: 'Thử lại xác nhận đã gửi', exact: true })).toBeVisible();
    await expect(adminPage.getByLabel('Mã chứng từ / tham chiếu', { exact: true })).toHaveValue(reference);
    await expect(adminPage.getByLabel('Ghi chú xác minh nội bộ', { exact: true })).toHaveValue(note);
    await adminPage.screenshot({ path: 'test-results/tv3-payment-staff-desktop.png', fullPage: true });
    await adminPage.setViewportSize({ width: 1024, height: 1000 });
    expect(await adminPage.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true);
    await adminPage.setViewportSize({ width: 390, height: 844 });
    expect(await adminPage.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true);
    await adminPage.screenshot({ path: 'test-results/tv3-payment-staff-mobile.png', fullPage: true });
    const replaying = adminPage.waitForResponse(response => response.url().endsWith(`/api/admin/payments/${paymentId}/confirm`) && response.request().method() === 'POST');
    await adminPage.getByRole('button', { name: 'Thử lại xác nhận đã gửi', exact: true }).click();
    const replay = await replaying; expect(replay.ok()).toBeTruthy();
    expect(replay.request().postDataJSON()).toEqual(originalProof);
    const replayed = await replay.json();
    const firstReceipt = committed.items.find(item => item.id === paymentId);
    const sameReceipt = replayed.items.find(item => item.id === paymentId);
    expect(sameReceipt.paidAt).toBe(firstReceipt.paidAt); expect(sameReceipt.verifiedBy).toEqual(firstReceipt.verifiedBy); expect(attempts).toBe(2);
    await expect(adminPage.getByText('Đã ghi nhận chứng từ và khoản thu.', { exact: true })).toBeVisible();

    await customerPage.getByRole('button', { name: 'Làm mới thanh toán', exact: true }).click();
    await expect(customerPage.getByTestId('payment-remaining')).toHaveText('0 ₫');
    await expect(customerPage.getByRole('region', { name: 'Lịch sử thanh toán', exact: true })).toContainText('Đã thu tiền');
    await expect(customerPage.getByText(note, { exact: true })).toHaveCount(0);
    const publicSummary = await (await customerPage.request.get(`/api/orders/${orderId}/payments`)).json();
    expect(Object.hasOwn(publicSummary.items[0], 'confirmationNote')).toBe(false); expect(Object.hasOwn(publicSummary.items[0], 'verifiedBy')).toBe(false);
    await customerPage.getByRole('link', { name: '← Chi tiết đơn hàng', exact: true }).click();
    await customerPage.getByRole('button', { name: 'Hủy đơn hàng', exact: true }).click();
    await customerPage.getByLabel('Lý do hủy đơn', { exact: true }).fill('Kết thúc demo TV3-08; giữ lịch sử khoản thu và yêu cầu hoàn.');
    const cancellation = customerPage.waitForResponse(response => response.url().endsWith(`/api/orders/${orderId}/cancel`) && response.request().method() === 'POST');
    await customerPage.getByRole('button', { name: 'Xác nhận hủy đơn', exact: true }).click();
    expect((await cancellation).ok()).toBeTruthy(); cancelled = true;
    const afterCancel = await (await customerPage.request.get(`/api/orders/${orderId}/payments`)).json();
    expect(afterCancel.orderStatus).toBe('CANCELLED'); expect(afterCancel.collectedAmount).toBe(initial.totalAmount);
    expect(afterCancel.pendingRefundAmount).toBe(initial.totalAmount); expect(afterCancel.refundedAmount).toBe('0.00'); expect(afterCancel.canCreate).toBe(false);
    expect(afterCancel.items[0].status).toBe('SUCCEEDED');
    expect(errors).toEqual([]);
  } finally {
    if (orderId && !cancelled) {
      const csrf = await (await customerPage.request.get('/api/csrf')).json();
      const cleanup = await customerPage.request.post(`/api/orders/${orderId}/cancel`, { data: { reason: 'Dọn giữ hàng sau demo thanh toán TV3-08, không xóa lịch sử.' }, headers: { [csrf.headerName]: csrf.token } });
      expect(cleanup.ok(), 'Cancel only the order created by this isolated test').toBeTruthy();
    }
    await adminContext.close();
  }
});
