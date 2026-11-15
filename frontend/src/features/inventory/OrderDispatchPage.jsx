import { useEffect, useMemo, useState } from 'react';
import { useCommerce } from '../../lib/session';
import { ErrorNotice, Loading, Pager } from '../../components/ui';
import { inventoryApi } from './inventoryApi';

const emptyForm = () => ({ warehouseId: '', carrier: '', trackingNumber: '', note: '' });
const definitiveErrors = new Set(['INVALID_DISPATCH', 'VALIDATION_ERROR', 'IDEMPOTENCY_CONFLICT',
  'ORDER_NOT_DISPATCHABLE', 'ORDER_UNAVAILABLE', 'ORDER_CHANGED', 'NO_HELD_STOCK',
  'PAYMENT_UNVERIFIED', 'WAREHOUSE_INACTIVE', 'STOCK_RESERVATION_CONFLICT', 'TRACKING_IN_USE', 'NOT_FOUND']);

export default function OrderDispatchPage() {
  const { api, session } = useCommerce();
  const client = useMemo(() => inventoryApi(api), [api]);
  const storageKey = `tv4.dispatch.pending.${session.user.id}`;
  const [pending, setPending] = useState(() => {
    try { return JSON.parse(sessionStorage.getItem(storageKey)); } catch { return null; }
  });
  const [queue, setQueue] = useState(null);
  const [preview, setPreview] = useState(null);
  const [form, setForm] = useState(emptyForm);
  const [page, setPage] = useState(0);
  const [revision, setRevision] = useState(0);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const [notice, setNotice] = useState(null);

  useEffect(() => {
    let active = true;
    setLoading(true);
    client.dispatchQueue(page).then(value => { if (active) setQueue(value); })
      .catch(value => { if (active) setError(value); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [client, page, revision]);

  async function selectOrder(orderId) {
    setError(null); setNotice(null);
    try {
      const value = await client.dispatchHolds(orderId);
      setPreview(value);
      setForm(emptyForm());
    } catch (value) { setError(value); }
  }
  async function send(attempt) {
    setBusy(true); setError(null); setNotice(null);
    try {
      const result = await client.dispatch(attempt.orderId, attempt.body, attempt.key);
      sessionStorage.removeItem(storageKey);
      setPending(null);
      setNotice(result);
      setPreview(null);
      setForm(emptyForm());
      setPage(0);
      setRevision(value => value + 1);
    } catch (value) {
      setError(value);
      if (definitiveErrors.has(value.code)) {
        sessionStorage.removeItem(storageKey);
        setPending(null);
        setRevision(revision => revision + 1);
      }
    } finally { setBusy(false); }
  }
  function submit(event) {
    event.preventDefault();
    if (busy || pending || !preview) return;
    const attempt = { orderId: preview.orderId, key: crypto.randomUUID(), body: {
      warehouseId: Number(form.warehouseId), carrier: form.carrier.trim(),
      trackingNumber: form.trackingNumber.trim(), note: form.note.trim(),
    } };
    sessionStorage.setItem(storageKey, JSON.stringify(attempt));
    setPending(attempt);
    send(attempt);
  }
  const locations = preview ? [...new Map(preview.holds.filter(item => item.remainingQuantity > 0)
    .map(item => [item.warehouseId, { id: item.warehouseId, code: item.warehouseCode }])).values()] : [];

  return <>
    <div className="breadcrumb"><span>Quản trị</span><span>/</span><span>Kho</span><span>/</span><span>Xuất hàng</span></div>
    <div className="page-heading"><div><span className="eyebrow">GIAO HÀNG</span><h1>Xuất kho theo đơn<span className="heading-dot">.</span></h1><p>Chọn đơn và kho có hàng đã giữ. Xuất kho ghi vận đơn, giảm tồn thực tế và số lượng đang giữ trong một giao dịch.</p></div></div>
    <ErrorNotice error={error} onRetry={() => pending ? send(pending) : setRevision(value => value + 1)} />
    {pending && <div className="notice warning" role="status"><strong>Yêu cầu xuất kho đang chờ kết quả.</strong><p>Đơn #{pending.orderId}, kho #{pending.body.warehouseId}, vận đơn {pending.body.trackingNumber}. Giữ nguyên yêu cầu khi thử lại để tránh xuất hai lần.</p><button className="button secondary" disabled={busy} onClick={() => send(pending)}>{busy ? 'Đang kiểm tra…' : 'Kiểm tra và gửi lại'}</button></div>}
    {notice && <div className="notice success" role="status">{notice.replayed ? 'Phiếu xuất đã được ghi nhận trước đó.' : 'Đã xuất kho.'} Vận đơn {notice.trackingNumber}, {notice.items.reduce((total, item) => total + item.quantity, 0)} sản phẩm, trạng thái {notice.status}.</div>}
    <section className="panel inventory-history" aria-label="Đơn chờ xuất kho"><div className="panel-heading"><div><span className="eyebrow">ĐƠN CẦN XỬ LÝ</span><h2>Hàng đang giữ</h2></div></div>
      {loading ? <Loading text="Đang tải đơn chờ xuất…" /> : queue?.items.length ? <div className="inventory-receipt-list">{queue.items.map(item => <article key={item.orderId} className="dispatch-queue-row"><div><strong>{item.orderNumber}</strong><span>{item.status} · {item.remainingQuantity} sản phẩm còn giữ</span></div><button type="button" className="button secondary" disabled={busy || !!pending} onClick={() => selectOrder(item.orderId)}>Xem hàng giữ</button></article>)}</div> : !error && <p className="muted">Không có đơn đang giữ hàng.</p>}
      <Pager page={queue} busy={loading || busy} onChange={setPage} />
    </section>
    {preview && !pending && <section className="panel inventory-receipt-panel" aria-label="Xuất kho cho đơn"><div className="panel-heading"><div><span className="eyebrow">VẬN ĐƠN MỚI</span><h2>{preview.orderNumber}</h2></div></div>
      <div className="dispatch-holds">{preview.holds.map(item => <div key={item.reservationId}><strong>{item.sku}</strong><span>{item.warehouseCode}: {item.remainingQuantity} còn giữ / {item.quantity} đặt</span></div>)}</div>
      <form className="form-grid" onSubmit={submit}>
        <div className="form-field"><label htmlFor="dispatch-warehouse">Kho xuất</label><select id="dispatch-warehouse" required value={form.warehouseId} disabled={busy} onChange={event => setForm(current => ({ ...current, warehouseId: event.target.value }))}><option value="">Chọn kho</option>{locations.map(item => <option key={item.id} value={item.id}>{item.code}</option>)}</select></div>
        <div className="form-field"><label htmlFor="dispatch-carrier">Đơn vị vận chuyển</label><input id="dispatch-carrier" required maxLength="100" value={form.carrier} disabled={busy} onChange={event => setForm(current => ({ ...current, carrier: event.target.value }))} placeholder="VD: Giao hàng nội bộ" /></div>
        <div className="form-field"><label htmlFor="dispatch-tracking">Mã vận đơn</label><input id="dispatch-tracking" required maxLength="150" value={form.trackingNumber} disabled={busy} onChange={event => setForm(current => ({ ...current, trackingNumber: event.target.value }))} placeholder="Mã duy nhất từ đơn vị vận chuyển" /></div>
        <div className="form-field wide"><label htmlFor="dispatch-note">Ghi chú</label><textarea id="dispatch-note" rows="3" maxLength="1000" value={form.note} disabled={busy} onChange={event => setForm(current => ({ ...current, note: event.target.value }))} /></div>
        <div className="editor-actions wide"><button className="button" disabled={busy || !locations.length}>{busy ? 'Đang xuất…' : 'Xác nhận xuất kho'}</button></div>
      </form>
    </section>}
  </>;
}
