import { useEffect, useMemo, useState } from 'react';
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom';
import { ErrorNotice, Icon, Loading, ProductImage } from '../../components/ui';
import { formatMoney } from '../../lib/format';
import { useCommerce } from '../../lib/session';
import { trackEvent } from '../../lib/tracking';
import { cartApi } from '../cart/cartApi';
import { engagementApi } from '../engagement/engagementApi';
import ProductReviewsSection from '../engagement/ProductReviewsSection';
import RecommendationStrip from '../ai/RecommendationStrip';
import './catalog.css';

export default function ProductDetailPage() {
  const { slug } = useParams();
  const { api, session } = useCommerce();
  const location = useLocation();
  const navigate = useNavigate();
  const engagement = useMemo(() => engagementApi(api), [api]);
  const [product, setProduct] = useState(null);
  const [selectedId, setSelectedId] = useState(null);
  const [imageIndex, setImageIndex] = useState(0);
  const [quantity, setQuantity] = useState(1);
  const [error, setError] = useState(null);
  const [notice, setNotice] = useState(null); // { text, linkTo?, linkLabel? } để giỏ hàng dẫn sang /cart, còn yêu thích dẫn sang /wishlist
  const [busy, setBusy] = useState(false);
  const [saved, setSaved] = useState(false); // Check xem sp này đã nằm trong mục yêu thích chưa
  const [wishlistBusy, setWishlistBusy] = useState(false);
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    setProduct(null); setError(null); setImageIndex(0); setNotice(null);
    api.request(`/catalog/products/${encodeURIComponent(slug)}`, { signal: controller.signal })
      .then(value => {
        setProduct(value);
        setSelectedId(value.variants[0]?.id ?? null);
        // Bắn tracking lượt xem sp để hệ thống gợi ý và funnel dashboard có dữ liệu thật
        trackEvent(api, { eventType: 'PRODUCT_VIEW', productId: value.id, pagePath: `/products/${value.slug}` });
      })
      .catch(value => { if (value.name !== 'AbortError') setError(value); });
    return () => controller.abort();
  }, [api, slug, revision]);

  // Nếu đã đăng nhập thì check xem sp hiện tại có trong danh sách yêu thích ko
  useEffect(() => {
    if (!session?.authenticated || !product?.id) { setSaved(false); return; }
    const controller = new AbortController();
    engagement.wishlist(controller.signal)
      .then(list => { if (!controller.signal.aborted) setSaved((list || []).some(item => item.productId === product.id)); })
      .catch(() => { });
    return () => controller.abort();
  }, [engagement, session?.authenticated, product?.id]);

  const selected = product?.variants.find(variant => variant.id === selectedId) || product?.variants[0];

  async function addToCart() {
    if (!selected || busy || selected.availableQuantity < 1) return;
    setBusy(true); setError(null); setNotice(null);
    trackEvent(api, { eventType: 'ADD_TO_CART_CLICK', productId: product.id, variantId: selected.id, quantity, pagePath: `/products/${product.slug}` });
    try {
      await cartApi(api).add(selected.id, quantity);
      setNotice({ text: `Đã thêm ${quantity} ${product.name} vào giỏ hàng.`, linkTo: '/cart', linkLabel: 'Xem giỏ hàng' });
    }
    catch (value) { setError(value); }
    finally { setBusy(false); }
  }

  // Bấm lưu hoặc bỏ yêu thích ngay tại trang chi tiết sp (khách chưa đăng nhập thì dẫn sang /login)
  async function toggleWishlist() {
    if (!product || wishlistBusy) return;
    if (!session?.authenticated) {
      navigate('/login', { state: { returnTo: location.pathname + location.search } });
      return;
    }
    setWishlistBusy(true); setError(null); setNotice(null);
    try {
      if (saved) {
        await engagement.removeWishlist(product.id);
        setSaved(false);
        setNotice({ text: `Đã bỏ ${product.name} khỏi danh sách yêu thích.` });
      } else {
        await engagement.addWishlist(product.id);
        setSaved(true);
        setNotice({ text: `Đã lưu ${product.name} vào danh sách yêu thích.`, linkTo: '/wishlist', linkLabel: 'Xem yêu thích' });
      }
    } catch (value) {
      setError(value);
    } finally {
      setWishlistBusy(false);
    }
  }

  return <div className="detail-page">
    <div className="breadcrumb"><Link to="/">Nếp Nhà</Link><span>/</span><Link to="/sale">Sản phẩm</Link>{product && <><span>/</span><Link to={`/sale?category=${encodeURIComponent(product.categorySlug)}`}>{product.categoryName}</Link><span>/</span><span>{product.name}</span></>}</div>
    <ErrorNotice error={error} onRetry={() => setRevision(value => value + 1)} />
    {!product && !error && <Loading text="Đang tải chi tiết sản phẩm…" />}
    {error?.status === 404 && <div className="empty-state"><h1>Không tìm thấy sản phẩm</h1><Link className="button" to="/sale">Xem tất cả sản phẩm</Link></div>}
    {product && <><div className="detail-layout"><div className="detail-gallery"><div className="detail-primary-image"><ProductImage src={product.images[imageIndex]?.url} name={product.images[imageIndex]?.altText || `Ảnh minh họa ${product.name}`} /></div>{product.images.length > 1 && <div className="detail-thumbs">{product.images.map((image, index) => <button type="button" key={`${image.url}-${index}`} aria-label={`Xem ảnh ${index + 1}`} aria-pressed={imageIndex === index} onClick={() => setImageIndex(index)}><img src={image.url} alt="" loading="lazy" /></button>)}</div>}<span className="detail-image-note">Ảnh minh họa sản phẩm demo</span></div>
      <div className="detail-info"><span className="eyebrow">{product.categoryName.toUpperCase()}</span><h1>{product.name}</h1><p className="detail-intro">{product.shortDescription}</p><div className="detail-prices"><strong>{formatMoney(selected?.price)}</strong>{selected?.compareAtPrice && <del>{formatMoney(selected.compareAtPrice)}</del>}</div><p className="detail-stock">{selected?.availableQuantity > 0 ? `Còn ${selected.availableQuantity} sản phẩm` : 'Tạm hết hàng'}</p>
        {product.variants.length > 1 && <div className="detail-options"><span>Phiên bản</span><div>{product.variants.map(variant => <button type="button" key={variant.id} className={selected?.id === variant.id ? 'selected' : ''} onClick={() => { setSelectedId(variant.id); setQuantity(1); }}>{variant.name}</button>)}</div></div>}
        <div className="detail-purchase"><label>Số lượng<input type="number" min="1" max={Math.max(1, Math.min(100, selected?.availableQuantity || 1))} value={quantity} onChange={event => setQuantity(Math.max(1, Math.min(100, selected?.availableQuantity || 1, Number(event.target.value) || 1)))} /></label><button className="button" type="button" disabled={busy || !selected?.availableQuantity} onClick={addToCart}><Icon name="cart" size={19} />{busy ? 'Đang thêm…' : 'Thêm vào giỏ'}</button><button className="button secondary" type="button" disabled={wishlistBusy} aria-pressed={saved} onClick={toggleWishlist}>{saved ? '♥ Đã yêu thích' : '♡ Yêu thích'}</button></div><div className="sr-only" role="status">{notice?.text || ''}</div>{notice && <p className="detail-notice">{notice.text}{notice.linkTo && <> <Link to={notice.linkTo}>{notice.linkLabel}</Link></>}</p>}
        <dl className="detail-facts"><div><dt>Mã SKU</dt><dd>{selected?.sku}</dd></div><div><dt>Kích thước</dt><dd>{[selected?.widthMm, selected?.depthMm, selected?.heightMm].filter(Boolean).join(' × ')} mm</dd></div><div><dt>Chất liệu</dt><dd>{selected?.materials.join(', ') || 'Đang cập nhật'}</dd></div><div><dt>Bảo hành</dt><dd>{product.warrantyMonths} tháng</dd></div><div><dt>Lắp đặt</dt><dd>{product.assemblyRequired ? 'Cần lắp đặt' : 'Không cần lắp đặt'}</dd></div>{selected?.leadTimeDays > 0 && <div><dt>Dự kiến chuẩn bị</dt><dd>{selected.leadTimeDays} ngày</dd></div>}</dl>
      </div></div><div className="detail-sections"><section><span className="eyebrow">THÔNG TIN SẢN PHẨM</span><h2>Một món đồ cho không gian của bạn</h2><p>{product.description || product.shortDescription}</p>{product.careInstructions && <><h3>Chăm sóc sản phẩm</h3><p>{product.careInstructions}</p></>}</section><section><span className="eyebrow">THÔNG SỐ</span><h2>Chi tiết kỹ thuật</h2><dl>{product.specifications.map(spec => <div key={spec.name}><dt>{spec.name}</dt><dd>{spec.value} {spec.unit || ''}</dd></div>)}</dl></section></div>
      <ProductReviewsSection productId={product.id} />
      <RecommendationStrip productId={product.id} heading="Gợi ý phối cùng không gian" limit={4} />
      <div className="detail-back"><Link to="/sale" className="inline-link">Xem thêm sản phẩm <Icon name="arrow" size={18} /></Link></div></>}
  </div>;
}
