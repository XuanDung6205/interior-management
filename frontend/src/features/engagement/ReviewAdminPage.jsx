import { useEffect, useMemo, useState } from 'react';
import { ErrorNotice, Loading, Pager } from '../../components/ui';
import { formatDate } from '../../lib/format';
import { useCommerce } from '../../lib/session';
import { engagementApi } from './engagementApi';

// Trang nhân viên duyệt/ẩn/từ chối và phản hồi đánh giá sản phẩm (route /admin/reviews)
export default function ReviewAdminPage() {
  const { api } = useCommerce();
  const client = useMemo(() => engagementApi(api), [api]);
  const [data, setData] = useState(null);
  const [status, setStatus] = useState('');
  const [search, setSearch] = useState('');
  const [query, setQuery] = useState('');
  const [page, setPage] = useState(0);
  const [replies, setReplies] = useState({});
  const [busyId, setBusyId] = useState(null);
  const [error, setError] = useState(null);
  const [notice, setNotice] = useState('');
  const [revision, setRevision] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    setError(null);
    client.adminReviews(status, query, page, controller.signal)
      .then(setData)
      .catch(err => { if (err.name !== 'AbortError') setError(err); });
    return () => controller.abort();
  }, [client, status, query, page, revision]);

  async function moderate(review, nextStatus) {
    setBusyId(review.id);
    setError(null);
    setNotice('');
    try {
      await client.moderateReview(review.id, {
        status: nextStatus,
        staffReply: replies[review.id] !== undefined ? replies[review.id] : review.staffReply,
      });
      setNotice(`Đã cập nhật đánh giá #${review.id} sang ${nextStatus}.`);
      setRevision(v => v + 1);
    } catch (err) {
      setError(err);
    } finally {
      setBusyId(null);
    }
  }

  return <>
    <div className="breadcrumb"><span>Quản trị</span><span>/</span><span>Đánh giá</span></div>
    <div className="page-heading">
      <div>
        <span className="eyebrow">KHÔNG GIAN QUẢN TRỊ</span>
        <h1>Kiểm duyệt đánh giá<span className="heading-dot">.</span></h1>
        <p>Duyệt hiển thị đánh giá đã mua và gửi phản hồi chính thức của cửa hàng.</p>
      </div>
    </div>

    <ErrorNotice error={error} onRetry={() => setRevision(v => v + 1)} />
    {notice && <div className="notice success" role="status">{notice}</div>}

    <section className="panel">
      <div className="panel-heading coupon-toolbar">
        <h2>Danh sách đánh giá <span className="count-badge">{data?.totalElements ?? '—'}</span></h2>
        <form className="search-form" onSubmit={e => { e.preventDefault(); setPage(0); setQuery(search.trim()); }}>
          <select aria-label="Lọc trạng thái" value={status} onChange={e => { setPage(0); setStatus(e.target.value); }}>
            <option value="">Tất cả trạng thái</option>
            <option value="PENDING">Chờ duyệt (PENDING)</option>
            <option value="APPROVED">Đã duyệt (APPROVED)</option>
            <option value="HIDDEN">Đã ẩn (HIDDEN)</option>
            <option value="REJECTED">Từ chối (REJECTED)</option>
          </select>
          <input type="search" value={search} onChange={e => setSearch(e.target.value)} placeholder="Sản phẩm, người gửi..." />
          <button className="button secondary">Lọc</button>
        </form>
      </div>

      {!data && !error ? <Loading text="Đang tải đánh giá…" /> : data?.items.length ? (
        <div className="order-section-body">
          {data.items.map(rev => (
            <article className="tv5-review-card" key={rev.id}>
              <div className="payment-record">
                <div>
                  <strong>#{rev.id} · {rev.productName} ({rev.rating}/5 ★)</strong>
                  <p className="muted small">Bởi {rev.authorName} · {formatDate(rev.createdAt)}</p>
                </div>
                <span className={`status-pill ${rev.status === 'APPROVED' ? 'active' : 'inactive'}`}>
                  <span />{rev.status}
                </span>
              </div>
              {rev.title && <p><strong>{rev.title}</strong></p>}
              <p>{rev.content}</p>
              <div className="form-field">
                <label htmlFor={`reply-${rev.id}`}>Phản hồi của cửa hàng</label>
                <textarea
                  id={`reply-${rev.id}`}
                  rows={2}
                  value={replies[rev.id] ?? rev.staffReply ?? ''}
                  onChange={e => setReplies(cur => ({ ...cur, [rev.id]: e.target.value }))}
                  disabled={busyId === rev.id}
                />
              </div>
              <div className="tv5-row-actions">
                <button className="button" disabled={busyId === rev.id} onClick={() => moderate(rev, 'APPROVED')}>Duyệt hiển thị</button>
                <button className="button secondary" disabled={busyId === rev.id} onClick={() => moderate(rev, 'HIDDEN')}>Ẩn</button>
                <button className="text-button destructive" disabled={busyId === rev.id} onClick={() => moderate(rev, 'REJECTED')}>Từ chối</button>
              </div>
            </article>
          ))}
        </div>
      ) : !error && <div className="empty-state"><p>Chưa có đánh giá nào khớp bộ lọc.</p></div>}

      <Pager page={data} busy={!data && !error} onChange={setPage} />
    </section>
  </>;
}
