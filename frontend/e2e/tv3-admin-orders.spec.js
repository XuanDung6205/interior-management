import { test, expect } from '@playwright/test';

test.beforeEach(async ({ request }) => {
  const response = await request.get('/api/commerce/runtime');
  expect(response.ok()).toBeTruthy();
  const runtime = await response.json();
  expect(runtime.application).toBe('tv3-commerce');
  expect(runtime.isolatedData, 'Admin order E2E must only mutate an isolated development/test database').toBe(true);
});

async function login(page, username) {
  await page.goto('/login');
  await page.getByLabel('Tên đăng nhập', { exact: true }).fill(username);
  await page.getByLabel('Mật khẩu', { exact: true }).fill(process.env.TV3_DEMO_PASSWORD);
  await page.getByRole('button', { name: 'Đăng nhập', exact: true }).click();
  await expect(page).toHaveURL(/\/cart$/);
}

test('staff searches an own test order, edits private notes, confirms/processes and cancels with public history', async ({ page: customerPage, browser, baseURL }) => {
  test.setTimeout(60000);
  test.skip(!process.env.TV3_DEMO_PASSWORD, 'Provide isolated demo credentials through the environment.');
  const adminContext = await browser.newContext({ baseURL, viewport: { width: 1440, height: 1000 }, timezoneId: 'Asia/Bangkok' });
  const adminPage = await adminContext.newPage();
  const errors = []; customerPage.on('pageerror', error => errors.push(error.message)); adminPage.on('pageerror', error => errors.push(error.message));
  let createdId; let cancelled = false;
  try {
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
    for (const [label, value] of [['Họ tên người nhận', 'Khách kiểm thử TV3-07'], ['Số điện thoại', '0900000000'], ['Địa chỉ', '12 Đường Kiểm Thử'], ['Phường / xã', 'Phường 1'], ['Quận / huyện', 'Quận 3'], ['Tỉnh / thành phố', 'Hồ Chí Minh']]) await customerPage.getByLabel(label, { exact: true }).fill(value);
    await customerPage.getByLabel('Yêu cầu lắp đặt', { exact: true }).check();
    await customerPage.getByLabel('Ghi chú cho cửa hàng', { exact: true }).fill('E2E TV3-07 isolated order; cancel after admin verification.');
    await customerPage.getByRole('button', { name: 'Xem trước đơn hàng', exact: true }).click();
    await customerPage.getByLabel('Tôi đã kiểm tra thông tin và tổng tiền.', { exact: true }).check();
    const creation = customerPage.waitForResponse(response => response.url().endsWith('/api/checkout/orders') && response.request().method() === 'POST');
    await customerPage.getByRole('button', { name: 'Đặt hàng', exact: true }).click();
    const created = await creation; expect(created.status()).toBe(201);
    const order = await created.json(); createdId = order.id;
    await expect(customerPage.getByRole('heading', { name: order.orderNumber, exact: true })).toBeVisible();

    await login(adminPage, process.env.TV3_ADMIN_USER || 'tv3.admin');
    await adminPage.getByRole('button', { name: 'Quản trị', exact: true }).click();
    await adminPage.getByRole('link', { name: 'Quản lý đơn hàng', exact: true }).click();
    await adminPage.getByLabel('Tìm đơn hàng', { exact: true }).fill(order.orderNumber);
    await adminPage.getByRole('button', { name: 'Tìm kiếm', exact: true }).click();
    await adminPage.getByLabel('Trạng thái đơn hàng', { exact: true }).selectOption('PENDING_CONFIRMATION');
    const viewOrder = adminPage.getByRole('link', { name: `Quản lý đơn ${order.orderNumber}`, exact: true });
    await expect(viewOrder).toBeVisible();
    await adminPage.screenshot({ path: 'test-results/tv3-admin-orders-desktop.png', fullPage: true });
    await adminPage.setViewportSize({ width: 390, height: 844 });
    expect(await adminPage.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true);
    await adminPage.screenshot({ path: 'test-results/tv3-admin-orders-mobile.png', fullPage: true });
    await viewOrder.click();
    await expect(adminPage.getByRole('heading', { name: order.orderNumber, exact: true })).toBeVisible();
    await expect(adminPage.getByText('Có chọn dịch vụ lắp đặt.', { exact: true })).toBeVisible();
    await expect(adminPage.getByText('Không cần lắp đặt theo kỹ thuật.', { exact: true })).toBeVisible();
    const internal = 'E2E07 Nội bộ: kiểm tra kho riêng.';
    await adminPage.getByLabel('Ghi chú nội bộ', { exact: true }).fill(internal);
    await adminPage.getByRole('button', { name: 'Lưu ghi chú nội bộ', exact: true }).click();
    await expect(adminPage.getByText('Đã lưu ghi chú nội bộ.', { exact: true })).toBeVisible();
    const publicHistory = adminPage.getByRole('region', { name: 'Lịch sử hiển thị cho khách hàng', exact: true });
    await expect(publicHistory).not.toContainText(internal);
    const customerDetail = await customerPage.request.get(`/api/orders/${createdId}`);
    expect(customerDetail.ok()).toBeTruthy();
    expect(Object.hasOwn(await customerDetail.json(), 'internalNote')).toBe(false);

    await adminPage.getByRole('button', { name: 'Xác nhận đơn', exact: true }).click();
    await adminPage.getByLabel('Thông điệp cho khách hàng', { exact: true }).fill('Cửa hàng đã xác nhận đơn của bạn.');
    await adminPage.getByRole('button', { name: 'Xác nhận chuyển trạng thái', exact: true }).click();
    await expect(adminPage.getByRole('button', { name: 'Bắt đầu xử lý', exact: true })).toBeVisible();
    await adminPage.getByRole('button', { name: 'Bắt đầu xử lý', exact: true }).click();
    await adminPage.getByLabel('Thông điệp cho khách hàng', { exact: true }).fill('Cửa hàng đang chuẩn bị hàng.');
    await adminPage.getByRole('button', { name: 'Xác nhận chuyển trạng thái', exact: true }).click();
    await expect(publicHistory).toContainText('Cửa hàng đang chuẩn bị hàng.');
    await expect(adminPage.locator('.blocked-transitions')).toBeVisible();
    await expect(adminPage.getByRole('button', { name: 'Hoàn tất đơn', exact: true })).toHaveCount(0);
    expect(await adminPage.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true);
    await adminPage.screenshot({ path: 'test-results/tv3-admin-order-mobile.png', fullPage: true });
    await adminPage.setViewportSize({ width: 1440, height: 1000 });
    await adminPage.screenshot({ path: 'test-results/tv3-admin-order-desktop.png', fullPage: true });

    await adminPage.getByRole('button', { name: 'Hủy đơn', exact: true }).click();
    await adminPage.getByLabel('Lý do hủy đơn (khách hàng nhìn thấy)', { exact: true }).fill('Hoàn tất kiểm thử quản trị TV3-07 trên dữ liệu cô lập.');
    await adminPage.getByRole('button', { name: 'Kiểm tra yêu cầu hủy', exact: true }).click();
    const cancellation = adminPage.waitForResponse(response => response.url().endsWith(`/api/admin/orders/${createdId}/transitions`) && response.request().method() === 'POST');
    await adminPage.getByRole('button', { name: 'Xác nhận hủy đơn', exact: true }).click();
    const cancelledResponse = await cancellation; expect(cancelledResponse.ok()).toBeTruthy(); expect((await cancelledResponse.json()).status).toBe('CANCELLED'); cancelled = true;
    await expect(adminPage.getByText('Không còn thao tác chuyển trạng thái cho đơn này.', { exact: true })).toBeVisible();
    await customerPage.reload();
    await expect(customerPage.getByText('Hoàn tất kiểm thử quản trị TV3-07 trên dữ liệu cô lập.', { exact: true }).first()).toBeVisible();
    await expect(customerPage.getByText(internal, { exact: true })).toHaveCount(0);
    expect(errors).toEqual([]);
  } finally {
    // Preserve the historical order and release only this test's own holds.
    if (createdId && !cancelled) {
      const detailResponse = await adminPage.request.get(`/api/admin/orders/${createdId}`);
      if (detailResponse.ok()) {
        const latest = await detailResponse.json();
        if (latest.status !== 'CANCELLED') {
          const csrf = await (await adminPage.request.get('/api/csrf')).json();
          const response = await adminPage.request.post(`/api/admin/orders/${createdId}/transitions`, { headers: { [csrf.headerName]: csrf.token }, data: { fromStatus: latest.status, toStatus: 'CANCELLED', note: 'Dọn giữ hàng của kiểm thử TV3-07.' } });
          expect(response.ok(), 'Cancel only the order created by this isolated E2E').toBeTruthy();
        }
      } else {
        const csrf = await (await customerPage.request.get('/api/csrf')).json();
        const response = await customerPage.request.post(`/api/orders/${createdId}/cancel`, { headers: { [csrf.headerName]: csrf.token }, data: { reason: 'Dọn giữ hàng của kiểm thử TV3-07.' } });
        expect(response.ok(), 'Customer cancellation before staff processing').toBeTruthy();
      }
    }
    await adminContext.close();
  }
});
