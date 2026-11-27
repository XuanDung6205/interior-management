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

test('demo paid order is cancelled, its unsent refund is replaced and exact refund proof replays before public history updates', async ({ page: customerPage, browser, baseURL }) => {
  test.setTimeout(120000);
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
    await adminPage.goto(`/admin/orders/${orderId}/refunds`);
    await expect(adminPage.getByRole('heading', { name: `Hoàn tiền đơn ${order.orderNumber}`, exact: true })).toBeVisible();
    const originalRefund = (await (await adminPage.request.get(`/api/admin/orders/${orderId}/refunds`)).json()).items[0];
    expect(originalRefund.status).toBe('PENDING'); expect(originalRefund.amount).toBe(initial.totalAmount);
    await adminPage.getByRole('link', { name: `Đối chiếu hoàn ${originalRefund.id}`, exact: true }).click();
    const cancelReason = 'DEMO TV3-10: chưa gửi tiền, thay yêu cầu để đối chiếu; không có tiền thật.';
    await adminPage.getByLabel('Lý do hủy yêu cầu hoàn', { exact: true }).fill(cancelReason);
    await adminPage.getByLabel('Tôi xác nhận chưa gửi tiền cho yêu cầu hoàn này.', { exact: true }).check();
    await adminPage.getByRole('button', { name: 'Kiểm tra hủy yêu cầu', exact: true }).click();
    await adminPage.getByRole('button', { name: 'Xác nhận hủy yêu cầu hoàn', exact: true }).click();
    await expect(adminPage.getByText('Đã hủy yêu cầu chưa gửi tiền. Nghĩa vụ hoàn vẫn cần đối chiếu.', { exact: true })).toBeVisible();
    await adminPage.getByRole('link', { name: 'Ngân sách & đề nghị hoàn của đơn', exact: true }).click();
    await adminPage.getByLabel('Khoản thu gốc', { exact: true }).selectOption(String(paymentId));
    await adminPage.getByLabel('Số tiền đề nghị hoàn', { exact: true }).fill(initial.totalAmount);
    await adminPage.getByLabel('Lý do đề nghị hoàn', { exact: true }).fill('DEMO TV3-10: hoàn sau hủy đơn, không có tiền thật.');
    await adminPage.getByRole('button', { name: 'Kiểm tra yêu cầu hoàn', exact: true }).click();
    let refundCreation; let createIntent; let createKey; let creates = 0;
    await adminPage.route(`**/api/admin/orders/${orderId}/refunds`, async route => {
      if (route.request().method() !== 'POST') return route.continue();
      creates++;
      if (creates === 1) {
        createIntent = route.request().postDataJSON(); createKey = route.request().headers()['idempotency-key'];
        const response = await route.fetch(); expect(response.status()).toBe(201); refundCreation = await response.json(); await route.abort('failed');
      } else { expect(route.request().postDataJSON()).toEqual(createIntent); expect(route.request().headers()['idempotency-key']).toBe(createKey); await route.continue(); }
    });
    await adminPage.getByRole('button', { name: 'Tạo yêu cầu hoàn', exact: true }).click();
    await expect(adminPage.getByRole('button', { name: 'Thử lại yêu cầu hoàn đã gửi', exact: true })).toBeVisible();
    await adminPage.reload();
    await adminPage.getByRole('button', { name: 'Thử lại yêu cầu hoàn đã gửi', exact: true }).click();
    await expect(adminPage.getByText('Đã giữ ngân sách cho yêu cầu hoàn. Chưa xác nhận đã trả tiền.', { exact: true })).toBeVisible();
    expect(creates).toBe(2);
    const refundId = refundCreation.selectedRefundId;
    await adminPage.getByRole('link', { name: `Đối chiếu hoàn ${refundId}`, exact: true }).click();
    const refundReference = `DEMO10_REFUND_${Date.now()}`; const refundNote = 'DEMO TV3-10 chứng từ giả riêng; không có chuyển tiền thật.';
    await adminPage.getByLabel('Tham chiếu hoàn tiền', { exact: true }).fill(refundReference);
    await adminPage.getByLabel('Ghi chú hoàn tiền nội bộ', { exact: true }).fill(refundNote);
    await adminPage.getByLabel('Tôi đã đối chiếu chứng từ và xác nhận đã trả đủ số tiền này.', { exact: true }).check();
    await adminPage.getByRole('button', { name: 'Kiểm tra chứng từ hoàn', exact: true }).click();
    let refundProof; let refundCommitted; let confirmations = 0;
    await adminPage.route(`**/api/admin/refunds/${refundId}/confirm`, async route => {
      confirmations++;
      if (confirmations === 1) { refundProof = route.request().postDataJSON(); const response = await route.fetch(); expect(response.ok()).toBeTruthy(); refundCommitted = await response.json(); await route.abort('failed'); }
      else { expect(route.request().postDataJSON()).toEqual(refundProof); await route.continue(); }
    });
    await adminPage.getByRole('button', { name: 'Xác nhận đã hoàn tiền', exact: true }).click();
    await expect(adminPage.getByRole('button', { name: 'Thử lại thao tác hoàn đã gửi', exact: true })).toBeVisible();
    await adminPage.reload();
    const refundReplay = adminPage.waitForResponse(response => response.url().endsWith(`/api/admin/refunds/${refundId}/confirm`) && response.request().method() === 'POST');
    await adminPage.getByRole('button', { name: 'Thử lại thao tác hoàn đã gửi', exact: true }).click();
    const refunded = await (await refundReplay).json();
    await expect(adminPage.getByText('Đã ghi nhận chứng từ hoàn tiền.', { exact: true })).toBeVisible();
    expect(confirmations).toBe(2);
    const firstRefund = refundCommitted.items.find(item => item.id === refundId); const replayRefund = refunded.items.find(item => item.id === refundId);
    expect(replayRefund.status).toBe('SUCCEEDED'); expect(replayRefund.completedAt).toBe(firstRefund.completedAt); expect(replayRefund.processedBy).toEqual(firstRefund.processedBy);
    expect(refunded.collectedAmount).toBe(initial.totalAmount); expect(refunded.refundedAmount).toBe(initial.totalAmount); expect(refunded.pendingRefundAmount).toBe('0.00');
    for (const path of ['/admin/refunds', `/admin/orders/${orderId}/refunds`, `/admin/refunds/${refundId}`]) {
      await adminPage.goto(path); await expect(adminPage.getByRole('heading', { level: 1 })).toBeVisible();
      if (path === '/admin/refunds') { await adminPage.getByLabel('Tìm yêu cầu hoàn', { exact: true }).fill(order.orderNumber); await adminPage.getByRole('button', { name: 'Tìm kiếm', exact: true }).click(); await expect(adminPage.getByRole('link', { name: `Đối chiếu hoàn ${refundId}`, exact: true })).toBeVisible(); }
      for (const width of [1440, 1024, 768, 390, 375]) {
        await adminPage.setViewportSize({ width, height: 1000 });
        const menuButton = adminPage.locator('.store-menu-toggle');
        if (width <= 1100 && await menuButton.getAttribute('aria-expanded') !== 'true') await menuButton.click();
        const navigation = adminPage.getByRole('navigation', { name: 'Điều hướng chính' });
        const adminMenu = navigation.getByRole('button', { name: 'Quản trị' });
        if (await adminMenu.getAttribute('aria-expanded') !== 'true') await adminMenu.click();
        await expect(navigation.getByRole('link')).toHaveCount(17);
        expect(await adminPage.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), `No overflow at ${path} width ${width}`).toBe(true);
        if ([1440, 390].includes(width)) await adminPage.screenshot({ path: `test-results/tv3-refund-${path === '/admin/refunds' ? 'queue' : path.endsWith('/refunds') ? 'order' : 'detail'}-${width}.png`, fullPage: true });
      }
    }
    await customerPage.goto(`/orders/${orderId}`);
    await expect(customerPage.getByRole('region', { name: 'Hoàn tiền', exact: true })).toContainText('Đã hoàn tiền');
    await expect(customerPage.getByText(refundNote, { exact: true })).toHaveCount(0); await expect(customerPage.getByText(cancelReason, { exact: true })).toHaveCount(0);
    const publicOrder = await (await customerPage.request.get(`/api/orders/${orderId}`)).json();
    const publicRefund = publicOrder.refunds.find(item => item.id === refundId); expect(publicRefund.status).toBe('SUCCEEDED');
    for (const item of publicOrder.refunds) for (const field of ['confirmationNote', 'resolutionNote', 'processedBy']) expect(Object.hasOwn(item, field)).toBe(false);
    await customerPage.goto(`/orders/${orderId}/payments`);
    const publicFinal = await (await customerPage.request.get(`/api/orders/${orderId}/payments`)).json();
    expect(publicFinal.refundedAmount).toBe(initial.totalAmount); expect(publicFinal.pendingRefundAmount).toBe('0.00'); expect(publicFinal.items[0].status).toBe('SUCCEEDED');
    await expect(customerPage.getByText(refundNote, { exact: true })).toHaveCount(0);
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
