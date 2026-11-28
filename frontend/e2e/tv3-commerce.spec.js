import { test, expect } from '@playwright/test';

test.beforeEach(async ({ request }) => {
  const response = await request.get('/api/commerce/runtime');
  expect(response.ok()).toBeTruthy();
  const runtime = await response.json();
  expect(runtime.application).toBe('tv3-commerce');
  expect(runtime.isolatedData, 'Never run browser mutations against production data').toBe(true);
});

test('guest cart persists, edits, renders mobile and removes items', async ({ page }) => {
  const errors = []; page.on('pageerror', error => errors.push(error.message));
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto('/cart');
  await page.getByRole('button', { name: 'Thêm Sofa Modena vào giỏ', exact: true }).click();
  const cart = page.getByRole('region', { name: 'Sản phẩm trong giỏ', exact: true });
  await expect(cart.getByRole('heading', { name: 'Sofa Modena', exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Tăng số lượng Sofa Modena', exact: true }).click();
  await expect(page.getByRole('textbox', { name: 'Số lượng Sofa Modena', exact: true })).toHaveValue('2');
  await page.reload();
  await expect(page.getByRole('textbox', { name: 'Số lượng Sofa Modena', exact: true })).toHaveValue('2');
  await page.screenshot({ path: 'test-results/tv3-cart-desktop.png', fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(cart.getByRole('heading', { name: 'Sofa Modena', exact: true })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1)).toBe(true);
  await page.screenshot({ path: 'test-results/tv3-cart-mobile.png', fullPage: true });
  await page.getByRole('button', { name: 'Xóa Sofa Modena', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Giỏ hàng đang chờ bạn', exact: true })).toBeVisible();
  expect(errors).toEqual([]);
});

test('real session merges guest cart and coupon admin create/preview/edit/toggle/delete works', async ({ page }) => {
  test.skip(!process.env.TV3_DEMO_PASSWORD, 'Provide isolated demo password through environment.');
  const code = `E2E_${Date.now()}`;
  let couponId;
  const errors = []; page.on('pageerror', error => errors.push(error.message));
  try {
    await page.goto('/cart');
    await page.getByRole('button', { name: 'Thêm Bàn trà Oslo vào giỏ', exact: true }).click();
    await page.getByRole('link', { name: 'Đăng nhập', exact: true }).first().click();
    // Guest carts merge into customer accounts; ADMIN accounts no longer use the storefront and land on /admin.
    await page.getByLabel('Tên đăng nhập', { exact: true }).fill('tv3.customer');
    await page.getByLabel('Mật khẩu', { exact: true }).fill(process.env.TV3_DEMO_PASSWORD);
    await page.getByRole('button', { name: 'Đăng nhập', exact: true }).click();
    await expect(page).toHaveURL(/\/cart$/);
    await expect(page.getByRole('region', { name: 'Sản phẩm trong giỏ', exact: true }).getByRole('heading', { name: 'Bàn trà Oslo', exact: true })).toBeVisible();
    await page.context().clearCookies();
    await page.goto('/login');
    await page.getByLabel('Tên đăng nhập', { exact: true }).fill('tv3.admin');
    await page.getByLabel('Mật khẩu', { exact: true }).fill(process.env.TV3_DEMO_PASSWORD);
    await page.getByRole('button', { name: 'Đăng nhập', exact: true }).click();
    await expect(page).toHaveURL(/\/admin$/);
    await page.getByRole('button', { name: 'Quản trị', exact: true }).click();
    await page.getByRole('link', { name: 'Quản lý ưu đãi', exact: true }).click();
    await page.getByRole('button', { name: 'Tạo mã mới', exact: true }).click();
    await page.getByLabel('Mã ưu đãi', { exact: true }).fill(code);
    await page.getByLabel('Tên chương trình', { exact: true }).fill('Kiểm thử trình duyệt TV3');
    await page.getByLabel('Giá trị giảm (%)', { exact: true }).fill('10');
    await page.getByLabel('Bắt đầu', { exact: true }).fill('2020-01-01T00:00');
    await page.getByLabel('Kết thúc', { exact: true }).fill('2030-01-01T00:00');
    const created = page.waitForResponse(response => response.url().endsWith('/api/admin/coupons') && response.request().method() === 'POST');
    await page.getByRole('button', { name: 'Tạo mã ưu đãi', exact: true }).click();
    const response = await created; expect(response.status()).toBe(201); couponId = (await response.json()).id;
    await expect(page.getByRole('heading', { name: code, exact: true })).toBeVisible();
    await page.getByRole('button', { name: `Xem thử ${code}`, exact: true }).click();
    await page.getByLabel('Tiền hàng để tính thử (₫)', { exact: true }).fill('1000');
    await page.getByRole('button', { name: 'Tính ưu đãi', exact: true }).click();
    await expect(page.locator('.preview-result')).toContainText('100');
    await page.getByRole('button', { name: 'Đóng xem thử', exact: true }).click();
    await page.getByRole('button', { name: `Sửa ${code}`, exact: true }).click();
    await page.getByLabel('Tên chương trình', { exact: true }).fill('Đã cập nhật');
    await page.getByRole('button', { name: 'Lưu thay đổi', exact: true }).click();
    await expect(page.getByText('Đã cập nhật', { exact: true })).toBeVisible();
    await page.getByRole('button', { name: `Tắt ${code}`, exact: true }).click();
    await expect(page.getByRole('button', { name: `Bật ${code}`, exact: true })).toBeVisible();
    await page.screenshot({ path: 'test-results/tv3-coupons-desktop.png', fullPage: true });
    await page.setViewportSize({ width: 390, height: 844 });
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1)).toBe(true);
    await page.screenshot({ path: 'test-results/tv3-coupons-mobile.png', fullPage: true });
    await page.getByRole('button', { name: `Xóa ${code}`, exact: true }).click();
    await page.getByRole('button', { name: 'Xác nhận xóa', exact: true }).click();
    await expect(page.getByRole('heading', { name: code, exact: true })).toHaveCount(0); couponId = undefined;
    await page.getByRole('button', { name: 'Tài khoản', exact: true }).click();
    await page.getByRole('button', { name: 'Đăng xuất', exact: true }).click();
    await expect(page.getByRole('link', { name: 'Quản lý ưu đãi', exact: true })).toHaveCount(0);
    expect(errors).toEqual([]);
  } finally {
    if (couponId) {
      const csrfResponse = await page.request.get('/api/csrf');
      const csrf = await csrfResponse.json();
      await page.request.delete(`/api/admin/coupons/${couponId}`, { headers: { [csrf.headerName]: csrf.token } });
    }
  }
});
