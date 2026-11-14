import React from 'react';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, expect, it, vi } from 'vitest';
import { SessionContext } from '../../lib/session';
import OrderDispatchPage from './OrderDispatchPage';

afterEach(() => { sessionStorage.clear(); vi.unstubAllGlobals(); });

it('retries an uncertain dispatch with the exact order, body and key', async () => {
  vi.stubGlobal('crypto', { randomUUID: () => 'dispatch-request-001' });
  const calls = [];
  const api = { request: async (path, options = {}) => {
    if (path.startsWith('/admin/inventory/orders?')) return { items: [{ orderId: 8, orderNumber: 'ORD-008', status: 'CONFIRMED', remainingQuantity: 2 }], page: 0, size: 20, totalPages: 1 };
    if (path === '/admin/inventory/orders/8/holds') return { orderId: 8, orderNumber: 'ORD-008', status: 'CONFIRMED', holds: [
      { reservationId: 19, orderItemId: 20, warehouseId: 3, warehouseCode: 'MAIN', sku: 'SOFA-KEM', quantity: 2, remainingQuantity: 2 },
    ] };
    if (path === '/admin/inventory/orders/8/dispatch' && options.method === 'POST') {
      calls.push({ path, options });
      if (calls.length === 1) throw new Error('Mất kết nối khi xuất kho');
      return { replayed: true, shipmentId: 40, trackingNumber: 'VAN-001', status: 'IN_TRANSIT', items: [{ quantity: 2 }] };
    }
    throw new Error(`Unexpected request ${path}`);
  } };
  render(<SessionContext.Provider value={{ api, session: { user: { id: 7, roles: ['STAFF'] } } }}><OrderDispatchPage /></SessionContext.Provider>);
  const user = userEvent.setup();
  await screen.findByText('ORD-008');
  await user.click(screen.getByRole('button', { name: 'Xem hàng giữ' }));
  await screen.findByText('SOFA-KEM');
  await user.selectOptions(screen.getByLabelText('Kho xuất'), '3');
  await user.type(screen.getByLabelText('Đơn vị vận chuyển'), 'Nội bộ');
  await user.type(screen.getByLabelText('Mã vận đơn'), 'VAN-001');
  await user.click(screen.getByRole('button', { name: 'Xác nhận xuất kho' }));
  await screen.findByText('Mất kết nối khi xuất kho');
  expect(sessionStorage.getItem('tv4.dispatch.pending.7')).toContain('dispatch-request-001');
  await user.click(screen.getByRole('button', { name: 'Kiểm tra và gửi lại' }));
  await screen.findByText(/Phiếu xuất đã được ghi nhận trước đó/);
  expect(calls).toHaveLength(2);
  expect(calls[0]).toEqual(calls[1]);
  await waitFor(() => expect(sessionStorage.getItem('tv4.dispatch.pending.7')).toBeNull());
});
