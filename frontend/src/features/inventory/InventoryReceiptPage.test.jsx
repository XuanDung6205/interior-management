import React from 'react';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, expect, it, vi } from 'vitest';
import { SessionContext } from '../../lib/session';
import InventoryReceiptPage from './InventoryReceiptPage';

afterEach(() => { sessionStorage.clear(); vi.unstubAllGlobals(); });

it('retries an uncertain receipt with the same body and idempotency key', async () => {
  vi.stubGlobal('crypto', { randomUUID: () => 'receipt-request-key-001' });
  const calls = [];
  let attempts = 0;
  const api = { request: async (path, options = {}) => {
    if (path === '/admin/inventory/warehouses') return [{ id: 3, code: 'MAIN', name: 'Kho chính' }];
    if (path.startsWith('/commerce/catalog/variants?')) return { items: [{ id: 12, sku: 'SOFA-KEM', productName: 'Sofa', variantName: 'Kem' }] };
    if (path.startsWith('/admin/inventory/receipts?')) return { items: [], page: 0, size: 20, totalPages: 0 };
    if (path === '/admin/inventory/receipts' && options.method === 'POST') {
      calls.push(options);
      if (++attempts === 1) throw new Error('Mất kết nối khi nhập hàng');
      return { movementId: 9, warehouseCode: 'MAIN', sku: 'SOFA-KEM', receivedQuantity: 4,
        onHandQuantity: 14, availableQuantity: 10, replayed: true };
    }
    throw new Error(`Unexpected request ${path}`);
  } };
  const session = { authenticated: true, user: { id: 7, roles: ['STAFF'] } };
  render(<SessionContext.Provider value={{ api, session }}><InventoryReceiptPage /></SessionContext.Provider>);
  const user = userEvent.setup();
  await screen.findByRole('option', { name: 'MAIN — Kho chính' });
  await user.selectOptions(screen.getByLabelText('Kho nhận hàng'), '3');
  await user.selectOptions(screen.getByLabelText('SKU'), '12');
  await user.type(screen.getByLabelText('Số lượng nhập'), '4');
  await user.type(screen.getByLabelText('Mã chứng từ'), 'PN-004');
  await user.type(screen.getByLabelText('Ghi chú nguồn hàng'), 'Nhà cung cấp');
  await user.click(screen.getByRole('button', { name: 'Xác nhận nhập kho' }));
  await screen.findByText('Mất kết nối khi nhập hàng');
  expect(sessionStorage.getItem('tv4.receipt.pending.7')).toContain('receipt-request-key-001');
  await user.click(screen.getByRole('button', { name: 'Kiểm tra và gửi lại' }));
  await screen.findByText(/Phiếu đã được ghi nhận trước đó/);
  expect(calls).toHaveLength(2);
  expect(calls[0].headers['Idempotency-Key']).toBe(calls[1].headers['Idempotency-Key']);
  expect(calls[0].body).toEqual(calls[1].body);
  await waitFor(() => expect(sessionStorage.getItem('tv4.receipt.pending.7')).toBeNull());
});
