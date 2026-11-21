import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { ErrorNotice, Loading } from '../../components/ui';
import { useCommerce } from '../../lib/session';
import { ApiError } from '../../lib/apiClient';
import { formatDate, formatMoney } from '../../lib/format';
import { AddressSnapshot, MoneyBreakdown, paymentMethods, Status } from './OrderParts';

function validSnapshot(value, id) {
  return value && String(value.id) === id && typeof value.orderNumber === 'string' && Array.isArray(value.items) && Array.isArray(value.payments) && Array.isArray(value.refunds) && ['collectedAmount', 'refundedAmount', 'pendingRefundAmount'].every(key => typeof value[key] === 'string' && /^\d+\.\d{2}$/.test(value[key]));
}
export default function OrderPrintPage({ staff = false }) {
  const { id } = useParams();
  const { api, session } = useCommerce();
  const actor = session.user?.id;
  const base = staff ? '/admin/orders' : '/orders';
  const [snapshot, setSnapshot] = useState(null);
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(true);
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    const controller = new AbortController(); setSnapshot(null); setError(null); setLoading(true);
    api.request(`${base}/${encodeURIComponent(id)}/print`, { signal: controller.signal, cache: 'no-store' }).then(value => {
      if (controller.signal.aborted) return;
      if (!validSnapshot(value, id)) throw new ApiError(200, { code: 'INVALID_RESPONSE', message: 'Chưa đọc được phiếu đơn hàng hợp lệ.' });
      setSnapshot({ actor, id, value });
    }).catch(failure => { if (!controller.signal.aborted && failure.name !== 'AbortError') setError(failure); }).finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [api, base, id, actor, revision]);
  const order = snapshot?.actor === actor && snapshot?.id === id ? snapshot.value : null;
  return <div className="order-print-page">
    <div className="order-print-actions"><Link className="inline-link" to={`${base}/${id}`}>← Chi tiết đơn hàng</Link>{order && !loading && <button className="button" onClick={() => window.print()}>In / Lưu PDF</button>}</div>
    <ErrorNotice error={error} onRetry={() => setRevision(value => value + 1)} />
    {loading ? <Loading text="Đang tải phiếu đơn hàng…" /> : order && <article className="order-print-sheet" aria-label="Phiếu đơn hàng">
      <header className="order-print-heading"><div><span className="eyebrow">NẾP NHÀ</span><h1>Phiếu đơn hàng</h1><h2>{order.orderNumber}</h2><p>Đặt lúc {formatDate(order.placedAt)} · {order.currency}</p></div><Status value={order.status} /></header>
      <p className="order-print-disclaimer">Phiếu ghi nhận đơn đặt hàng. Đây không phải biên lai xác nhận đã thanh toán.</p>
      <section className="order-print-shipping"><h2>Thông tin giao hàng</h2><AddressSnapshot address={order.shippingAddress} /><p>{order.assemblyRequested ? 'Có chọn dịch vụ lắp đặt.' : 'Không chọn dịch vụ lắp đặt.'}</p></section>
      <section><h2>Sản phẩm trong đơn</h2><table className="order-print-table"><colgroup><col className="print-product-col" /><col /><col /><col /></colgroup><thead><tr><th scope="col">Sản phẩm</th><th scope="col">SL</th><th scope="col">Đơn giá</th><th scope="col">Thành tiền</th></tr></thead><tbody>{order.items.map((item, index) => <tr key={item.id ?? index}><td><strong>{item.productName}</strong><span>{item.sku}</span><span>{[item.variantName, item.colorName].filter(Boolean).join(' · ')}</span></td><td>{item.quantity}</td><td>{formatMoney(item.unitPrice)}</td><td>{formatMoney(item.lineTotal)}</td></tr>)}</tbody></table></section>
      <div className="order-print-totals"><section><h2>Chi tiết số tiền</h2><MoneyBreakdown value={order} /></section><section><h2>Đối chiếu thực tế</h2><dl className="money-breakdown"><div><dt>Đã thu</dt><dd>{formatMoney(order.collectedAmount)}</dd></div><div><dt>Đã hoàn</dt><dd>{formatMoney(order.refundedAmount)}</dd></div><div><dt>Đang chờ hoàn</dt><dd>{formatMoney(order.pendingRefundAmount)}</dd></div></dl><p className="small muted">Các khoản thực thu và hoàn tiền được ghi nhận riêng với tổng đơn.</p></section></div>
      <section><h2>Thanh toán</h2>{order.payments.length ? <ul className="order-print-records">{order.payments.map(payment => <li key={payment.id}><span>{paymentMethods[payment.method] || payment.method} · {formatMoney(payment.amount)}{payment.paidAt ? ` · ${formatDate(payment.paidAt)}` : ''}</span><Status value={payment.status} /></li>)}</ul> : <p>Chưa có khoản thanh toán.</p>}</section>
      {order.refunds.length > 0 && <section><h2>Hoàn tiền</h2><ul className="order-print-records">{order.refunds.map(refund => <li key={refund.id}><span>{formatMoney(refund.amount)} · {refund.reason}</span><Status value={refund.status} kind="refund" /></li>)}</ul></section>}
      {order.customerNote && <section><h2>Ghi chú của khách</h2><p className="customer-note">{order.customerNote}</p></section>}
    </article>}
  </div>;
}
