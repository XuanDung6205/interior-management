import { useEffect, useMemo, useRef, useState } from 'react';
import { Link, useLocation, useParams } from 'react-router-dom';
import { ErrorNotice, Icon, Loading } from '../../components/ui';
import { useCommerce } from '../../lib/session';
import { formatDate, formatMoney } from '../../lib/format';
import { orderApi } from './orderApi';
import { AddressSnapshot, MoneyBreakdown, OrderItems, OrderNote, paymentMethods, Status } from './OrderParts';

export default function OrderDetailPage() {
  const { api } = useCommerce();
  const client = useMemo(() => orderApi(api), [api]);
  const { id } = useParams();
  const location = useLocation();
  const [order, setOrder] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [revision, setRevision] = useState(0);
  const [cancelOpen, setCancelOpen] = useState(false);
  const [reason, setReason] = useState('');
  const [cancelling, setCancelling] = useState(false);
  const [cancelError, setCancelError] = useState(null);
  const [cancelled, setCancelled] = useState(false);
  const cancelLock = useRef(false);
  const cancelErrorRef = useRef(null);
  useEffect(() => {
    const controller = new AbortController(); setLoading(true); setError(null); setOrder(null); setCancelOpen(false); setCancelled(false);
    client.detail(id, controller.signal).then(value => { if (!controller.signal.aborted) setOrder(value); }).catch(value => { if (value.name !== 'AbortError') setError(value); }).finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [client, id, revision]);
  useEffect(() => { if (cancelError) cancelErrorRef.current?.focus(); }, [cancelError]);
  async function cancel(event) {
    event.preventDefault(); if (cancelLock.current || !reason.trim() || !order.canCancel) return;
    cancelLock.current = true; setCancelling(true); setCancelError(null);
    try { setOrder(await client.cancel(id, reason.trim())); setCancelOpen(false); setCancelled(true); }
    catch (value) { setCancelError(value); }
    finally { cancelLock.current = false; setCancelling(false); }
  }
  return <>
    <div className="breadcrumb"><Link to="/orders">Đơn hàng</Link><span>/</span><span>Chi tiết</span></div>
    <ErrorNotice error={error} onRetry={() => setRevision(value => value + 1)} />
    {loading ? <Loading text="Đang tải chi tiết đơn hàng…" /> : order && <>
      <div className="page-heading order-detail-heading"><div><span className="eyebrow">ĐƠN HÀNG CỦA BẠN</span><h1>{order.orderNumber}</h1><p>Đặt lúc {formatDate(order.placedAt)}</p></div><Status value={order.status} /></div>
      {location.state?.placed && !cancelled && <div className="notice success" role="status"><strong>Đã tiếp nhận đơn hàng.</strong><p>Cửa hàng sẽ xác nhận và chuẩn bị những món đồ bạn chọn. Trạng thái thanh toán được theo dõi riêng bên dưới.</p></div>}
      {cancelled && <div className="notice success" role="status">Đã hủy đơn hàng. Xem thông tin thanh toán và yêu cầu hoàn tiền bên dưới.</div>}
      <div className="order-detail-layout"><div className="order-detail-main"><section className="panel"><div className="panel-heading"><h2>Sản phẩm trong đơn</h2><span className="muted small">Thông tin tại thời điểm đặt</span></div><OrderItems items={order.items} snapshots /></section>
        <section className="panel order-section" aria-label="Thanh toán"><div className="panel-heading"><h2>Thanh toán</h2></div><div className="order-section-body">{order.payments.length ? order.payments.map(payment => <div className="payment-record" key={payment.id}><div><strong>{paymentMethods[payment.method] || payment.method}</strong><p>{formatMoney(payment.amount)}</p>{payment.paidAt && <p className="muted small">Thu lúc {formatDate(payment.paidAt)}</p>}</div><Status value={payment.status} /></div>) : <p className="muted">Chưa có khoản thanh toán.</p>}<OrderNote>Đơn được tiếp nhận không có nghĩa là đã thu tiền. Chuyển khoản cần được cửa hàng xác minh.</OrderNote></div></section>
        <section className="panel order-section" aria-label="Giao hàng"><div className="panel-heading"><h2>Giao hàng</h2></div><div className="order-section-body">{order.shipments.length ? order.shipments.map(shipment => <div className="shipment-record" key={shipment.id}><div className="payment-record"><div><strong>{shipment.carrier || 'Đơn vị vận chuyển đang cập nhật'}</strong>{shipment.trackingNumber && <p>Mã vận đơn: <span>{shipment.trackingNumber}</span></p>}{shipment.shippedAt && <p className="muted small">Xuất kho: {formatDate(shipment.shippedAt)}</p>}{shipment.deliveredAt && <p className="muted small">Giao thành công: {formatDate(shipment.deliveredAt)}</p>}</div><Status value={shipment.status} /></div>{order.shipmentEvents?.filter(event => event.shipmentId === shipment.id).length > 0 && <ol className="shipment-event-list">{order.shipmentEvents.filter(event => event.shipmentId === shipment.id).map((event, index) => <li key={`${event.changedAt}-${index}`}><Status value={event.toStatus} /><span>{formatDate(event.changedAt)} · {event.reason}</span></li>)}</ol>}{shipment.status === 'RETURNED' && <OrderNote>Hàng đã hoàn về kho. Cửa hàng sẽ xử lý yêu cầu tiếp theo của đơn.</OrderNote>}</div>) : <p className="muted">Chưa có thông tin vận chuyển.</p>}</div></section>
        <section className="panel order-section" aria-label="Hoàn tiền"><div className="panel-heading"><h2>Hoàn tiền</h2></div><div className="order-section-body">{order.refunds.length ? <>{order.refunds.map(refund => <div className="payment-record" key={refund.id}><div><strong>{formatMoney(refund.amount)}</strong><p>{refund.reason}</p><small className="muted">Khoản thanh toán #{refund.paymentId}</small></div><Status value={refund.status} kind="refund" /></div>)}{order.refunds.some(refund => refund.status === 'PENDING' || refund.status === 'PROCESSING') && <OrderNote>Yêu cầu hoàn đang chờ được cửa hàng xử lý và xác minh; đây chưa phải xác nhận đã nhận lại tiền.</OrderNote>}</> : <p className="muted">Chưa có yêu cầu hoàn tiền.</p>}</div></section>
        <section className="panel order-section" aria-label="Lịch sử đơn hàng"><div className="panel-heading"><h2>Lịch sử đơn hàng</h2></div><ol className="order-timeline">{order.history.map((entry, index) => <li key={`${entry.changedAt}-${index}`}><div><Status value={entry.toStatus} /><time dateTime={entry.changedAt}>{formatDate(entry.changedAt)}</time></div>{entry.note && <p>{entry.note}</p>}</li>)}</ol></section>
      </div><aside className="order-detail-aside"><section className="panel order-summary"><h2>Chi tiết số tiền</h2><MoneyBreakdown value={order} /><OrderNote>Số tiền và sản phẩm được lưu theo đơn đã đặt.</OrderNote></section><section className="panel address-panel"><h2>Địa chỉ nhận hàng</h2><AddressSnapshot address={order.shippingAddress} /></section>{order.customerNote && <section className="panel address-panel"><h2>Ghi chú của bạn</h2><p className="customer-note">{order.customerNote}</p></section>}
        {order.cancelledAt && <section className="notice"><strong>Đã hủy lúc {formatDate(order.cancelledAt)}</strong>{order.cancelReason && <p>{order.cancelReason}</p>}</section>}
        {order.canCancel ? <section className="panel cancellation-panel"><h2>Cần thay đổi lựa chọn?</h2><p>Đơn còn đủ điều kiện hủy theo trạng thái hiện tại. Cửa hàng kiểm tra lại khi gửi yêu cầu.</p>{cancelOpen ? <form onSubmit={cancel}><div ref={cancelErrorRef} tabIndex="-1"><ErrorNotice error={cancelError} /></div><label htmlFor="cancel-reason">Lý do hủy đơn</label><textarea id="cancel-reason" autoFocus required maxLength={1000} rows={3} value={reason} disabled={cancelling} onChange={event => setReason(event.target.value)} aria-describedby={cancelError?.fieldErrors?.reason ? 'cancel-reason-error' : undefined} />{cancelError?.fieldErrors?.reason && <span className="field-error" id="cancel-reason-error">{cancelError.fieldErrors.reason}</span>}<p className="small muted">Nếu đã thu tiền, cửa hàng sẽ tạo yêu cầu hoàn tiền để xử lý riêng.</p><div className="cancel-actions"><button type="button" className="button secondary" disabled={cancelling} onClick={() => setCancelOpen(false)}>Giữ đơn</button><button className="button danger" disabled={cancelling || !reason.trim()}>{cancelling ? 'Đang hủy…' : 'Xác nhận hủy đơn'}</button></div></form> : <button className="text-button destructive" onClick={() => setCancelOpen(true)}>Hủy đơn hàng</button>}</section> : order.cancellationMessage && <div className="notice"><p>{order.cancellationMessage}</p></div>}
      </aside></div><Link to="/orders" className="inline-link">← Tất cả đơn hàng</Link>
    </>}
  </>;
}
