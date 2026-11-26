import { useRef, useState } from 'react';
import { ErrorNotice } from '../../components/ui';
import { formatDate } from '../../lib/format';

const PREFIX = '/admin/operations/vector';
const errorLabels = { EMBEDDING_UNAVAILABLE: 'Chưa kết nối được dịch vụ embedding.', EMBEDDING_INVALID: 'Dịch vụ trả dữ liệu vector chưa hợp lệ.', CATALOG_CHANGED: 'Sản phẩm đã đổi; cần lập chỉ mục lại.', LEASE_EXPIRED: 'Tác vụ bị gián đoạn và có thể chạy lại.' };

export default function VectorOperationsPanel({ api }) {
  const [data, setData] = useState(null), [error, setError] = useState(null), [result, setResult] = useState(null);
  const [busy, setBusy] = useState(false), [cursor, setCursor] = useState(0), [hasMore, setHasMore] = useState(true), [reasons, setReasons] = useState({});
  const lock = useRef(false);
  const ready = data?.enabled === true && data?.configured === true;
  async function perform(action = 'status', eventId) {
    if (lock.current || action !== 'status' && !ready) return;
    const reason = reasons[eventId]?.trim();
    if (action === 'retry' && (!reason || reason.length > 1000)) return;
    lock.current = true; setBusy(true); setError(null);
    try {
      if (action === 'status') setData(await api.request(PREFIX));
      else {
        const path = action === 'enqueue' ? '/reindex' : action === 'retry' ? `/events/${encodeURIComponent(eventId)}/retry` : '/run';
        const body = action === 'enqueue' ? { afterId: cursor, limit: 25 } : action === 'retry' ? { reason } : {};
        const response = await api.request(PREFIX + path, { method: 'POST', body });
        if (action === 'enqueue') {
          if (!Number.isSafeInteger(response.nextAfterId) || response.nextAfterId < cursor || typeof response.hasMore !== 'boolean') throw new Error('Chưa đọc được kết quả xếp hàng. Giữ nguyên trang hiện tại và tải lại trạng thái.');
          setCursor(response.nextAfterId); setHasMore(response.hasMore);
        }
        setResult({ action, ...response });
        if (action === 'retry') setReasons(value => ({ ...value, [eventId]: '' }));
        setData(action === 'retry' && response.outbox ? response : await api.request(PREFIX));
      }
    } catch (failure) { setError(failure); }
    finally { lock.current = false; setBusy(false); }
  }
  return <section className="panel order-section-body" aria-labelledby="vector-operations-title">
    <div className="panel-heading"><h2 id="vector-operations-title">Vector catalog và reindex</h2><button className="button secondary" disabled={busy} onClick={() => perform()}>{busy ? 'Đang xử lý vector…' : 'Xem trạng thái vector'}</button></div>
    <p>Lập chỉ mục nội dung sản phẩm để hỗ trợ tìm kiếm theo ý nghĩa. Giá, trạng thái bán và tồn kho vẫn được lấy từ database hiện hành.</p>
    <ErrorNotice error={error} />
    {data && <>
      {!data.enabled ? <p className="notice warning">Chưa bật dịch vụ vector. Các chức năng catalog và đặt hàng vẫn sử dụng dữ liệu hiện hành.</p> : !data.configured ? <p className="notice warning">Dịch vụ vector chưa có khóa AI. Nhờ quản trị viên cấu hình trên máy chủ trước khi chạy.</p> : <p className="notice">Dịch vụ vector đã sẵn sàng. Mỗi lượt xử lý tối đa {data.batchSize} tác vụ.</p>}
      <p className="muted small">Mô hình: {data.model} · {data.dimensions} chiều · Chu kỳ {data.intervalSeconds} giây.</p>
      <dl className="operations-metrics">{[['Sản phẩm đã lập chỉ mục', data.indexedProducts], ['Chờ xử lý', data.outbox?.pending], ['Chờ thử lại', data.outbox?.retry], ['Đã xử lý', data.outbox?.sent], ['Cần xử lý', data.outbox?.dead], ['Đang chạy', data.outbox?.leased]].map(([name, value]) => <div key={name}><dt>{name}</dt><dd>{value ?? '—'}</dd></div>)}</dl>
      <p>Xếp hàng chưa đồng nghĩa đã lập chỉ mục. Tác vụ được lưu để tiếp tục sau khi khởi động lại máy chủ.</p>
      <div className="actions"><button className="button secondary" disabled={busy || !ready || !hasMore} onClick={() => perform('enqueue')}>{cursor === 0 ? 'Xếp 25 sản phẩm đầu tiên' : 'Xếp tối đa 25 sản phẩm tiếp theo'}</button><button className="button secondary" disabled={busy || !ready} onClick={() => perform('run')}>Chạy một lượt reindex</button>{cursor > 0 && <button className="text-button" disabled={busy} onClick={() => { setCursor(0); setHasMore(true); }}>Quay lại trang đầu</button>}</div>
      {!hasMore && <p>Đã duyệt hết trang sản phẩm hiện có.</p>}
      {(data.deadEvents || []).map(item => <form key={item.id} className="receipt-review" onSubmit={event => { event.preventDefault(); perform('retry', item.id); }}>
        <h3>Tác vụ vector #{item.id} · Sản phẩm #{item.productId}</h3>
        <p>{item.attempts} lần thử · {errorLabels[item.lastError] || 'Tác vụ chưa hoàn tất; cần kiểm tra rồi xếp lại.'}{item.updatedAt ? ` · ${formatDate(item.updatedAt)}` : ''}</p>
        <label htmlFor={`vector-retry-${item.id}`}>Lý do xếp lại tác vụ vector #{item.id}</label><textarea id={`vector-retry-${item.id}`} required maxLength={1000} disabled={busy} value={reasons[item.id] || ''} onChange={event => setReasons(value => ({ ...value, [item.id]: event.target.value }))} />
        <button className="button secondary" disabled={busy || !ready || !reasons[item.id]?.trim()}>Xếp lại tác vụ vector #{item.id}</button>
      </form>)}
    </>}
    {result && <div className="notice" role="status" aria-atomic="true">{result.action === 'enqueue' ? <p>Đã xếp hàng: {result.queued} · Bỏ qua: {result.skipped}.</p> : result.action === 'run' ? <><p>Đã quét {result.scanned} tác vụ.</p><p>Đã lập chỉ mục: {result.indexed} · Thử lại: {result.retried} · Lỗi: {result.failed}.</p></> : <p>Đã nhận yêu cầu xếp lại tác vụ. Tải trạng thái để theo dõi kết quả.</p>}</div>}
  </section>;
}
