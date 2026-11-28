import { test, expect } from '@playwright/test';

// Only read/mutate the isolated Docker demo, never a production catalog.
test.beforeEach(async ({ request }) => {
  expect((await (await request.get('/api/commerce/runtime')).json()).isolatedData).toBe(true);
});

test('TV2 filters, collection and SKU gallery work at desktop and phone widths', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 950 });
  await page.goto('/collections/demo-an-yen');
  await expect(page.getByRole('heading', { name: 'Góc nghỉ an yên.' })).toBeVisible();
  await expect(page.locator('.sale-card').first()).toBeVisible();
  await page.getByRole('combobox', { name: 'Chất liệu', exact: true }).selectOption('demo-vai-boc');
  await page.getByRole('button', { name: 'Áp dụng bộ lọc' }).click();
  await expect(page).toHaveURL(/material=demo-vai-boc/);
  await page.goto('/products/demo-sofa-01-01');
  await expect(page.getByRole('button', { name: /Be ấm \/ 2250 mm/ })).toBeVisible();
  await page.getByRole('button', { name: /Be ấm \/ 2250 mm/ }).click();
  await expect(page.getByRole('button', { name: /Be ấm \/ 2250 mm/ })).toHaveAttribute('aria-pressed', 'true');
  await page.getByRole('button', { name: 'Xem ảnh 2' }).click();
  await expect(page.locator('.detail-image-note')).toContainText('Ảnh tham khảo bố trí');
  await page.getByRole('button', { name: 'Thêm vào giỏ' }).click();
  await expect(page.locator('.detail-notice')).toContainText('Đã thêm 1');
  await page.setViewportSize({ width: 390, height: 844 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true);
  await page.screenshot({ path: 'test-results/tv2-detail-mobile.png', fullPage: true });
  await page.goto('/sale');
  await expect(page.locator('.sale-card')).toHaveCount(12);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true);
  await page.screenshot({ path: 'test-results/tv2-list-mobile.png', fullPage: true });
});

test('TV2 admin editor persists a decimal price and responsive taxonomy form', async ({ page }) => {
  test.skip(!process.env.TV3_DEMO_PASSWORD, 'Supply the isolated demo password through environment.');
  await page.goto('/login');
  await page.getByLabel('Tên đăng nhập').fill(process.env.TV2_E2E_ADMIN_USERNAME || 'tv3.admin');
  await page.getByLabel('Mật khẩu', { exact: true }).fill(process.env.TV3_DEMO_PASSWORD);
  await page.getByRole('button', { name: 'Đăng nhập', exact: true }).click();
  await expect(page).not.toHaveURL(/\/login/);
  await page.goto('/admin/catalog');
  await page.getByLabel('Tìm trong catalog').fill('NN-DEMO-01-01');
  await page.getByRole('button', { name: 'Tìm kiếm', exact: true }).click();
  await page.getByRole('button', { name: 'Sửa Sofa vải Mộc An', exact: true }).click();
  await page.getByLabel('Giá bán (₫) *', { exact: true }).first().fill('12900000.00');
  await page.getByRole('button', { name: 'Lưu sản phẩm', exact: true }).click();
  await expect(page.getByText('Đã lưu sản phẩm và các phiên bản.')).toBeVisible();
  await page.screenshot({ path: 'test-results/tv2-admin-desktop.png', fullPage: true });
  await page.getByRole('button', { name: 'Phân loại', exact: true }).click();
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(page.getByLabel('Tên phân loại *')).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true);
  await page.screenshot({ path: 'test-results/tv2-admin-mobile.png', fullPage: true });
});
