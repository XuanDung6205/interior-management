import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { ErrorNotice, Icon, Loading, ProductImage } from '../../components/ui';
import { formatMoney } from '../../lib/format';
import { useCommerce } from '../../lib/session';
import RecommendationStrip from '../ai/RecommendationStrip';
import { engagementApi } from './engagementApi';

// Trang danh sách sản phẩm yêu thích của khách đang đăng nhập (route /wishlist)
export default function WishlistPage() {
  const { api } = useCommerce();
  const client = useMemo(() => engagementApi(api), [api]);
  const [items, setItems] = useState(null);
  const [error, setError] = useState(null);
  const [notice, setNotice] = useState('');
  const [busyId, setBusyId] = useState(null);
  const [revision, setRevision] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    setError(null);
    client.wishlist(controller.signal)
      .then(setItems)
      .catch(err => { if (err.name !== 'AbortError') setError(err); });
    return () => controller.abort();
  }, [client, revision]);

  async function remove(productId) {
    setBusyId(productId);
    setError(null);
    try {
      await client.removeWishlist(productId);
      setItems(current => (current || []).filter(item => item.productId !== productId));
      setNotice('Đã bỏ sản phẩm khỏi mục yêu thích.');
    } catch (err) {
      setError(err);
    } finally {
      setBusyId(null);
    }
  }

  async function addToCart(item) {
    if (!item.defaultVariantId) return;
    setBusyId(item.productId);
    setError(null);
    try {
      await api.request('/cart/items', {
        method: 'POST',
        body: { variantId: item.defaultVariantId, quantity: 1 },
      });
      setNotice(`Đã thêm ${item.productName} vào giỏ hàng.`);
    } catch (err) {
      setError(err);
    } finally {
      setBusyId(null);
    }
  }

  return <>
    <div className="breadcrumb"><Link to="/">Trang chủ</Link><span>/</span><span>Yêu thích</span></div>
    <div className="page-heading">
      <div>
        <span className="eyebrow">LƯU GIỮ CẢM HỨNG</span>
        <h1>Sản phẩm yêu thích<span className="heading-dot">.</span></h1>
        <p>Những món nội thất bạn đã đánh dấu để cân nhắc cho không gian sống.</p>
      </div>
      <Link to="/sale" className="inline-link">Khám phá thêm <Icon name="arrow" size={16} /></Link>
    </div>

    <ErrorNotice error={error} onRetry={() => setRevision(v => v + 1)} />
    {notice && <div className="notice success" role="status">{notice}</div>}
    {!items && !error && <Loading text="Đang tải danh sách yêu thích…" />}

    {items && (items.length ? (
      <div className="product-grid">
        {items.map(item => (
          <article className="product-card" key={item.productId}>
            <Link to={item.productSlug ? `/products/${item.productSlug}` : '/sale'}>
              <ProductImage src={item.imageUrl} name={item.productName} />
            </Link>
            <div className="product-card-content">
              <span className="sku">{item.categoryName}</span>
              <h3>
                <Link to={item.productSlug ? `/products/${item.productSlug}` : '/sale'}>
                  {item.productName}
                </Link>
              </h3>
              <strong>{item.minPrice ? formatMoney(item.minPrice) : 'Đang cập nhật'}</strong>
              <p className="muted small">
                {!item.active ? 'Ngừng bán' : item.totalAvailable > 0 ? `Còn sẵn: ${item.totalAvailable}` : 'Tạm hết hàng'}
              </p>
              <div className="tv5-row-actions">
                <button
                  className="button secondary"
                  disabled={busyId === item.productId || !item.active || item.totalAvailable < 1 || !item.defaultVariantId}
                  onClick={() => addToCart(item)}
                >Thêm vào giỏ</button>
                <button
                  className="text-button destructive"
                  disabled={busyId === item.productId}
                  onClick={() => remove(item.productId)}
                >Bỏ lưu</button>
              </div>
            </div>
          </article>
        ))}
      </div>
    ) : (
      <div className="empty-state">
        <h3>Danh sách yêu thích đang trống</h3>
        <p>Hãy khám phá cửa hàng và lưu lại những món đồ bạn ưng ý.</p>
        <Link to="/sale" className="button">Xem sản phẩm</Link>
      </div>
    ))}

    <RecommendationStrip heading="Có thể bạn cũng thích" limit={4} />
  </>;
}
