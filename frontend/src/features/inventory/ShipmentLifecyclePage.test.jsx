import React from 'react';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, expect, it, vi } from 'vitest';
import { SessionContext } from '../../lib/session';
import ShipmentLifecyclePage from './ShipmentLifecyclePage';

afterEach(() => { sessionStorage.clear(); vi.unstubAllGlobals(); });

it('retries a returned shipment with the same body and key after losing the response', async () => {
  vi.stubGlobal('crypto', { randomUUID: () => 'shipment-status-001' });
  const calls = [];
  const api = { request: async (path, options = {}) => {
    if (path.startsWith('/admin/inventory/shipments?')) return { items: [{ id: 12, orderId: 8,
      orderNumber: 'ORD-008', warehouseId: 3, warehouseCode: 'MAIN', carrier: 'Nội bộ',
      trackingNumber: 'VAN-001', status: 'FAILED', shippedAt: '2026-11-14T10:00:00Z' }],
    page: 0, size: 20, totalPages: 1 };
    if (path === '/admin/inventory/shipments/12/status' && options.method === 'POST') {
      calls.push(options);
      if (calls.length === 1) throw new Error('Mất kết nối khi hoàn hàng');
      return { id: 12, trackingNumber: 'VAN-001', status: 'RETURNED', replayed: true };
    }
    throw new Error(`Unexpected request ${path}`);
  } };
  render(<SessionContext.Provider value={{ api, session: { user: { id: 7, roles: ['STAFF'] } } }}><ShipmentLifecyclePage /></SessionContext.Provider>);
  const user = userEvent.setup();
  await screen.findByText(/ORD-008/);
  await user.click(screen.getByRole('button', { name: 'Cập nhật giao hàng' }));
  await user.selectOptions(screen.getByLabelText('Kết quả giao hàng'), 'RETURNED');
  await user.type(screen.getByLabelText('Lý do / xác nhận'), 'Hàng đã về kho');
  const submit = screen.getByRole('button', { name: 'Xác nhận cập nhật' });
  expect(submit).toBeDisabled();
  await user.click(screen.getByRole('checkbox', { name: /Tôi xác nhận hàng đã thực sự về kho/ }));
  await user.click(submit);
  await screen.findByText('Mất kết nối khi hoàn hàng');
  expect(sessionStorage.getItem('tv4.shipment.pending.7')).toContain('shipment-status-001');
  await user.click(screen.getByRole('button', { name: 'Kiểm tra và gửi lại' }));
  await screen.findByText(/Cập nhật đã được ghi nhận trước đó/);
  expect(calls).toHaveLength(2);
  expect(calls[0]).toEqual(calls[1]);
  await waitFor(() => expect(sessionStorage.getItem('tv4.shipment.pending.7')).toBeNull());
});
