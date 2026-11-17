import { useEffect, useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { ErrorNotice, Loading } from '../../components/ui';
import { useCommerce } from '../../lib/session';
import { formatMoney } from '../../lib/format';
import { allPaymentMethods } from '../payments/PaymentParts';
import { Status } from '../orders/OrderParts';
import { refundApi, friendlyRefundError } from './refundApi';
import { RefundAmounts, RefundHistory, RefundNotice } from './RefundParts';
import RefundCreate from './RefundCreate';
import RefundResolve from './RefundResolve';

export default function RefundOrderPage({ selected = false }) {
  const { id } = useParams(); const { api, session } = useCommerce(); const client = useMemo(() => refundApi(api), [api]);
  const [summary, setSummary] = useState(null); const [loading, setLoading] = useState(true); const [error, setError] = useState(null); const [revision, setRevision] = useState(0);
  useEffect(() => {
    const controller = new AbortController(); setLoading(true); setError(null);
    (selected ? client.detail(id, controller.signal) : client.summary(id, controller.signal)).then(value => { if (!controller.signal.aborted) setSummary(value); }).catch(failure => { if (!controller.signal.aborted && failure.name !== 'AbortError') setError(friendlyRefundError(failure)); }).finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [client, id, selected, revision]);
  const item = summary?.items.find(value => String(value.id) === String(summary.selectedRefundId));
  const returnTo = selected ? `/admin/refunds/${id}` : `/admin/orders/${id}/refunds`;
  return <><div className="breadcrumb"><Link to="/admin/refunds">Quản lý hoàn tiền</Link><span>/</span><span>{selected ? `Hoàn #${id}` : 'Theo đơn'}</span></div><div className="page-heading payment-page-heading"><div><span className="eyebrow">NGÂN SÁCH & CHỨNG TỪ HOÀN</span><h1>{selected ? `Đối chiếu hoàn #${id}` : summary ? `Hoàn tiền đơn ${summary.orderNumber}` : 'Hoàn tiền theo đơn'}</h1><p>Giữ riêng khoản đã thu, đang chờ hoàn và đã trả lại.</p></div><button className="button secondary" disabled={loading} onClick={() => setRevision(value => value + 1)}>Làm mới hoàn tiền</button></div><RefundNotice /><ErrorNotice error={error} onRetry={() => setRevision(value => value + 1)} />{error?.status === 401 && <Link className="inline-link" to="/login" state={{ returnTo }}>Đăng nhập lại để đối chiếu hoàn</Link>}{loading ? <Loading text="Đang tải ngân sách hoàn…" /> : summary && <div className="payments-layout"><div className="payments-main">{selected ? item ? <RefundResolve key={`${session.user.id}-${id}`} id={id} actorId={session.user.id} selected={item} client={client} onUpdated={setSummary} /> : <div className="notice error">Không tìm thấy yêu cầu hoàn được chọn.</div> : <RefundCreate key={`${session.user.id}-${id}`} id={id} actorId={session.user.id} summary={summary} client={client} onUpdated={setSummary} />}<RefundHistory items={summary.items} selectedId={selected ? summary.selectedRefundId : null} /></div><aside className="payments-aside"><RefundAmounts summary={summary} /><section className="panel"><div className="panel-heading"><h2>Khoản thu gốc</h2></div><div className="order-section-body">{summary.payments.map(payment => <div className="refund-budget" key={payment.id}><h3>#{payment.id} · {allPaymentMethods[payment.method] || payment.method}</h3><p><Status value={payment.status} /></p><dl className="payment-receipt-meta"><div><dt>{payment.status === 'SUCCEEDED' ? 'Đã thu' : 'Giá trị yêu cầu'}</dt><dd>{formatMoney(payment.amount)}</dd></div><div><dt>Đang giữ</dt><dd>{formatMoney(payment.pendingRefundAmount)}</dd></div><div><dt>Đã hoàn</dt><dd>{formatMoney(payment.refundedAmount)}</dd></div><div><dt>Khả dụng</dt><dd>{formatMoney(payment.availableRefundAmount)}</dd></div></dl><Link className="inline-link" to={`/admin/payments/${payment.id}`}>Xem khoản thu #{payment.id}</Link></div>)}</div></section><Link className="button secondary full" to={`/admin/orders/${summary.orderId}`}>Xem đơn hàng liên quan</Link>{selected && <Link className="button secondary full" to={`/admin/orders/${summary.orderId}/refunds`}>Ngân sách & đề nghị hoàn của đơn</Link>}</aside></div>}</>;
}
