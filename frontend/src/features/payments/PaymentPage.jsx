import { useEffect, useMemo, useRef, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { ErrorNotice, Loading, OrderCode } from '../../components/ui';
import { useCommerce } from '../../lib/session';
import { paymentMethods } from '../orders/OrderParts';
import { BankGuidance, createBlockMessage, PaymentAmounts, PaymentHistory, PaymentNotice } from './PaymentParts';
import { SePayGuidance, useSePayPolling } from './SePayParts';
import { clearPaymentRequest, creationRejections, paymentApi, readPaymentRequest, savePaymentRequest } from './paymentApi';
import CheckoutSteps from '../../components/CheckoutSteps';

export default function PaymentPage() {
  const { id } = useParams(); const { api, session } = useCommerce(); const userId = session.user.id;
  const client = useMemo(() => paymentApi(api), [api]);
  const [pending, setPending] = useState(() => readPaymentRequest('create', userId, id));
  const [method, setMethod] = useState('COD'); const [summary, setSummary] = useState(null);
  const [loading, setLoading] = useState(true); const [busy, setBusy] = useState(false); const [error, setError] = useState(null); const [notice, setNotice] = useState('');
  const lock = useRef(false); const errorRef = useRef(null);
  useEffect(() => {
    const controller = new AbortController(); setLoading(true); setError(null);
    client.summary(id, controller.signal).then(value => { if (!controller.signal.aborted) setSummary(value); }).catch(failure => { if (!controller.signal.aborted && failure.name !== 'AbortError') setError(failure); }).finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [client, id]);
  const pollingWarning = useSePayPolling({ active: !!summary?.items.some(p => p.provider === 'SEPAY_SANDBOX' && p.status === 'PENDING' && p.sepay?.qrUrl), client, id, lock, onSummary: setSummary });
  useEffect(() => { if (error) errorRef.current?.focus(); }, [error]);
  async function refresh() {
    if (lock.current) return; lock.current = true; setBusy(true); setError(null);
    try { setSummary(await client.summary(id)); } catch (failure) { setError(failure); } finally { lock.current = false; setBusy(false); }
  }
  async function create() {
    if (lock.current || !pending && !summary?.canCreate) return;
    lock.current = true; setBusy(true); setError(null); setNotice(''); let attempt = pending;
    try {
      if (!attempt) { attempt = { key: crypto.randomUUID(), body: { method } }; savePaymentRequest('create', userId, id, attempt); setPending(attempt); }
      setSummary(await client.create(id, attempt)); clearPaymentRequest('create', userId, id); setPending(null); setNotice('Đã tạo yêu cầu thanh toán. Chưa xác nhận đã thu tiền.');
    } catch (failure) {
      setError(failure);
      if (failure.status === 409 && creationRejections.has(failure.code)) { clearPaymentRequest('create', userId, id); setPending(null); try { setSummary(await client.summary(id)); } catch { /* Preserve the business rejection and let the customer refresh explicitly. */ } }
    } finally { lock.current = false; setBusy(false); }
  }
  return <>
    <div className="breadcrumb"><Link to={`/orders/${id}`}>Đơn hàng</Link><span>/</span><span>Thanh toán</span></div>
    <div className="page-heading payment-page-heading"><div><span className="eyebrow">THEO DÕI KHOẢN THU</span><h1>{summary ? <>Thanh toán đơn <OrderCode value={summary.orderNumber} /></> : 'Thanh toán đơn hàng'}</h1><p>Khoản phải thu, chứng từ và hoàn tiền được theo dõi riêng.</p></div><button className="button secondary" disabled={busy || loading} onClick={refresh}>Làm mới thanh toán</button></div>
    <CheckoutSteps current={2} />
    <PaymentNotice bank={summary?.bankTransfer} sepay={summary?.items.some(p => p.provider === 'SEPAY_SANDBOX')} />{pollingWarning && <p className="notice warning" role="status">{pollingWarning}</p>}
    <div ref={errorRef} tabIndex="-1"><ErrorNotice error={error} />{error?.status === 401 && <Link className="inline-link" to="/login" state={{ returnTo: `/orders/${id}/payments` }}>Đăng nhập lại để kiểm tra yêu cầu</Link>}</div>
    {notice && <div className="notice success" role="status">{notice}</div>}
    {pending && <section className="notice warning" aria-label="Yêu cầu thanh toán chưa rõ kết quả"><h2>Giữ nguyên yêu cầu đang chờ kết quả</h2><p>Phương thức đã gửi: {paymentMethods[pending.body.method]}. Hãy gửi lại đúng yêu cầu này để nhận kết quả, không tạo yêu cầu khác sau khi mất phản hồi.</p><button className="button" disabled={busy} onClick={create}>{busy ? 'Đang kiểm tra…' : 'Thử lại yêu cầu thanh toán'}</button></section>}
    {loading ? <Loading text="Đang tải thông tin thanh toán…" /> : summary && <div className="payments-layout"><div className="payments-main"><SePayGuidance summary={summary} /><BankGuidance summary={summary} /><PaymentHistory items={summary.items} /></div><aside className="payments-aside"><PaymentAmounts summary={summary} />{!pending && <section className="panel payment-create"><h2>Yêu cầu thanh toán</h2>{summary.canCreate ? <><p>Chỉ tạo khi lần trước đã kết thúc. Số tiền được cửa hàng tính lại từ đơn.</p><label htmlFor="payment-method">Phương thức yêu cầu mới</label><select id="payment-method" value={method} onChange={event => setMethod(event.target.value)} disabled={busy}>{summary.sepay?.enabled && <option value="SEPAY">SePay Sandbox · QR thử nghiệm</option>}<option value="COD">Thanh toán khi nhận hàng</option><option value="BANK_TRANSFER" disabled={!summary.bankTransfer?.configured}>Chuyển khoản ngân hàng</option></select><button className="button full" disabled={busy || method === 'BANK_TRANSFER' && !summary.bankTransfer?.configured} onClick={create}>{busy ? 'Đang tạo…' : 'Tạo yêu cầu thanh toán'}</button></> : <p>{createBlockMessage(summary.createBlockReason)}</p>}</section>}</aside></div>}
    <Link className="inline-link" to={`/orders/${id}`}>← Chi tiết đơn hàng</Link>
  </>;
}
