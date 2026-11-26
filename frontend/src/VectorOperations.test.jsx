import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import VectorOperationsPanel from './features/operations/VectorOperationsPanel';

const status = (overrides = {}) => ({ enabled: true, configured: true, model: 'gemini-embedding-001', dimensions: 768, batchSize: 5, intervalSeconds: 60, indexedProducts: 2, outbox: { pending: 3, retry: 1, sent: 2, dead: 0, leased: 0 }, deadEvents: [], ...overrides });
afterEach(cleanup);

describe('vector operations', () => {
  it('does not call the server before staff requests status and blocks unconfigured work', async () => {
    const api = { request: vi.fn().mockResolvedValue(status({ enabled: false, configured: false })) };
    const user = userEvent.setup(); render(<VectorOperationsPanel api={api} />);
    expect(api.request).not.toHaveBeenCalled();
    await user.click(screen.getByRole('button', { name: 'Xem trạng thái vector' }));
    await screen.findByText(/Chưa bật dịch vụ vector/);
    expect(screen.getByRole('button', { name: 'Xếp 25 sản phẩm đầu tiên' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Chạy một lượt reindex' })).toBeDisabled();
  });
  it('queues bounded pages, advances only after confirmed response and runs one batch', async () => {
    const api = { request: vi.fn(async (path, options) => {
      if (path.endsWith('/reindex')) return { queued: 25, skipped: 0, nextAfterId: 31, hasMore: true };
      if (path.endsWith('/run')) return { scanned: 5, indexed: 4, deleted: 0, retried: 1, failed: 0, skipped: 0 };
      return status();
    }) };
    const user = userEvent.setup(); render(<VectorOperationsPanel api={api} />);
    await user.click(screen.getByRole('button', { name: 'Xem trạng thái vector' }));
    await user.click(await screen.findByRole('button', { name: 'Xếp 25 sản phẩm đầu tiên' }));
    expect(api.request).toHaveBeenCalledWith('/admin/operations/vector/reindex', { method: 'POST', body: { afterId: 0, limit: 25 } });
    await user.click(await screen.findByRole('button', { name: 'Xếp tối đa 25 sản phẩm tiếp theo' }));
    expect(api.request).toHaveBeenCalledWith('/admin/operations/vector/reindex', { method: 'POST', body: { afterId: 31, limit: 25 } });
    await user.click(screen.getByRole('button', { name: 'Chạy một lượt reindex' }));
    await screen.findByText('Đã lập chỉ mục: 4 · Thử lại: 1 · Lỗi: 0.');
    expect(api.request).toHaveBeenCalledWith('/admin/operations/vector/run', { method: 'POST', body: {} });
  });
  it('retains the same page after lost response and never presents queued work as indexed', async () => {
    const calls = [];
    const api = { request: vi.fn(async (path, options) => {
      if (path.endsWith('/reindex')) { calls.push(options.body); if (calls.length === 1) throw new Error('Mất kết nối'); return { queued: 0, skipped: 3, nextAfterId: 3, hasMore: false }; }
      return status();
    }) };
    const user = userEvent.setup(); render(<VectorOperationsPanel api={api} />);
    await user.click(screen.getByRole('button', { name: 'Xem trạng thái vector' }));
    await user.click(await screen.findByRole('button', { name: 'Xếp 25 sản phẩm đầu tiên' }));
    await screen.findByRole('alert');
    await user.click(screen.getByRole('button', { name: 'Xếp 25 sản phẩm đầu tiên' }));
    await screen.findByText('Đã xếp hàng: 0 · Bỏ qua: 3.');
    expect(calls).toEqual([{ afterId: 0, limit: 25 }, { afterId: 0, limit: 25 }]);
    expect(screen.queryByText(/Đã lập chỉ mục:/)).not.toBeInTheDocument();
  });
  it('requires a reason for DEAD retry and releases the form after definitive validation', async () => {
    let rejected = false;
    const snapshot = status({ deadEvents: [{ id: 8, productId: 41, attempts: 8, lastError: 'EMBEDDING_UNAVAILABLE', updatedAt: '2026-11-26T10:00:00Z' }] });
    const api = { request: vi.fn(async (path, options) => {
      if (path.endsWith('/events/8/retry')) { if (!rejected) { rejected = true; throw { status: 400, message: 'Cần ghi rõ lý do' }; } return snapshot; }
      return snapshot;
    }) };
    const user = userEvent.setup(); render(<VectorOperationsPanel api={api} />);
    await user.click(screen.getByRole('button', { name: 'Xem trạng thái vector' }));
    const reason = await screen.findByLabelText('Lý do xếp lại tác vụ vector #8');
    expect(screen.getByRole('button', { name: 'Xếp lại tác vụ vector #8' })).toBeDisabled();
    await user.type(reason, 'Đã khôi phục kết nối AI');
    await user.click(screen.getByRole('button', { name: 'Xếp lại tác vụ vector #8' }));
    await screen.findByRole('alert'); await waitFor(() => expect(reason).toBeEnabled());
    await user.clear(reason); await user.type(reason, 'Đã kiểm tra cấu hình');
    await user.click(screen.getByRole('button', { name: 'Xếp lại tác vụ vector #8' }));
    await waitFor(() => expect(api.request).toHaveBeenCalledWith('/admin/operations/vector/events/8/retry', { method: 'POST', body: { reason: 'Đã kiểm tra cấu hình' } }));
  });
});
