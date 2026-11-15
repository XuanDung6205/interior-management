import { useEffect, useRef, useState } from 'react';
import { ErrorNotice } from '../../components/ui';
import { orderStates, Status } from './OrderParts';

const actionLabels = { CONFIRMED: 'Xác nhận đơn', PROCESSING: 'Bắt đầu xử lý', COMPLETED: 'Hoàn tất đơn', CANCELLED: 'Hủy đơn' };
export default function OrderLifecycleActions({ order, client, busy, perform, onUpdated, onRefresh }) {
  const [selected, setSelected] = useState(null);
  const [note, setNote] = useState('');
  const [confirmCancel, setConfirmCancel] = useState(false);
  const [error, setError] = useState(null);
  const [notice, setNotice] = useState('');
  const errorRef = useRef(null);
  const stale = error?.code === 'ORDER_STATE_CHANGED' || (selected && selected.fromStatus !== order.status);
  const cancellation = selected?.toStatus === 'CANCELLED';
  useEffect(() => { if (error) errorRef.current?.focus(); }, [error]);
  function select(toStatus) { setSelected({ fromStatus: order.status, toStatus }); setNote(''); setConfirmCancel(false); setError(null); setNotice(''); }
  async function submit(event) {
    event.preventDefault();
    if (!selected || busy || stale || note.length > 1000 || !order.allowedTransitions.includes(selected.toStatus) || (cancellation && !note.trim())) return;
    if (cancellation && !confirmCancel) { setConfirmCancel(true); return; }
    await perform(async () => {
      setError(null); setNotice('');
      try {
        const updated = await client.transition(order.id, selected.fromStatus, selected.toStatus, note.trim() || null);
        onUpdated(updated); setSelected(null); setNote(''); setConfirmCancel(false); setNotice('Đã cập nhật trạng thái đơn hàng.');
      } catch (failure) { setError(failure); }
    });
  }
  async function refresh() {
    const updated = await onRefresh();
    if (updated) { setSelected(null); setConfirmCancel(false); setError(null); }
  }
  return <section className="panel lifecycle-panel" aria-label="Xử lý đơn hàng"><div className="panel-heading"><h2>Xử lý đơn hàng</h2></div><div className="order-section-body"><p className="muted small">Trạng thái hiện tại <Status value={order.status} /></p>{notice && <div className="notice success" role="status">{notice}</div>}<div className="lifecycle-buttons">{order.allowedTransitions.map(target => <button className={`button ${target === 'CANCELLED' ? 'secondary destructive' : 'secondary'}`} key={target} disabled={busy} onClick={() => select(target)}>{actionLabels[target] || orderStates[target] || target}</button>)}</div>{!order.allowedTransitions.length && <p className="muted">Không còn thao tác chuyển trạng thái cho đơn này.</p>}{selected && <form onSubmit={submit} className="transition-form"><h3>{actionLabels[selected.toStatus] || orderStates[selected.toStatus]}</h3><p className="public-note-label">Thông điệp này sẽ hiển thị cho khách hàng trong lịch sử đơn.</p><div ref={errorRef} tabIndex="-1"><ErrorNotice error={error} /></div><label htmlFor="public-transition-note">{cancellation ? 'Lý do hủy đơn (khách hàng nhìn thấy)' : 'Thông điệp cho khách hàng'}</label><textarea id="public-transition-note" value={note} maxLength={1000} rows={3} required={cancellation} disabled={busy || confirmCancel} onChange={event => setNote(event.target.value)} aria-invalid={!!error?.fieldErrors?.note} aria-describedby={`public-transition-hint${error?.fieldErrors?.note ? ' transition-note-error' : ''}`} /><p id="public-transition-hint" className="muted small">{note.length} / 1.000 ký tự. {cancellation ? 'Cần nêu lý do hủy đơn.' : 'Không bắt buộc. Ghi chú riêng dùng ô nội bộ bên dưới.'}</p>{error?.fieldErrors?.note && <span id="transition-note-error" className="field-error">{error.fieldErrors.note}</span>}{confirmCancel && <div className="notice warning" role="alert"><strong>Xác nhận hủy đơn {order.orderNumber}?</strong><p>{note.trim()}</p><p>Hệ thống kiểm tra lại điều kiện hủy và xử lý giữ hàng, khoản thu hoặc yêu cầu hoàn theo dữ liệu hiện tại.</p></div>}{stale && <button className="text-button" type="button" disabled={busy} onClick={refresh}>Tải lại trạng thái để kiểm tra</button>}<div className="cancel-actions"><button type="button" className="button secondary" disabled={busy} onClick={() => { if (confirmCancel) setConfirmCancel(false); else { setSelected(null); setError(null); } }}>{confirmCancel ? 'Sửa lý do' : 'Đóng thao tác'}</button><button className={`button ${cancellation && confirmCancel ? 'danger' : ''}`} disabled={busy || stale || (cancellation && !note.trim())}>{busy ? 'Đang xử lý…' : cancellation ? confirmCancel ? 'Xác nhận hủy đơn' : 'Kiểm tra yêu cầu hủy' : 'Xác nhận chuyển trạng thái'}</button></div></form>}{order.blockedTransitions.length > 0 && <div className="blocked-transitions"><h3>Chưa đủ điều kiện</h3>{order.blockedTransitions.map(blocked => <div key={blocked.toStatus}><strong>{orderStates[blocked.toStatus] || blocked.toStatus}</strong><ul>{blocked.reasons.map((reason, index) => <li key={index}>{reason}</li>)}</ul></div>)}<p className="muted small">Thanh toán và giao/lắp đặt cần bằng chứng từ các bộ phận phụ trách. Không thay đổi những trạng thái đó tại đây.</p></div>}</div></section>;
}
