import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { ProductImage } from '../../components/ui';
import { formatMoney } from '../../lib/format';
import { useCommerce } from '../../lib/session';
import { getTrackingSessionId } from '../../lib/tracking';
import { aiApi } from './aiApi';

// Khối sản phẩm gợi ý nội thất theo ngữ cảnh và hành vi phiên hiện tại
export default function RecommendationStrip({ productId = null, limit = 4, heading = 'Gợi ý dành cho tổ ấm của bạn' }) {
  const { api } = useCommerce();
  const client = useMemo(() => aiApi(api), [api]);
  const [items, setItems] = useState([]);

  useEffect(() => {
    const controller = new AbortController();
    client.recommendations({ productId, sessionId: getTrackingSessionId(), limit }, controller.signal)
      .then(res => setItems(res?.items || []))
      .catch(() => {});
    return () => controller.abort();
  }, [client, productId, limit]);

  if (!items.length) return null;

  return (
    <section className="catalog-section" aria-label={heading}>
      <div className="section-heading">
        <div>
          <span className="eyebrow">GỢI Ý NỘI THẤT</span>
          <h2>{heading}</h2>
        </div>
      </div>
      <div className="product-grid">
        {items.map(item => (
          // Bấm vào món gợi ý là mở thẳng trang chi tiết sp theo slug luôn
          <Link to={item.productSlug ? `/products/${item.productSlug}` : '/sale'} className="product-card" key={item.productId}>
            <ProductImage src={item.imageUrl} name={item.productName} />
            <div className="product-card-content">
              <span className="sku">{item.reason}</span>
              <h3>{item.productName}</h3>
              <p className="muted small">{item.categoryName}</p>
              <strong>{item.minPrice ? formatMoney(item.minPrice) : 'Đang cập nhật'}</strong>
            </div>
          </Link>
        ))}
      </div>
    </section>
  );
}
