import { useEffect, useMemo, useRef, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { ErrorNotice, Loading } from '../../components/ui';
import { useCommerce } from '../../lib/session';
import { formatMoney } from '../../lib/format';
import { Status } from '../orders/OrderParts';
import { allPaymentMethods, PaymentAmounts, PaymentHistory, PaymentNotice } from './PaymentParts';
import { SePayReconcile } from './SePayParts';
import { clearPaymentRequest, paymentApi, proofRejections, readPaymentRequest, savePaymentRequest } from './paymentApi';

export default function PaymentAdminDetailPage() {
  const { id } = useParams(); const { api, session } = useCommerce(); const userId = session.user.id; const client = useMemo(() => paymentApi(api), [api]);
  const [pending, setPending] = useState(() => readPaymentRequest('confirm', userId, id));
  const [reference, setReference] = useState(() => (readPaymentRequest('confirm', userId, id) || readPaymentRequest('draft', userId, id))?.reference || '');
  const [note, setNote] = useState(() => (readPaymentRequest('confirm', userId, id) || readPaymentRequest('draft', userId, id))?.note || '');
  const [ack, setAck] = useState(false); const [review, setReview] = useState(false); const [rejected, setRejected] = useState(() => !!readPaymentRequest('draft', userId, id));
  const [summary, setSummary] = useState(null); const [loading, setLoading] = useState(true); const [busy, setBusy] = useState(false); const [error, setError] = useState(null); const [notice, setNotice] = useState('');
  const lock = useRef(false); const errorRef = useRef(null);
  const selected = summary?.items.find(item => String(item.id) === String(summary.selectedPaymentId));
  const manual = selected && ['COD', 'BANK_TRANSFER'].includes(selected.method) && ['COD', 'MANUAL_BANK'].includes(selected.provider);
  const canConfirm = manual && selected.canConfirm;
  useEffect(() => {
    const controller = new AbortController(); setLoading(true); setError(null);
    client.detail(id, controller.signal).then(value => { if (!controller.signal.aborted) setSummary(value); }).catch(failure => { if (!controller.signal.aborted && failure.name !== 'AbortError') setError(failure); }).finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [client, id]);
  useEffect(() => { if (error) errorRef.current?.focus(); }, [error]);
  useEffect(() => {
    if (rejected && !pending) {
      try { savePaymentRequest('draft', userId, id, { reference, note }); }
      catch (failure) { setError(failure); }
    }
  }, [rejected, pending, userId, id, reference, note]);
  async function refresh() {
    if (lock.current) return; lock.current = true; setBusy(true); setError(null);
    try { setSummary(await client.detail(id)); } catch (failure) { setError(failure); } finally { lock.current = false; setBusy(false); }
  }
  function prepare(event) { event.preventDefault(); if (canConfirm && ack && /^[A-Za-z0-9._:/-]{6,150}$/.test(reference.trim()) && note.length <= 1000) { setReview(true); setError(null); } }
  async function confirm() {
    if (lock.current || !manual || !pending && (!canConfirm || !review || !ack)) return;
    lock.current = true; setBusy(true); setError(null); setNotice(''); let proof = pending;
    try {
      if (!proof) { proof = { reference: reference.trim(), note: note.trim() || null, acknowledged: true }; savePaymentRequest('confirm', userId, id, proof); setPending(proof); }
      setSummary(await client.confirm(id, proof)); clearPaymentRequest('confirm', userId, id); clearPaymentRequest('draft', userId, id); setPending(null); setReview(false); setAck(false); setRejected(false); setNotice('Đã ghi nhận chứng từ và khoản thu.');
    } catch (failure) {
      setError(failure);
      if (failure.status === 409 && proofRejections.has(failure.code) || failure.status === 400 && Object.keys(failure.fieldErrors || {}).length > 0) {
        try { savePaymentRequest('draft', userId, id, { reference, note }); clearPaymentRequest('confirm', userId, id); setPending(null); setReview(false); setAck(false); setRejected(true); }
        catch (storageFailure) { setError(storageFailure); }
      }
    } finally { lock.current = false; setBusy(false); }
  }
  const frozen = busy || review || !!pending || !canConfirm;
  return <>
    <div className="breadcrumb"><Link to="/admin/payments">Quản lý thanh toán</Link><span>/</span><span>Khoản #{id}</span></div>
    <div className="page-heading payment-page-heading"><div><span className="eyebrow">CHỨNG TỪ & KHOẢN THU</span><h1>Xác minh khoản #{id}</h1>{summary && <p>Đơn {summary.orderNumber} · Các khoản tiền được giữ nguyên theo máy chủ.</p>}</div><button className="button secondary" disabled={busy || loading} onClick={refresh}>Làm mới chứng từ</button></div>
    <PaymentNotice bank={summary?.bankTransfer} sepay={selected?.provider === 'SEPAY_SANDBOX'} />
    <div ref={errorRef} tabIndex="-1"><ErrorNotice error={error} />{error?.status === 401 && <Link className="inline-link" to="/login" state={{ returnTo: `/admin/payments/${id}` }}>Đăng nhập lại để kiểm tra chứng từ</Link>}</div>{notice && <div className="notice success" role="status">{notice}</div>}
    {loading ? <Loading text="Đang tải khoản cần xác minh…" /> : summary && selected && <div className="payments-layout"><div className="payments-main"><section className="panel payment-proof"><div className="panel-heading"><h2>Khoản cần đối chiếu</h2><Status value={selected.status} /></div><div className="order-section-body"><p>{selected.provider === 'SEPAY_SANDBOX' ? 'SePay Sandbox · QR thử nghiệm' : allPaymentMethods[selected.method] || selected.method}</p><strong className="immutable-payment-amount" data-testid="confirmation-amount">{formatMoney(selected.amount)}</strong><p className="muted small">{selected.provider === 'SEPAY_SANDBOX' ? 'Số tiền cố định của khoản này. Hệ thống xác minh giao dịch SePay trước khi ghi nhận.' : 'Số tiền cố định của khoản này. Chỉ xác nhận sau khi đã đối chiếu chứng từ tương ứng.'}</p>{selected.isLateCollection && <div className="notice warning"><h3>Khoản thu đến sau khi đơn đã hủy</h3><p>Yêu cầu hoàn sẽ ở trạng thái chờ xử lý, chưa phải đã hoàn tiền. Đối chiếu thực thu trước khi ghi nhận.</p></div>}{!canConfirm && selected.confirmationMessage && <div className="notice">{selected.confirmationMessage}</div>}
      {(canConfirm || pending && manual || rejected) && <><form aria-label="Chứng từ xác minh" onSubmit={prepare}><div className="form-field"><label htmlFor="receipt-reference">Mã chứng từ / tham chiếu</label><input id="receipt-reference" value={reference} required minLength={6} maxLength={150} disabled={frozen} onChange={event => { setReference(event.target.value); setAck(false); }} aria-invalid={!!error?.fieldErrors?.reference} aria-describedby="receipt-reference-hint" /><p id="receipt-reference-hint" className="muted small">6–150 ký tự ASCII: chữ, số hoặc . _ : / -</p>{error?.fieldErrors?.reference && <span className="field-error">{error.fieldErrors.reference}</span>}</div><div className="form-field"><label htmlFor="receipt-note">Ghi chú xác minh nội bộ</label><textarea id="receipt-note" rows={3} maxLength={1000} value={note} disabled={frozen} onChange={event => { setNote(event.target.value); setAck(false); }} /><p className="muted small">Chỉ nhân viên nhìn thấy. Không dùng ô này thay chứng từ.</p>{error?.fieldErrors?.note && <span className="field-error">{error.fieldErrors.note}</span>}</div>{!pending && !review && <><label className="checkbox-label receipt-attestation"><input type="checkbox" checked={ack} disabled={busy || !canConfirm} onChange={event => setAck(event.target.checked)} />Tôi đã đối chiếu chứng từ và xác nhận thực thu đủ số tiền này.</label><button className="button full" disabled={busy || !canConfirm || !ack || !/^[A-Za-z0-9._:/-]{6,150}$/.test(reference.trim())}>Kiểm tra xác nhận</button></>}</form>
        {review && !pending && <div className="receipt-review"><h3>Kiểm tra lần cuối trước khi ghi nhận</h3><dl className="payment-receipt-meta"><div><dt>Số tiền thực thu đã đối chiếu</dt><dd>{formatMoney(selected.amount)}</dd></div><div><dt>Tham chiếu</dt><dd>{reference.trim()}</dd></div>{note.trim() && <div><dt>Ghi chú nội bộ</dt><dd>{note.trim()}</dd></div>}</dl><div className="actions"><button className="button secondary" disabled={busy} onClick={() => { setReview(false); setAck(false); }}>Sửa chứng từ</button><button className="button" disabled={busy} onClick={confirm}>{busy ? 'Đang ghi nhận…' : 'Xác nhận đã thu tiền'}</button></div></div>}
        {pending && manual && <div className="notice warning"><h3>Giữ nguyên chứng từ đã gửi</h3><p>Kết quả có thể đã được ghi nhận. Gửi lại đúng chứng từ để kiểm tra; không đổi tham chiếu khi chưa rõ kết quả.</p><button className="button full" disabled={busy} onClick={confirm}>{busy ? 'Đang kiểm tra…' : 'Thử lại xác nhận đã gửi'}</button></div>}
      </>}
      {selected.isExpired && <p className="field-error">Yêu cầu đã hết hạn theo giờ máy chủ.</p>}</div></section>{selected.provider === 'SEPAY_SANDBOX' && <SePayReconcile key={selected.id} selected={selected} client={client} userId={userId} onUpdated={setSummary}/>}<PaymentHistory items={summary.items} staff selectedId={summary.selectedPaymentId} /></div><aside className="payments-aside"><PaymentAmounts summary={summary} /><Link className="button secondary full" to={`/admin/orders/${summary.orderId}/refunds`}>Đối chiếu hoàn tiền của đơn</Link><Link className="button secondary full" to={`/admin/orders/${summary.orderId}`}>Xem đơn hàng liên quan</Link></aside></div>}
    {!loading && summary && !selected && <div className="notice error">Không tìm thấy khoản được chọn trong đơn này.</div>}
  </>;
}
