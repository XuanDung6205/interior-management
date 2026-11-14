import React from 'react';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, expect, it, vi } from 'vitest';
import { SessionContext } from '../../lib/session';
import StockOperationsPage from './StockOperationsPage';

afterEach(() => { sessionStorage.clear(); vi.unstubAllGlobals(); });

it('retries a transfer with identical body and key after an uncertain response', async () => {
  vi.stubGlobal('crypto', { randomUUID: () => 'stock-request-key-001' });
  const calls = [];
  const api = { request: async (path, options = {}) => {
    if (path === '/admin/inventory/warehouses') return [{ id: 3, code: 'MAIN', name: 'Kho chính' }, { id: 4, code: 'WEST', name: 'Kho tây' }];
    if (path.startsWith('/commerce/catalog/variants?')) return { items: [{ id: 12, sku: 'SOFA-KEM', productName: 'Sofa', variantName: 'Kem' }] };
    if (path.startsWith('/admin/inventory/movements?')) return { items: [], page: 0, size: 20, totalPages: 0 };
    if (path.startsWith('/admin/inventory/balances?')) return { warehouseId: path.includes('warehouseId=3') ? 3 : 4,
      warehouseCode: 'MAIN', onHandQuantity: 10, reservedQuantity: 2, availableQuantity: 8 };
    if (path === '/admin/inventory/transfers' && options.method === 'POST') {
      calls.push(options);
      if (calls.length === 1) throw new Error('Mất kết nối khi chuyển kho');
      return { replayed: true, source: { availableQuantity: 6 }, destination: { availableQuantity: 2 } };
    }
    throw new Error(`Unexpected request ${path}`);
  } };
  render(<SessionContext.Provider value={{ api, session: { user: { id: 7, roles: ['STAFF'] } } }}><StockOperationsPage /></SessionContext.Provider>);
  const user = userEvent.setup();
  await screen.findByRole('option', { name: 'WEST — Kho tây' });
  await user.selectOptions(screen.getByLabelText('Loại thao tác'), 'transfer');
  await user.selectOptions(screen.getByLabelText('Kho nguồn'), '3');
  await user.selectOptions(screen.getByLabelText('Kho đích'), '4');
  await user.selectOptions(screen.getByLabelText('SKU'), '12');
  await user.type(screen.getByLabelText('Số lượng'), '2');
  await user.type(screen.getByLabelText('Mã chứng từ'), 'CK-001');
  await user.type(screen.getByLabelText('Lý do'), 'Bổ sung kho tây');
  await user.click(screen.getByRole('button', { name: 'Xác nhận biến động' }));
  await screen.findByText('Mất kết nối khi chuyển kho');
  expect(sessionStorage.getItem('tv4.stock.pending.7')).toContain('stock-request-key-001');
  await user.click(screen.getByRole('button', { name: 'Kiểm tra và gửi lại' }));
  await screen.findByText(/Thao tác đã được ghi nhận trước đó/);
  expect(calls).toHaveLength(2);
  expect(calls[0].headers['Idempotency-Key']).toBe(calls[1].headers['Idempotency-Key']);
  expect(calls[0].body).toEqual(calls[1].body);
  await waitFor(() => expect(sessionStorage.getItem('tv4.stock.pending.7')).toBeNull());
});
