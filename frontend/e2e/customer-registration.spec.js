import { test, expect } from '@playwright/test';

test('a new customer can register and sign in, while duplicate email stays on the form', async ({ page, request }) => {
  const runtimeResponse = await request.get('/api/commerce/runtime');
  expect(runtimeResponse.ok()).toBeTruthy();
  const runtime = await runtimeResponse.json();
  expect(runtime.isolatedData, 'Registration E2E must use an isolated database').toBe(true);

  const suffix = `${Date.now()}${Math.floor(Math.random() * 10000)}`;
  const username = `newcustomer${suffix}`;
  const email = `${username}@example.invalid`;
  await page.goto('/register');
  await expect(page.getByRole('heading', { name: 'Đăng ký' })).toBeVisible();
  await page.getByLabel('Họ và tên').fill('Khách Hàng Mới');
  await page.getByLabel('Tên đăng nhập').fill(username);
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Mật khẩu', { exact: true }).fill('secret-pass-123');
  await page.getByLabel('Nhập lại mật khẩu').fill('secret-pass-123');
  await page.getByRole('button', { name: 'Tạo tài khoản' }).click();
  await expect(page).toHaveURL(/\/login$/);
  await expect(page.getByRole('status')).toContainText('Tài khoản đã được tạo');
  await expect(page.getByLabel('Tên đăng nhập')).toHaveValue(username);

  await page.goto('/register');
  await page.getByLabel('Họ và tên').fill('Người Dùng Khác');
  await page.getByLabel('Tên đăng nhập').fill(`${username}other`);
  await page.getByLabel('Email').fill(email.toUpperCase());
  await page.getByLabel('Mật khẩu', { exact: true }).fill('secret-pass-123');
  await page.getByLabel('Nhập lại mật khẩu').fill('secret-pass-123');
  await page.getByRole('button', { name: 'Tạo tài khoản' }).click();
  await expect(page.getByText('Email đã được sử dụng.', { exact: true })).toBeVisible();
  await expect(page.getByLabel('Email')).toHaveValue(email.toUpperCase());

  await page.goto('/login');
  await page.getByLabel('Tên đăng nhập').fill(username);
  await page.getByLabel('Mật khẩu', { exact: true }).fill('secret-pass-123');
  await page.getByRole('button', { name: 'Đăng nhập', exact: true }).click();
  await expect(page).toHaveURL(/\/cart$/);
  const session = await (await page.request.get('/api/session')).json();
  expect(session.authenticated).toBe(true);
  expect(session.user.username).toBe(username);
  expect(session.user.roles).toEqual(['CUSTOMER']);
});
