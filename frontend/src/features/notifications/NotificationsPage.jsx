import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { ErrorNotice, Loading, Pager } from '../../components/ui';
import { useCommerce } from '../../lib/session';
import { formatDate } from '../../lib/format';

export function UnreadBadge() {
  const { api, session } = useCommerce();
  const [count, setCount] = useState(null);
  useEffect(() => {
    if (!session?.authenticated) return;
    let active = true;
    const controller = new AbortController();
    const load = () => api.request('/notifications/unread-count', { signal: controller.signal }).then(value => { if (active) setCount(value.unreadCount); }).catch(() => { if (active) setCount(null); });
    load(); const timer = setInterval(load, 30000); window.addEventListener('commerce:notifications', load);
    return () => { active = false; controller.abort(); clearInterval(timer); window.removeEventListener('commerce:notifications', load); };
  }, [api, session?.authenticated, session?.user?.id]);
  return count > 0 ? <span className="notification-count" aria-label={`${count} thông báo chưa đọc`}>{count > 99 ? '99+' : count}</span> : null;
}

export default function NotificationsPage() {
  const { api } = useCommerce();
  const [data, setData] = useState(null); const [page, setPage] = useState(0); const [unread, setUnread] = useState(false);
  const [revision, setRevision] = useState(0); const [error, setError] = useState(null); const [busy, setBusy] = useState(false);
  useEffect(() => {
    const controller = new AbortController(); setError(null);
    api.request(`/notifications?${new URLSearchParams({ page, size: 10, unreadOnly: unread })}`, { signal: controller.signal }).then(value => { if (!controller.signal.aborted) setData(value); }).catch(value => { if (value.name !== 'AbortError') setError(value); });
    const timer = setInterval(() => setRevision(value => value + 1), 30000);
    return () => { controller.abort(); clearInterval(timer); };
  }, [api, page, unread, revision]);
  async function read(id) {
    if (busy) return; setBusy(true); setError(null);
    try { await api.request(`/notifications/${id}/read`, { method: 'POST' }); setData(current => ({ ...current, items: current.items.map(item => item.id === id ? { ...item, readAt: item.readAt || new Date().toISOString() } : item) })); setRevision(value => value + 1); window.dispatchEvent(new Event('commerce:notifications')); }
    catch (value) { setError(value); } finally { setBusy(false); }
  }
  return <><div className="page-heading"><div><span className="eyebrow">TÀI KHOẢN / HỘP THƯ</span><h1>Thông báo của bạn</h1><p>Cập nhật đơn hàng tại cửa hàng. Đây là thông báo web.</p></div><button className="button secondary" onClick={() => setRevision(value => value + 1)}>Làm mới thông báo</button></div><ErrorNotice error={error} onRetry={() => setRevision(value => value + 1)} />{error?.status === 401 && <Link to="/login" state={{ returnTo: '/notifications' }}>Đăng nhập lại</Link>}<label className="checkbox-row"><input type="checkbox" checked={unread} onChange={event => { setUnread(event.target.checked); setPage(0); setData(null); }} />Chỉ thông báo chưa đọc</label>{!data && !error ? <Loading /> : data && <><div className="aftercare-stack">{data.items.length ? data.items.map(item => <article className="panel order-section-body" key={item.id}><h2>{item.title}</h2><p>{item.body}</p><p className="muted small">{formatDate(item.createdAt)} · {item.readAt ? 'Đã đọc' : 'Chưa đọc'}</p><div className="actions">{Number.isSafeInteger(item.orderId) && item.orderId > 0 && <Link className="inline-link" to={`/orders/${item.orderId}`}>Xem đơn {item.orderNumber}</Link>}{!item.readAt && <button className="button secondary" disabled={busy} onClick={() => read(item.id)}>Đánh dấu đã đọc</button>}</div></article>) : <p className="panel order-section-body">Chưa có thông báo.</p>}</div><Pager page={data} busy={busy} onChange={setPage} /></>}</>;
}
