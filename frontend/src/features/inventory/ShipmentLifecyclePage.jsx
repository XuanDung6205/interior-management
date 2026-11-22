import { useEffect, useMemo, useState } from 'react';
import { useCommerce } from '../../lib/session';
import { ErrorNotice, Loading, Pager } from '../../components/ui';
import { formatDate, toLocalInput } from '../../lib/format';
import { Status } from '../orders/OrderParts';
import { inventoryApi } from './inventoryApi';

const transitions = { IN_TRANSIT: [{ value: 'DELIVERED', label: 'Đã giao thành công' },
  { value: 'FAILED', label: 'Giao thất bại' }], FAILED: [
  { value: 'IN_TRANSIT', label: 'Giao lại' }, { value: 'RETURNED', label: 'Hàng đã hoàn về kho' }] };
const definitiveErrors = new Set(['INVALID_SHIPMENT_STATUS', 'VALIDATION_ERROR', 'IDEMPOTENCY_CONFLICT',
  'SHIPMENT_STATUS_CONFLICT', 'ORDER_STATUS_CONFLICT', 'SHIPMENT_CHANGED', 'ORDER_CHANGED',
  'ORDER_UNAVAILABLE', 'SHIPMENT_EMPTY', 'STOCK_CHANGED', 'STOCK_LIMIT', 'NOT_FOUND']);
const scheduleErrors = new Set(['INVALID_SHIPMENT_SCHEDULE', 'VALIDATION_ERROR', 'IDEMPOTENCY_CONFLICT',
  'ORDER_STATUS_CONFLICT', 'ORDER_OWNER_CHANGED', 'SHIPMENT_STATUS_CONFLICT',
  'INSTALLATION_NOT_READY', 'SHIPMENT_CHANGED', 'NOT_FOUND']);

