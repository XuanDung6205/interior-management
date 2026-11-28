import { useEffect, useMemo, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { ErrorNotice, Icon, Loading, OrderCode, Pager } from '../../components/ui';
import { useCommerce } from '../../lib/session';
import { formatDate, formatMoney } from '../../lib/format';
import { orderApi } from './orderApi';
import { orderStates, Status } from './OrderParts';

export default function OrdersPage() {
  const { api } = useCommerce();
  const client = useMemo(() => orderApi(api), [api]);
  const [params, setParams] = useSearchParams();
  const rawPage = Number(params.get('page') || 0);
  const page = Number.isSafeInteger(rawPage) && rawPage >= 0 ? rawPage : 0;
  const status = Object.hasOwn(orderStates, params.get('status')) ? params.get('status') : '';
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    const controller = new AbortController(); setLoading(true); setError(null);
    client.list(page, status, controller.signal).then(value => { if (!controller.signal.aborted) setData(value); }).catch(value => { if (value.name !== 'AbortError') setError(value); }).finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [client, page, status, revision]);
  return <>
    <div className="breadcrumb"><Link to="/cart">Nếp Nhà</Link><span>/</span><span>Đơn hàng</span></div>
    <div className="page-heading"><div><span className="eyebrow">HÀNH TRÌNH ĐẾN TỔ ẤM</span><h1>Đơn hàng của bạn<span className="heading-dot">.</span></h1><p>Theo dõi từng lựa chọn trên đường về nhà.</p></div><Link className="inline-link" to="/cart">Tiếp tục khám phá<Icon name="arrow" size={18} /></Link></div>
    <section className="panel orders-panel"><div className="panel-heading"><h2>Danh sách đơn hàng <span className="count-badge">{data?.totalElements ?? '—'}</span></h2><div className="order-filter"><label htmlFor="order-status">Trạng thái đơn hàng</label><select id="order-status" value={status} onChange={event => setParams({ page: '0', status: event.target.value })}><option value="">Tất cả trạng thái</option>{Object.entries(orderStates).map(([value, label]) => <option value={value} key={value}>{label}</option>)}</select></div></div>
      <ErrorNotice error={error} onRetry={() => setRevision(value => value + 1)} />
      {loading ? <Loading text="Đang tải đơn hàng…" /> : !error && data && (data.items.length ? <div className="orders-list">{data.items.map(order => <article className="order-card" key={order.id}><div className="order-card-top"><div><span className="eyebrow">{formatDate(order.placedAt)}</span><h2><OrderCode value={order.orderNumber} /></h2></div><Status value={order.status} /></div><div className="order-card-states"><span>Thanh toán <Status value={order.paymentStatus} /></span><span>Giao hàng <Status value={order.shipmentStatus} /></span></div><div className="order-card-bottom"><span>{order.itemCount} sản phẩm</span><strong>{formatMoney(order.grandTotal)}</strong><Link to={`/orders/${order.id}`} aria-label={`Xem đơn ${order.orderNumber}`} className="button secondary">Xem chi tiết<Icon name="arrow" size={18} /></Link></div></article>)}</div> : <div className="empty-state"><Icon name="bag" size={38} /><h2>Chưa có đơn hàng phù hợp</h2><p>{status ? 'Thử chọn trạng thái khác để xem các đơn của bạn.' : 'Những món đồ bạn chọn sẽ xuất hiện ở đây sau khi đặt hàng.'}</p><Link to="/cart" className="button">Về giỏ hàng</Link></div>)}
      <Pager page={data} busy={loading} onChange={value => setParams({ page: String(value), status })} />
    </section>
  </>;
}
