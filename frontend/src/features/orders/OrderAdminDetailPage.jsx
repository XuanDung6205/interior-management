import { useEffect, useMemo, useRef, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { ErrorNotice, Icon, Loading, OrderCode } from '../../components/ui';
import { useCommerce } from '../../lib/session';
import { formatDate } from '../../lib/format';
import { AddressSnapshot, AssemblySelection, MoneyBreakdown, OrderItems, Status } from './OrderParts';
import { adminOrderApi } from './adminOrderApi';
import { OrderEvidence, PublicOrderHistory } from './OrderEvidence';
import InternalNoteEditor from './InternalNoteEditor';
import OrderLifecycleActions from './OrderLifecycleActions';

export default function OrderAdminDetailPage() {
  const { id } = useParams();
  const { api } = useCommerce();
  const client = useMemo(() => adminOrderApi(api), [api]);
  const [order, setOrder] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const lock = useRef(false);
  useEffect(() => {
    const controller = new AbortController(); setLoading(true); setError(null); setOrder(null);
    client.detail(id, controller.signal).then(value => { if (!controller.signal.aborted) setOrder(value); }).catch(value => { if (value.name !== 'AbortError') setError(value); }).finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [client, id]);
  async function perform(operation) {
    if (lock.current) return null;
    lock.current = true; setBusy(true);
    try { return await operation(); }
    finally { lock.current = false; setBusy(false); }
  }
  async function refresh() {
    return perform(async () => {
      setError(null);
      try { const latest = await client.detail(id); setOrder(latest); return latest; }
      catch (failure) { setError(failure); return null; }
    });
  }
  return <>
    <div className="breadcrumb"><Link to="/admin/orders">Quản lý đơn hàng</Link><span>/</span><span>Chi tiết</span></div>
    <ErrorNotice error={error} onRetry={refresh} />
    {loading ? <Loading text="Đang tải đơn hàng để xử lý…" /> : order && <>
      <div className="page-heading order-detail-heading"><div><span className="eyebrow">QUẢN TRỊ / CHI TIẾT ĐƠN</span><h1><OrderCode value={order.orderNumber} /></h1><p>Đặt lúc {formatDate(order.placedAt)}{order.updatedAt ? ` · Cập nhật ${formatDate(order.updatedAt)}` : ''}</p></div><div className="admin-detail-heading-actions"><Status value={order.status} /><Link className="button secondary" to={`/admin/orders/${order.id}/print`}>In phiếu đơn hàng</Link><button className="text-button" disabled={busy} onClick={refresh}>Tải lại thông tin đơn</button></div></div>
      <div className="admin-order-layout"><div className="order-detail-main">
        <section className="panel customer-profile"><div className="panel-heading"><h2>Khách hàng & người nhận</h2><span className="staff-workspace"><Icon name="shield" size={16} />Thông tin phục vụ đơn hàng</span></div><div className="order-section-body customer-profile-grid"><div><span className="eyebrow">TÀI KHOẢN</span><h3>{order.customer?.fullName || order.customer?.username || 'Khách không có tài khoản'}</h3>{order.customer?.username && <p className="muted">@{order.customer.username}</p>}{order.customer?.email && <p>{order.customer.email}</p>}{order.customer?.id == null && <p className="muted small">Đơn lịch sử không gắn với tài khoản.</p>}</div><div><span className="eyebrow">GIAO ĐẾN</span><AddressSnapshot address={order.shippingAddress} /></div></div></section>
        <section className="panel"><div className="panel-heading"><h2>Sản phẩm trong đơn</h2><span className="muted small">Snapshot tại thời điểm đặt</span></div><OrderItems items={order.items} snapshots /></section>
        <OrderEvidence order={order} />
        <PublicOrderHistory history={order.history} />
        <InternalNoteEditor key={order.id} order={order} client={client} busy={busy} perform={perform} onUpdated={setOrder} />
      </div><aside className="order-detail-aside admin-order-aside">
        <Link className="button secondary full" to={`/admin/orders/${order.id}/aftercare`}>Hậu mãi, nhận trả & lắp đặt</Link><Link className="button secondary full" to={`/admin/orders/${order.id}/refunds`}>Đối chiếu hoàn tiền của đơn</Link><OrderLifecycleActions key={order.id} order={order} client={client} busy={busy} perform={perform} onUpdated={setOrder} onRefresh={refresh} />
        <section className="panel order-summary"><h2>Chi tiết số tiền</h2><MoneyBreakdown value={order} /></section>
        <AssemblySelection requested={order.assemblyRequested} />
        {order.customerNote && <section className="panel address-panel"><h2>Ghi chú của khách</h2><p className="customer-note">{order.customerNote}</p></section>}
        {(order.confirmedAt || order.completedAt || order.cancelledAt) && <section className="panel order-milestones"><h2>Các mốc xử lý</h2><dl>{order.confirmedAt && <div><dt>Xác nhận</dt><dd>{formatDate(order.confirmedAt)}</dd></div>}{order.completedAt && <div><dt>Hoàn thành</dt><dd>{formatDate(order.completedAt)}</dd></div>}{order.cancelledAt && <div><dt>Hủy đơn</dt><dd>{formatDate(order.cancelledAt)}</dd></div>}</dl>{order.cancelReason && <p>{order.cancelReason}</p>}</section>}
      </aside></div><Link to="/admin/orders" className="inline-link">← Tất cả đơn của cửa hàng</Link>
    </>}
  </>;
}
