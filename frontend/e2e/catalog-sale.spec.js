import { test, expect } from '@playwright/test';

test('50 demo products appear on Sale and open a purchasable detail page', async ({ page, request }) => {
  const runtime = await request.get('/api/commerce/runtime');
  expect((await runtime.json()).isolatedData).toBe(true);
  const response = await request.get('/api/catalog/products?query=NN-DEMO&size=100');
  expect(response.ok()).toBe(true);
  const catalog = await response.json();
  expect(catalog.totalElements).toBe(50);
  expect(new Set(catalog.items.map(product => product.imageUrl)).size).toBe(50);

  await page.setViewportSize({ width: 1440, height: 950 });
  await page.goto('/sale?query=NN-DEMO');
  await expect(page.getByRole('heading', { name: 'Sale & tất cả sản phẩm.' })).toBeVisible();
  await expect(page.locator('.sale-card')).toHaveCount(12);
  await expect(page.getByText('50 sản phẩm')).toBeVisible();
  await page.screenshot({ path: 'test-results/catalog-sale-desktop.png', fullPage: true });
  await page.locator('.sale-card').first().click();
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
  await expect(page.getByText(/NN-DEMO-/)).toBeVisible();
  await expect(page.getByText('Chi tiết kỹ thuật')).toBeVisible();
  await expect(page.getByRole('img', { name: /Ảnh minh họa/ })).toHaveJSProperty('complete', true);
  await page.getByRole('button', { name: 'Thêm vào giỏ' }).click();
  await expect(page.getByText(/Đã thêm 1 .* vào giỏ hàng/, { exact: false }).last()).toBeVisible();
  await page.setViewportSize({ width: 390, height: 844 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true);
  await page.screenshot({ path: 'test-results/catalog-detail-mobile.png', fullPage: true });
});
