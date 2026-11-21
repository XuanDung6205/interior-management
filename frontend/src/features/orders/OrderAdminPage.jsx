import { useEffect, useMemo, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { ErrorNotice, Icon, Loading, Pager } from '../../components/ui';
import { useCommerce } from '../../lib/session';
import { formatDate, formatMoney } from '../../lib/format';
import { orderStates, Status } from './OrderParts';
import { adminOrderApi } from './adminOrderApi';

export default function OrderAdminPage() {
  const { api } = useCommerce();
  const client = useMemo(() => adminOrderApi(api), [api]);
  const [params, setParams] = useSearchParams();
  const rawPage = params.get('page') || '0';
  const page = /^\d{1,9}$/.test(rawPage) ? Number(rawPage) : 0;
  const status = Object.hasOwn(orderStates, params.get('status')) ? params.get('status') : '';
  const query = (params.get('query') || '').slice(0, 150);
  const fromDate = params.get('fromDate') || '';
  const toDate = params.get('toDate') || '';
  const [search, setSearch] = useState(query);
  const [fromDraft, setFromDraft] = useState(fromDate);
  const [toDraft, setToDraft] = useState(toDate);
  const [exporting, setExporting] = useState(false);
  const [exportError, setExportError] = useState(null);
  const exportController = useRef(null);
  const [data, setData] = useState(null);
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(true);
  const [revision, setRevision] = useState(0);
  useEffect(() => { setSearch(query); setFromDraft(fromDate); setToDraft(toDate); setExportError(null); }, [query, fromDate, toDate]);
  useEffect(() => () => exportController.current?.abort(), []);
  function applyFilters(next = {}) { setParams({ page: '0', status, query, ...(fromDate ? { fromDate } : {}), ...(toDate ? { toDate } : {}), ...next }); }
  async function exportCsv() {
    if (exportController.current) return;
    const controller = new AbortController(); exportController.current = controller; setExporting(true); setExportError(null);
    try {
      const { blob, filename } = await client.exportCsv(status, query, fromDate, toDate, controller.signal);
      if (controller.signal.aborted) return;
      const objectUrl = URL.createObjectURL(blob);
      const anchor = document.createElement('a'); anchor.href = objectUrl; anchor.download = filename;
      try { document.body.appendChild(anchor); anchor.click(); }
      finally { anchor.remove(); URL.revokeObjectURL(objectUrl); }
    } catch (failure) { if (!controller.signal.aborted && failure.name !== 'AbortError') setExportError(failure); }
    finally { if (exportController.current === controller) exportController.current = null; if (!controller.signal.aborted) setExporting(false); }
  }
  useEffect(() => {
    const controller = new AbortController(); setLoading(true); setError(null);
    client.list(page, status, query, controller.signal, fromDate, toDate).then(value => { if (!controller.signal.aborted) setData(value); }).catch(value => { if (value.name !== 'AbortError') setError(value); }).finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [client, page, status, query, fromDate, toDate, revision]);
  return <>
    <div className="breadcrumb"><span>Quản trị</span><span>/</span><span>Đơn hàng</span></div>
    <div className="page-heading"><div><span className="eyebrow">KHÔNG GIAN QUẢN TRỊ</span><h1>Quản lý đơn hàng<span className="heading-dot">.</span></h1><p>Theo dõi yêu cầu, xử lý từng bước và giữ trọn lịch sử.</p></div><span className="staff-workspace"><Icon name="shield" size={17} />Nhân viên & quản trị viên</span></div>
    <section className="panel admin-orders-panel"><div className="panel-heading admin-orders-toolbar"><h2>Tất cả đơn hàng <span className="count-badge">{data?.totalElements ?? '—'}</span></h2><form className="search-form" onSubmit={event => { event.preventDefault(); applyFilters({ query: search.trim(), fromDate: fromDraft, toDate: toDraft }); }}><Icon name="search" /><label className="sr-only" htmlFor="admin-order-search">Tìm đơn hàng</label><input id="admin-order-search" type="search" maxLength={150} value={search} onChange={event => setSearch(event.target.value)} placeholder="Mã đơn, khách hàng hoặc người nhận" /><div className="order-date-filter"><label htmlFor="admin-order-from">Từ ngày (giờ Việt Nam)</label><input id="admin-order-from" type="date" value={fromDraft} onChange={event => setFromDraft(event.target.value)} /></div><div className="order-date-filter"><label htmlFor="admin-order-to">Đến ngày (giờ Việt Nam)</label><input id="admin-order-to" type="date" value={toDraft} onChange={event => setToDraft(event.target.value)} /></div><button className="button secondary" disabled={loading}>Tìm kiếm</button></form><div className="order-filter"><label htmlFor="admin-order-status">Trạng thái đơn hàng</label><select id="admin-order-status" value={status} onChange={event => applyFilters({ status: event.target.value })}><option value="">Tất cả trạng thái</option>{Object.entries(orderStates).map(([value, label]) => <option value={value} key={value}>{label}</option>)}</select></div><button className="button secondary" disabled={exporting || loading} onClick={exportCsv}>{exporting ? 'Đang xuất CSV…' : 'Xuất CSV'}</button></div><p className="order-export-help muted small">Xuất tất cả đơn theo bộ lọc đã áp dụng, tối đa 5000 đơn. Ngày đặt theo giờ Việt Nam, gồm cả ngày đầu và ngày cuối.</p><ErrorNotice error={exportError} /><ErrorNotice error={error} onRetry={() => setRevision(value => value + 1)} />
      {loading ? <Loading text="Đang tải đơn hàng của cửa hàng…" /> : !error && data && (data.items.length ? <div className="admin-orders-list">{data.items.map(order => <article className="admin-order-row" key={order.id}><div className="admin-order-reference"><span className="eyebrow">{formatDate(order.placedAt)}</span><h2>{order.orderNumber}</h2><Status value={order.status} /></div><div className="admin-order-customer"><strong>{order.customer?.fullName || order.customer?.username || 'Khách không có tài khoản'}</strong>{order.customer?.username && <span>@{order.customer.username}</span>}{order.customer?.email && <span>{order.customer.email}</span>}<p>Người nhận: {order.recipientName || 'Chưa có tên'}{order.recipientPhone ? ` · ${order.recipientPhone}` : ''}</p></div><div className="admin-order-evidence"><span>Thanh toán <Status value={order.paymentStatus} /></span><span>Giao hàng <Status value={order.shipmentStatus} /></span></div><div className="admin-order-total"><strong>{formatMoney(order.grandTotal)}</strong><span>{order.itemCount} sản phẩm</span><Link className="button secondary" to={`/admin/orders/${order.id}`} aria-label={`Quản lý đơn ${order.orderNumber}`}>Xem & xử lý<Icon name="arrow" size={17} /></Link></div></article>)}</div> : <div className="empty-state"><Icon name="orders" size={38} /><h2>Chưa có đơn hàng phù hợp</h2><p>Thử trạng thái khác, mã đơn hoặc thông tin người nhận.</p></div>)}<Pager page={data} busy={loading} onChange={value => applyFilters({ page: String(value) })} />
    </section>
  </>;
}