export default function ShipmentLifecyclePage() {
  const { api, session } = useCommerce();
  const client = useMemo(() => inventoryApi(api), [api]);
  const storageKey = `tv4.shipment.pending.${session.user.id}`;
  const scheduleStorageKey = `tv4.shipment.schedule.pending.${session.user.id}`;
  const [pending, setPending] = useState(() => {
    try { return JSON.parse(sessionStorage.getItem(storageKey)); } catch { return null; }
  });
  const [history, setHistory] = useState(null);
  const [pendingSchedule, setPendingSchedule] = useState(() => {
    try { return JSON.parse(sessionStorage.getItem(scheduleStorageKey)); } catch { return null; }
  });
  const [scheduleShipment, setScheduleShipment] = useState(null);
  const [scheduleKind, setScheduleKind] = useState('DELIVERY');
  const [scheduleFrom, setScheduleFrom] = useState('');
  const [scheduleTo, setScheduleTo] = useState('');
  const [installationAt, setInstallationAt] = useState('');
  const [selected, setSelected] = useState(null);
  const [targetStatus, setTargetStatus] = useState('');
  const [reason, setReason] = useState('');
  const [received, setReceived] = useState(false);
  const [filter, setFilter] = useState('');
  const [page, setPage] = useState(0);
  const [revision, setRevision] = useState(0);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const [notice, setNotice] = useState(null);

  useEffect(() => {
    let active = true;
    setLoading(true);
    client.shipments(page, filter).then(value => { if (active) setHistory(value); })
      .catch(value => { if (active) setError(value); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [client, filter, page, revision]);

  function select(shipment) {
    setSelected(shipment);
    setScheduleShipment(null);
    setTargetStatus('');
    setReason('');
    setReceived(false);
    setError(null);
  }
  function selectSchedule(shipment) {
    setSelected(null);
    setScheduleShipment(shipment);
    setScheduleKind(['IN_TRANSIT', 'FAILED'].includes(shipment.status) ? 'DELIVERY' : 'INSTALLATION');
    setScheduleFrom(toLocalInput(shipment.scheduledFrom));
    setScheduleTo(toLocalInput(shipment.scheduledTo));
    setInstallationAt(toLocalInput(shipment.installationScheduledAt));
    setError(null);
  }
  async function sendSchedule(attempt) {
    setBusy(true); setError(null); setNotice(null);
    try {
      const result = await client.shipmentSchedule(attempt.shipmentId, attempt.body, attempt.key);
      sessionStorage.removeItem(scheduleStorageKey);
      setPendingSchedule(null);
      setScheduleShipment(null);
      setNotice({ ...result, scheduled: true });
      setRevision(value => value + 1);
    } catch (value) {
      setError(value);
      if (scheduleErrors.has(value.code)) {
        sessionStorage.removeItem(scheduleStorageKey);
        setPendingSchedule(null);
        setRevision(current => current + 1);
      }
    } finally { setBusy(false); }
  }
  function submitSchedule(event) {
    event.preventDefault();
    if (!scheduleShipment || busy || pending || pendingSchedule) return;
    const localTimes = scheduleKind === 'DELIVERY' ? [scheduleFrom, scheduleTo] : [installationAt];
    if (localTimes.some(value => !value || Number.isNaN(new Date(value).getTime()))) return;
    const body = scheduleKind === 'DELIVERY'
      ? { kind: 'DELIVERY', scheduledFrom: new Date(scheduleFrom).toISOString(), scheduledTo: new Date(scheduleTo).toISOString() }
      : { kind: 'INSTALLATION', installationAt: new Date(installationAt).toISOString() };
    const attempt = { shipmentId: scheduleShipment.id, body, key: crypto.randomUUID() };
    sessionStorage.setItem(scheduleStorageKey, JSON.stringify(attempt));
    setPendingSchedule(attempt);
    sendSchedule(attempt);
  }
  async function send(attempt) {
    setBusy(true); setError(null); setNotice(null);
    try {
      const result = await client.shipmentStatus(attempt.shipmentId, attempt.body, attempt.key);
      sessionStorage.removeItem(storageKey);
      setPending(null);
      setSelected(null);
      setNotice(result);
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
    if (!selected || !targetStatus || !reason.trim() || busy || pending || targetStatus === 'RETURNED' && !received) return;
    const attempt = { shipmentId: selected.id, body: { targetStatus, reason: reason.trim() }, key: crypto.randomUUID() };
    sessionStorage.setItem(storageKey, JSON.stringify(attempt));
    setPending(attempt);
    send(attempt);
  }

  return <>
    <div className="breadcrumb"><span>Quản trị</span><span>/</span><span>Giao hàng</span><span>/</span><span>Vận đơn</span></div>
    <div className="page-heading"><div><span className="eyebrow">VẬN HÀNH GIAO HÀNG</span><h1>Theo dõi vận đơn<span className="heading-dot">.</span></h1><p>Đặt lịch giao/lắp và ghi nhận kết quả giao hàng.</p></div></div>
    <ErrorNotice error={error} onRetry={() => pending ? send(pending) : pendingSchedule ? sendSchedule(pendingSchedule) : setRevision(value => value + 1)} />
    {pending && <div className="notice warning" role="status"><strong>Yêu cầu cập nhật vận đơn đang chờ kết quả.</strong><p>Vận đơn #{pending.shipmentId} → {pending.body.targetStatus}. Nếu mất kết nối, dùng lại đúng nội dung và mã yêu cầu.</p><button className="button secondary" disabled={busy} onClick={() => send(pending)}>{busy ? 'Đang kiểm tra…' : 'Kiểm tra và gửi lại'}</button></div>}
    {pendingSchedule && <div className="notice warning" role="status"><strong>Yêu cầu đặt lịch đang chờ kết quả.</strong><p>Vận đơn #{pendingSchedule.shipmentId}. Gửi lại đúng lịch và mã yêu cầu để kiểm tra.</p><button className="button secondary" disabled={busy} onClick={() => sendSchedule(pendingSchedule)}>{busy ? 'Đang kiểm tra…' : 'Kiểm tra lịch và gửi lại'}</button></div>}
    {notice && <div className="notice success" role="status">{notice.replayed ? 'Cập nhật đã được ghi nhận trước đó.' : notice.scheduled ? 'Đã lưu lịch vận đơn.' : 'Đã cập nhật vận đơn.'} {notice.trackingNumber}: <Status value={notice.status} />.</div>}
    <section className="panel inventory-history" aria-label="Danh sách vận đơn"><div className="panel-heading"><div><span className="eyebrow">DANH SÁCH</span><h2>Vận đơn gần đây</h2></div><div className="shipment-filter"><label htmlFor="shipment-filter">Trạng thái</label><select id="shipment-filter" value={filter} onChange={event => { setFilter(event.target.value); setPage(0); setSelected(null); }}><option value="">Tất cả</option><option value="IN_TRANSIT">Đang vận chuyển</option><option value="FAILED">Giao thất bại</option><option value="DELIVERED">Đã giao</option><option value="RETURNED">Đã hoàn về</option></select></div></div>
      {loading ? <Loading text="Đang tải vận đơn…" /> : history?.items.length ? <div className="inventory-receipt-list">{history.items.map(shipment => <article key={shipment.id} className="dispatch-queue-row"><div><strong>{shipment.orderNumber} · {shipment.trackingNumber}</strong><span>{shipment.carrier} · {shipment.warehouseCode} · Xuất {formatDate(shipment.shippedAt)}</span>{shipment.scheduledFrom && <span>Giao dự kiến: {formatDate(shipment.scheduledFrom)} – {formatDate(shipment.scheduledTo)}</span>}{shipment.installationScheduledAt && <span>Lắp dự kiến: {formatDate(shipment.installationScheduledAt)}</span>}</div><Status value={shipment.status} />{(transitions[shipment.status] || shipment.installationRequired && ['PENDING', 'SCHEDULED'].includes(shipment.installationStatus) && ['IN_TRANSIT', 'DELIVERED'].includes(shipment.status)) && <button type="button" className="button secondary" disabled={busy || !!pending || !!pendingSchedule} onClick={() => selectSchedule(shipment)}>Đặt lịch</button>}{transitions[shipment.status] && <button type="button" className="button secondary" disabled={busy || !!pending || !!pendingSchedule} onClick={() => select(shipment)}>Cập nhật giao hàng</button>}</article>)}</div> : !error && <p className="muted">Chưa có vận đơn phù hợp.</p>}
      <Pager page={history} busy={loading || busy} onChange={setPage} />
    </section>
    {scheduleShipment && !pendingSchedule && <section className="panel inventory-receipt-panel" aria-label="Đặt lịch giao và lắp đặt"><div className="panel-heading"><div><span className="eyebrow">ĐẶT LỊCH</span><h2>{scheduleShipment.trackingNumber}</h2></div></div><form className="form-grid" onSubmit={submitSchedule}>
      <div className="form-field"><label htmlFor="schedule-kind">Loại lịch</label><select id="schedule-kind" value={scheduleKind} disabled={busy} onChange={event => setScheduleKind(event.target.value)}>{['IN_TRANSIT', 'FAILED'].includes(scheduleShipment.status) && <option value="DELIVERY">Giao hàng</option>}{scheduleShipment.installationRequired && ['PENDING', 'SCHEDULED'].includes(scheduleShipment.installationStatus) && ['IN_TRANSIT', 'DELIVERED'].includes(scheduleShipment.status) && <option value="INSTALLATION">Lắp đặt</option>}</select></div>
      {scheduleKind === 'DELIVERY' ? <><div className="form-field"><label htmlFor="schedule-from">Bắt đầu giao</label><input id="schedule-from" type="datetime-local" required value={scheduleFrom} disabled={busy} onChange={event => setScheduleFrom(event.target.value)} /></div><div className="form-field"><label htmlFor="schedule-to">Kết thúc giao</label><input id="schedule-to" type="datetime-local" required value={scheduleTo} disabled={busy} onChange={event => setScheduleTo(event.target.value)} /></div></> : <div className="form-field"><label htmlFor="installation-at">Thời gian lắp đặt</label><input id="installation-at" type="datetime-local" required value={installationAt} disabled={busy} onChange={event => setInstallationAt(event.target.value)} /></div>}
      <p className="muted wide">Thời gian hiển thị theo múi giờ trên thiết bị của bạn. Lịch đã lưu sẽ hiện trong chi tiết đơn của khách.</p><div className="editor-actions wide"><button className="button" disabled={busy || !!pending || (scheduleKind === 'DELIVERY' ? !scheduleFrom || !scheduleTo : !installationAt)}>{busy ? 'Đang lưu…' : 'Lưu lịch'}</button></div>
    </form></section>}
    {selected && !pending && <section className="panel inventory-receipt-panel" aria-label="Cập nhật trạng thái vận đơn"><div className="panel-heading"><div><span className="eyebrow">CẬP NHẬT</span><h2>{selected.trackingNumber}</h2></div></div><form className="form-grid" onSubmit={submit}>
      <div className="form-field"><label htmlFor="shipment-target">Kết quả giao hàng</label><select id="shipment-target" required value={targetStatus} disabled={busy} onChange={event => { setTargetStatus(event.target.value); setReceived(false); }}><option value="">Chọn kết quả</option>{transitions[selected.status]?.map(option => <option key={option.value} value={option.value}>{option.label}</option>)}</select></div>
      <div className="form-field wide"><label htmlFor="shipment-reason">Lý do / xác nhận</label><textarea id="shipment-reason" required rows="3" maxLength="1000" value={reason} disabled={busy} onChange={event => setReason(event.target.value)} placeholder="Thông tin này sẽ hiển thị cho khách trong lịch sử giao hàng" /></div>
      {targetStatus === 'RETURNED' && <label className="wide shipment-confirm"><input type="checkbox" checked={received} disabled={busy} onChange={event => setReceived(event.target.checked)} /> Tôi xác nhận hàng đã thực sự về kho {selected.warehouseCode}; hệ thống sẽ cộng lại tồn vật lý.</label>}
      <div className="editor-actions wide"><button className="button" disabled={busy || !targetStatus || !reason.trim() || targetStatus === 'RETURNED' && !received}>{busy ? 'Đang lưu…' : 'Xác nhận cập nhật'}</button></div>
    </form></section>}
  </>;
}
