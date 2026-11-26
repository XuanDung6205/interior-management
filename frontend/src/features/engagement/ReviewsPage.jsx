import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { ErrorNotice, Loading, ProductImage } from '../../components/ui';
import { formatDate } from '../../lib/format';
import { useCommerce } from '../../lib/session';
import { engagementApi } from './engagementApi';

const STATUS_LABELS = {
  PENDING: 'Chờ duyệt',
  APPROVED: 'Đã hiển thị',
  REJECTED: 'Từ chối',
  HIDDEN: 'Đã ẩn',
};

// Trang đánh giá sản phẩm của khách: liệt kê sản phẩm đã giao chưa đánh giá và các đánh giá đã gửi (route /reviews)
export default function ReviewsPage() {
  const { api } = useCommerce();
  const client = useMemo(() => engagementApi(api), [api]);
  const [eligible, setEligible] = useState(null);
  const [mine, setMine] = useState(null);
  const [selected, setSelected] = useState(null);
  const [rating, setRating] = useState('5');
  const [title, setTitle] = useState('');
  const [content, setContent] = useState('');
  const [imageText, setImageText] = useState(''); // Mỗi dòng 1 URL ảnh đính kèm
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const [fields, setFields] = useState({});
  const [notice, setNotice] = useState('');
  const [revision, setRevision] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    setError(null);
    Promise.all([
      client.eligibleReviews(controller.signal),
      client.myReviews(controller.signal),
    ]).then(([e, m]) => {
      setEligible(e);
      setMine(m);
    }).catch(err => {
      if (err.name !== 'AbortError') setError(err);
    });
    return () => controller.abort();
  }, [client, revision]);

  async function submitReview(event) {
    event.preventDefault();
    if (!selected || busy) return;
    setBusy(true);
    setError(null);
    setFields({});
    setNotice('');
    try {
      const imageUrls = imageText
        .split('\n')
        .map(line => line.trim())
        .filter(Boolean);
      await client.createReview({
        orderItemId: selected.orderItemId,
        rating: Number(rating),
        title,
        content,
        imageUrls,
      });
      setSelected(null);
      setRating('5');
      setTitle('');
      setContent('');
      setImageText('');
      setNotice('Cảm ơn bạn đã gửi đánh giá. Cửa hàng sẽ duyệt và hiển thị sớm.');
      setRevision(v => v + 1);
    } catch (err) {
      setError(err);
      setFields(err.fieldErrors || {});
    } finally {
      setBusy(false);
    }
  }

  return <>
    <div className="breadcrumb"><Link to="/orders">Đơn hàng</Link><span>/</span><span>Đánh giá của tôi</span></div>
    <div className="page-heading">
      <div>
        <span className="eyebrow">CHIA SẺ TRẢI NGHIỆM</span>
        <h1>Đánh giá sản phẩm<span className="heading-dot">.</span></h1>
        <p>Chỉ những sản phẩm từ đơn đã giao thành công mới được gửi đánh giá xác thực.</p>
      </div>
    </div>

    <ErrorNotice error={error} onRetry={() => setRevision(v => v + 1)} />
    {notice && <div className="notice success" role="status">{notice}</div>}

    {selected && (
      <section className="panel coupon-editor">
        <div className="panel-heading">
          <h2>Đánh giá: {selected.productName} ({selected.variantName})</h2>
          <button className="text-button" onClick={() => setSelected(null)}>Đóng</button>
        </div>
        <form onSubmit={submitReview} className="tv5-form-pad">
          <div className="form-grid">
            <div className="form-field">
              <label htmlFor="rev-rating">Số sao</label>
              <select id="rev-rating" value={rating} onChange={e => setRating(e.target.value)} disabled={busy}>
                <option value="5">5 sao — Rất hài lòng</option>
                <option value="4">4 sao — Hài lòng</option>
                <option value="3">3 sao — Bình thường</option>
                <option value="2">2 sao — Chưa ưng ý</option>
                <option value="1">1 sao — Không hài lòng</option>
              </select>
              {fields.rating && <span className="field-error">{fields.rating}</span>}
            </div>
            <div className="form-field">
              <label htmlFor="rev-title">Tiêu đề ngắn</label>
              <input id="rev-title" value={title} onChange={e => setTitle(e.target.value)} disabled={busy} placeholder="VD: Gỗ chắc chắn, màu rất ấm" />
            </div>
            <div className="form-field wide">
              <label htmlFor="rev-content">Cảm nhận của bạn</label>
              <textarea id="rev-content" rows={4} required value={content} onChange={e => setContent(e.target.value)} disabled={busy} />
              {fields.content && <span className="field-error">{fields.content}</span>}
            </div>
            <div className="form-field wide">
              <label htmlFor="rev-images">Link ảnh thực tế (tối đa 5 link, mỗi dòng 1 URL)</label>
              <textarea id="rev-images" rows={2} value={imageText} onChange={e => setImageText(e.target.value)} disabled={busy} placeholder="/images/sofa-modena.jpg" />
            </div>
          </div>
          <div className="editor-actions">
            <button type="button" className="button secondary" onClick={() => setSelected(null)} disabled={busy}>Hủy</button>
            <button className="button" disabled={busy}>{busy ? 'Đang gửi…' : 'Gửi đánh giá'}</button>
          </div>
        </form>
      </section>
    )}

    {(!eligible || !mine) && !error ? <Loading text="Đang tải danh sách đánh giá…" /> : (
      <div className="aftercare-stack">
        <section className="panel">
          <div className="panel-heading">
            <h2>Chờ bạn đánh giá <span className="count-badge">{eligible?.length ?? 0}</span></h2>
          </div>
          <div className="order-section-body">
            {eligible?.length ? eligible.map(item => (
              <div className="payment-record" key={item.orderItemId}>
                <div className="tv5-inline-item">
                  <div className="tv5-thumb"><ProductImage src={item.imageUrl} name={item.productName} /></div>
                  <div>
                    <strong>{item.productName}</strong>
                    <p className="muted small">{item.variantName} · Đơn {item.orderNumber}</p>
                  </div>
                </div>
                <button className="button secondary" onClick={() => setSelected(item)}>Viết đánh giá</button>
              </div>
            )) : <p className="muted">Hiện chưa có sản phẩm nào chờ đánh giá.</p>}
          </div>
        </section>

        <section className="panel">
          <div className="panel-heading">
            <h2>Đánh giá đã gửi <span className="count-badge">{mine?.length ?? 0}</span></h2>
          </div>
          <div className="order-section-body">
            {mine?.length ? mine.map(rev => (
              <article className="tv5-review-card" key={rev.id}>
                <div className="payment-record">
                  <div>
                    <strong>{rev.productName} · {'★'.repeat(rev.rating)}{'☆'.repeat(5 - rev.rating)}</strong>
                    {rev.title && <p><strong>{rev.title}</strong></p>}
                  </div>
                  <span className={`status-pill ${rev.status === 'APPROVED' ? 'active' : 'inactive'}`}>
                    <span />{STATUS_LABELS[rev.status] || rev.status}
                  </span>
                </div>
                <p>{rev.content}</p>
                <p className="muted small">Gửi lúc {formatDate(rev.createdAt)}</p>
                {rev.staffReply && (
                  <div className="notice">
                    <strong>Phản hồi từ Nếp Nhà ({rev.repliedByName || 'Cửa hàng'}):</strong>
                    <p>{rev.staffReply}</p>
                  </div>
                )}
              </article>
            )) : <p className="muted">Bạn chưa gửi đánh giá nào.</p>}
          </div>
        </section>
      </div>
    )}
  </>;
}
