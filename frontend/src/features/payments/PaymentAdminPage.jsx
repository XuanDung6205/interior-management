import { useEffect, useMemo, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { ErrorNotice, Loading, OrderCode, Pager } from '../../components/ui';
import { useCommerce } from '../../lib/session';
import { formatDate, formatMoney } from '../../lib/format';
import { Status } from '../orders/OrderParts';
import { allPaymentMethods, PaymentNotice, paymentStatuses } from './PaymentParts';
import { SePayEventReview, SePayJobs } from './SePayParts';
import { paymentApi } from './paymentApi';

export default function PaymentAdminPage() {
  const { api, session } = useCommerce(); const client = useMemo(() => paymentApi(api), [api]); const [params, setParams] = useSearchParams();
  const page = /^\d{1,9}$/.test(params.get('page') || '0') ? Number(params.get('page') || 0) : 0;
  const status = Object.hasOwn(paymentStatuses, params.get('status')) ? params.get('status') : '';
  const method = Object.hasOwn(allPaymentMethods, params.get('method')) ? params.get('method') : '';
  const query = (params.get('query') || '').slice(0, 150); const [search, setSearch] = useState(query);
  const [data, setData] = useState(null); const [loading, setLoading] = useState(true); const [error, setError] = useState(null); const [revision, setRevision] = useState(0);
  useEffect(() => { setSearch(query); }, [query]);
  useEffect(() => {
    const controller = new AbortController(); setLoading(true); setError(null);
    client.list(page, status, method, query, controller.signal).then(value => { if (!controller.signal.aborted) setData(value); }).catch(failure => { if (!controller.signal.aborted && failure.name !== 'AbortError') setError(failure); }).finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [client, page, status, method, query, revision]);
  const filter = changes => setParams({ page: '0', status, method, query, ...changes });
  return <>
    <div className="breadcrumb"><span>Quản trị</span><span>/</span><span>Khoản thu</span></div>
    <div className="page-heading payment-page-heading"><div><span className="eyebrow">ĐỐI CHIẾU & XÁC MINH</span><h1>Quản lý thanh toán<span className="heading-dot">.</span></h1><p>Đối chiếu chứng từ trước khi ghi nhận khoản thu.</p></div></div>
    <PaymentNotice overview />
    <section className="panel admin-payments-list"><div className="panel-heading payment-list-toolbar"><h2>Các khoản thanh toán <span className="count-badge">{data?.totalElements ?? '—'}</span></h2><form className="search-form" onSubmit={event => { event.preventDefault(); filter({ query: search.trim() }); }}><label htmlFor="payment-search" className="sr-only">Tìm khoản thanh toán</label><input id="payment-search" type="search" maxLength={150} value={search} onChange={event => setSearch(event.target.value)} placeholder="Mã đơn, khách hoặc tham chiếu" /><button className="button secondary" disabled={loading}>Tìm kiếm</button></form><div className="payment-list-filters"><div className="payment-list-filter"><label htmlFor="payment-status-filter">Trạng thái thanh toán</label><select id="payment-status-filter" value={status} onChange={event => filter({ status: event.target.value })}><option value="">Tất cả trạng thái</option>{Object.entries(paymentStatuses).map(([value, label]) => <option value={value} key={value}>{label}</option>)}</select></div><div className="payment-list-filter"><label htmlFor="payment-method-filter">Phương thức thanh toán</label><select id="payment-method-filter" value={method} onChange={event => filter({ method: event.target.value })}><option value="">Tất cả phương thức</option>{Object.entries(allPaymentMethods).map(([value, label]) => <option value={value} key={value}>{label}</option>)}</select></div></div></div><ErrorNotice error={error} onRetry={() => setRevision(value => value + 1)} />
      {loading ? <Loading text="Đang tải khoản thanh toán…" /> : !error && data && (data.items.length ? data.items.map(payment => <article className="payment-list-record" key={payment.id}><div><span className="eyebrow">KHOẢN #{payment.id} · {formatDate(payment.createdAt)}</span><h2><OrderCode value={payment.orderNumber} /></h2><p>{payment.customer?.fullName || payment.customer?.username || 'Khách không có tài khoản'}</p><Link className="inline-link" to={`/admin/orders/${payment.orderId}`}>Xem đơn hàng</Link></div><div><strong>{allPaymentMethods[payment.method] || payment.method}</strong><p><Status value={payment.status} /></p>{payment.reference && <p className="muted receipt-reference">{payment.reference}</p>}{payment.isExpired && <p className="field-error">Yêu cầu đã hết hạn</p>}<p className="muted small">Đơn hàng <Status value={payment.orderStatus} /></p></div><div className="payment-list-amount"><strong>{formatMoney(payment.amount)}</strong><Link className="button secondary" to={`/admin/payments/${payment.id}`} aria-label={`Xác minh khoản ${payment.id}`}>Xem & đối chiếu</Link></div></article>) : <div className="empty-state"><h2>Chưa có khoản thanh toán phù hợp</h2><p>Thử bộ lọc khác hoặc tìm theo mã đơn, khách hàng và tham chiếu.</p></div>)}<Pager page={data} busy={loading} onChange={value => setParams({ page: String(value), status, method, query })} />
    </section>
    <SePayJobs client={client}/><SePayEventReview client={client} userId={session.user.id}/>
  </>;
}
