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

test('room menus show room products without cart and Home uses three different featured photos', async ({ page, request }) => {
  await page.goto('/');
  const inspiration = await page.locator('.home-inspiration img').evaluateAll(images => images.map(image => image.getAttribute('src')));
  const featured = page.locator('.home-product-grid img');
  await expect(featured).toHaveCount(3);
  await featured.first().scrollIntoViewIfNeeded();
  const sources = await featured.evaluateAll(images => images.map(image => image.getAttribute('src')));
  expect(new Set(sources).size).toBe(3);
  expect(sources.every(source => !inspiration.includes(source))).toBe(true);
  await expect.poll(() => featured.evaluateAll(images => images.every(image => image.complete && image.naturalWidth > 0))).toBe(true);

  for (const [label, slug] of [['Phòng khách', 'phong-khach'], ['Phòng ăn', 'phong-an'], ['Phòng ngủ', 'phong-ngu']]) {
    const response = await request.get(`/api/catalog/products?room=${slug}&size=100`);
    expect(response.ok()).toBe(true);
    expect((await response.json()).totalElements).toBeGreaterThan(0);
    await page.getByRole('button', { name: label, exact: true }).click();
    await page.getByRole('link', { name: `Tất cả ${label.toLowerCase()}` }).click();
    await expect(page.getByRole('heading', { name: `${label}.` })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Giỏ hàng của bạn.' })).toHaveCount(0);
    await expect(page.locator('.sale-card').first()).toBeVisible();
  }
  await page.getByRole('button', { name: 'Phòng ngủ', exact: true }).click();
  await page.getByRole('link', { name: 'Tủ', exact: true }).click();
  await expect(page.locator('.sale-card')).toHaveCount(5);
});

test('catalog keeps cards visible during filtering and returns to the top on the next page', async ({ page }) => {
  await page.goto('/sale');
  await expect(page.locator('.sale-card')).toHaveCount(12);
  await page.route('**/api/catalog/products?*', async route => {
    if (new URL(route.request().url()).searchParams.get('category') === 'demo-ban-an') {
      await new Promise(resolve => setTimeout(resolve, 350));
    }
    await route.continue();
  });
  await page.getByRole('button', { name: 'Bàn ăn' }).click();
  await expect(page.getByText('Đang cập nhật sản phẩm…')).toBeVisible();
  await expect(page.locator('.sale-card')).toHaveCount(12);
  await expect(page.locator('.sale-card')).toHaveCount(5);
  await page.getByRole('button', { name: 'Tất cả', exact: true }).click();
  await expect(page.locator('.sale-card')).toHaveCount(12);
  await page.getByRole('button', { name: 'Trang sau' }).click();
  await expect(page.getByRole('heading', { name: 'Sale & tất cả sản phẩm.' })).toBeInViewport();
});
