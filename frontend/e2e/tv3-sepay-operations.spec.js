import { test, expect } from '@playwright/test';
import { staff } from '../src/test/fixtures.js';
import { paymentList, staffPaymentSummary } from '../src/test/paymentFixtures.js';

// Compiled UI contract/viewport check. Provider and finance behavior is covered by
// the separate MariaDB/HTTP tests; this test does not send money or use a token.
test('SePay staff review, jobs and provider refund states stay clear on desktop and mobile', async ({ page }) => {
  const calls = [], errors = [];
  page.on('pageerror', error => errors.push(error.message));
  page.setDefaultTimeout(10000);
  let event = { id: 8, code: 'DH12345678', amount: '1000.00', reference: 'UI-TEST-REF', reason: 'TRANSFER_MISMATCH', paymentId: 31, reviewStatus: 'OPEN' };
  const payment = { ...staffPaymentSummary.items[0], provider: 'SEPAY_SANDBOX', canConfirm: false, sepay: { code: 'DH12345678', canReconcile: true } };
  const paymentSummary = { ...staffPaymentSummary, items: [payment] };
  let refund = { id: 81, orderId: 101, paymentId: 31, provider: 'SEPAY_SANDBOX', status: 'PENDING', amount: '1000.00', reason: 'Hoàn thử nghiệm giao diện', createdAt: '2026-11-25T02:00:00Z', canConfirm: true, canCancel: true, sepayExecution: { state: 'NOT_STARTED', canSubmit: true, canSync: false } };
  const refundSummary = () => ({ orderId: 101, orderNumber: 'NN-101', orderStatus: 'CANCELLED', selectedRefundId: 81, items: [refund], payments: [], returns: [], collectedAmount: '1000.00', pendingRefundAmount: refund.status === 'SUCCEEDED' ? '0.00' : '1000.00', refundedAmount: refund.status === 'SUCCEEDED' ? '1000.00' : '0.00', availableRefundAmount: '0.00' });
  await page.route('**/api/**', async route => {
    const request = route.request(), url = new URL(request.url()), path = url.pathname;
    const method = request.method(); calls.push({ method, path }); let data;
    if (path === '/api/session') data = staff;
    else if (path === '/api/csrf') data = { token: 'ui-contract-csrf', headerName: 'X-CSRF-TOKEN' };
    else if (path === '/api/notifications/unread-count') data = { unreadCount: 0 };
    else if (path === '/api/admin/payments') data = paymentList;
    else if (path === '/api/admin/payments/sepay/events') data = { items: [event], page: 0, size: 25 };
    else if (path === '/api/admin/payments/sepay/events/8/review') { expect(request.postDataJSON()).toEqual({ note: 'Đã kiểm tra giao dịch thử nghiệm', expectedReviewStatus: 'OPEN' }); event = { ...event, reviewStatus: 'REVIEWED', reviewNote: request.postDataJSON().note, reviewedBy: 7, reviewedAt: '2026-11-25T03:00:00Z' }; data = event; }
    else if (path === '/api/admin/payments/sepay/jobs') data = { enabled: true, canRun: true, ttlSeconds: 1800, intervalSeconds: 60, batchSize: 5, queue: { pending: 2, due: 1, leased: 0 } };
    else if (path === '/api/admin/payments/sepay/jobs/run') data = { scanned: 1, reconciled: 0, expired: 0, deferred: 1, failed: 0 };
    else if (path === '/api/admin/payments/31') data = paymentSummary;
    else if (path === '/api/admin/payments/31/sepay/reconcile-code') data = { ...paymentSummary, reconciliationSummary: { checked: 2, matched: 0, complete: false } };
    else if (path === '/api/admin/refunds/81') data = refundSummary();
    else if (path === '/api/admin/refunds/81/sepay/submit') { expect(request.postDataJSON()).toEqual({ acknowledged: true }); refund = { ...refund, canConfirm: false, canCancel: false, sepayExecution: { state: 'UNKNOWN', canSubmit: false, canSync: true, refundId: 'RF-UI-TEST' } }; data = refundSummary(); }
    else if (path === '/api/admin/refunds/81/sepay/sync') { refund = { ...refund, status: 'SUCCEEDED', sepayExecution: { ...refund.sepayExecution, state: 'SUCCEEDED', canSync: false } }; data = refundSummary(); }
    else { await route.fulfill({ status: 404, json: { message: 'Unexpected UI fixture route' } }); return; }
    await route.fulfill({ json: data });
  });
  await page.setViewportSize({ width: 1440, height: 1000 }); await page.goto('/admin/payments');
  await expect(page.getByRole('heading', { name: 'Giao dịch SePay cần kiểm tra' })).toBeVisible();
  expect(calls.some(call => call.path.includes('/sepay/'))).toBe(false);
  await page.getByRole('button', { name: 'Tải sự kiện SePay' }).click();
  await page.getByLabel('Ghi chú kiểm tra sự kiện #8').fill('Đã kiểm tra giao dịch thử nghiệm');
  await page.getByRole('button', { name: 'Lưu ghi chú kiểm tra', exact: true }).click();
  await expect(page.getByText('Đã kiểm tra giao dịch thử nghiệm', { exact: true })).toBeVisible();
  await expect(page.getByLabel('Ghi chú kiểm tra sự kiện #8')).toHaveCount(0);
  await page.getByRole('button', { name: 'Xem job SePay' }).click(); await page.getByRole('button', { name: 'Chạy một lượt đối soát' }).click();
  await expect(page.getByText(/Hoãn 1 khoản/)).toBeVisible();
  await page.setViewportSize({ width: 390, height: 844 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true);
  await page.screenshot({ path: 'test-results/tv3-sepay-operations-mobile.png', fullPage: true });
  await page.goto('/admin/payments/31'); await page.getByRole('button', { name: 'Tra cứu theo mã DH12345678' }).click();
  await expect(page.getByText(/Kết quả chưa đầy đủ/)).toBeVisible();
  await page.goto('/admin/refunds/81');
  await expect(page.getByRole('button', { name: 'Gửi yêu cầu hoàn qua SePay Sandbox' })).toBeDisabled();
  await page.getByLabel('Tôi đồng ý gửi toàn bộ yêu cầu hoàn này qua SePay Sandbox.').check();
  await page.getByRole('button', { name: 'Gửi yêu cầu hoàn qua SePay Sandbox' }).click();
  await expect(page.getByLabel('Tham chiếu hoàn tiền', { exact: true })).toHaveCount(0);
  await expect(page.getByText('Chưa rõ kết quả từ SePay', { exact: true }).first()).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true);
  await page.screenshot({ path: 'test-results/tv3-sepay-provider-refund-mobile.png', fullPage: true });
  await page.getByRole('button', { name: 'Kiểm tra kết quả hoàn SePay' }).click();
  await expect(page.getByText('SePay đã xác nhận hoàn tiền thành công.', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Kiểm tra kết quả hoàn SePay' })).toHaveCount(0);
  expect(calls.filter(call => call.method === 'POST' && call.path.endsWith('/sepay/submit'))).toHaveLength(1);
  expect(errors).toEqual([]);
});
