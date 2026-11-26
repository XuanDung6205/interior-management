import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { Pager } from '../../components/ui';
import { formatDate } from '../../lib/format';
import { useCommerce } from '../../lib/session';
import { engagementApi } from './engagementApi';

// Khối hiển thị các đánh giá đã duyệt của khách mua thật trên trang chi tiết sp
export default function ProductReviewsSection({ productId }) {
  const { api } = useCommerce();
  const client = useMemo(() => engagementApi(api), [api]);
  const [data, setData] = useState(null);
  const [page, setPage] = useState(0);

  useEffect(() => {
    if (!productId) return;
    const controller = new AbortController();
    // Lỗi mạng thì bỏ qua nhẹ nhàng, ko làm sập trang xem sản phẩm
    client.productReviews(productId, page, controller.signal)
      .then(value => { if (!controller.signal.aborted) setData(value); })
      .catch(() => {});
    return () => controller.abort();
  }, [client, productId, page]);

  if (!data) return null;

  return (
    <section className="panel order-section-body" aria-label="Đánh giá từ khách đã mua" style={{ marginTop: '28px' }}>
      <div className="panel-heading" style={{ padding: '0 0 14px', marginBottom: '16px' }}>
        <div>
          <span className="eyebrow">CẢM NHẬN THỰC TẾ</span>
          <h2>
            Đánh giá sản phẩm
            <span className="count-badge">{data.totalReviews}</span>
          </h2>
          {data.totalReviews > 0 && (
            <p className="muted small">Điểm trung bình: <strong>{data.averageRating}/5 ★</strong></p>
          )}
        </div>
        <Link to="/reviews" className="button secondary">Viết đánh giá của bạn</Link>
      </div>

      {data.items.length ? (
        <div>
          {data.items.map(rev => (
            <article className="tv5-review-card" key={rev.id}>
              <div className="payment-record">
                <div>
                  <strong>{rev.authorName}</strong> · {'★'.repeat(rev.rating)}{'☆'.repeat(5 - rev.rating)}
                  {rev.title && <p><strong>{rev.title}</strong></p>}
                </div>
                <span className="muted small">{formatDate(rev.createdAt)}</span>
              </div>
              <p>{rev.content}</p>
              {/* Ảnh thực tế khách đính kèm khi đánh giá */}
              {rev.images?.length > 0 && (
                <div className="tv5-review-photos">
                  {rev.images.map(img => (
                    <img key={img.id} src={img.url} alt={img.altText || `Ảnh đánh giá ${rev.productName}`} loading="lazy" />
                  ))}
                </div>
              )}
              {rev.staffReply && (
                <div className="notice">
                  <strong>Phản hồi từ Nếp Nhà ({rev.repliedByName || 'Cửa hàng'}):</strong>
                  <p>{rev.staffReply}</p>
                </div>
              )}
            </article>
          ))}
        </div>
      ) : (
        <p className="muted">Chưa có đánh giá nào được duyệt cho sản phẩm này. Khách đã nhận hàng có thể gửi đánh giá đầu tiên nhé.</p>
      )}

      <Pager page={data} busy={false} onChange={setPage} />
    </section>
  );
}
