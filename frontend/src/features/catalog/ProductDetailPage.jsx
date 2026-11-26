import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { ErrorNotice, Icon, Loading, ProductImage } from '../../components/ui';
import { formatMoney } from '../../lib/format';
import { useCommerce } from '../../lib/session';
import { cartApi } from '../cart/cartApi';
import './catalog.css';

export default function ProductDetailPage() {
  const { slug } = useParams();
  const { api } = useCommerce();
  const [product, setProduct] = useState(null);
  const [selectedId, setSelectedId] = useState(null);
  const [imageIndex, setImageIndex] = useState(0);
  const [quantity, setQuantity] = useState(1);
  const [error, setError] = useState(null);
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    setProduct(null); setError(null); setImageIndex(0); setNotice('');
    api.request(`/catalog/products/${encodeURIComponent(slug)}`, { signal: controller.signal })
      .then(value => { setProduct(value); setSelectedId(value.variants[0]?.id ?? null); })
      .catch(value => { if (value.name !== 'AbortError') setError(value); });
    return () => controller.abort();
  }, [api, slug, revision]);
  const selected = product?.variants.find(variant => variant.id === selectedId) || product?.variants[0];

  async function addToCart() {
    if (!selected || busy || selected.availableQuantity < 1) return;
    setBusy(true); setError(null); setNotice('');
    try { await cartApi(api).add(selected.id, quantity); setNotice(`Đã thêm ${quantity} ${product.name} vào giỏ hàng.`); }
    catch (value) { setError(value); }
    finally { setBusy(false); }
  }

  return <div className="detail-page">
    <div className="breadcrumb"><Link to="/">Nếp Nhà</Link><span>/</span><Link to="/sale">Sản phẩm</Link>{product && <><span>/</span><Link to={`/sale?category=${encodeURIComponent(product.categorySlug)}`}>{product.categoryName}</Link><span>/</span><span>{product.name}</span></>}</div>
    <ErrorNotice error={error} onRetry={() => setRevision(value => value + 1)} />
    {!product && !error && <Loading text="Đang tải chi tiết sản phẩm…" />}
    {error?.status === 404 && <div className="empty-state"><h1>Không tìm thấy sản phẩm</h1><Link className="button" to="/sale">Xem tất cả sản phẩm</Link></div>}
    {product && <><div className="detail-layout"><div className="detail-gallery"><div className="detail-primary-image"><ProductImage src={product.images[imageIndex]?.url} name={product.images[imageIndex]?.altText || `Ảnh minh họa ${product.name}`} /></div>{product.images.length > 1 && <div className="detail-thumbs">{product.images.map((image, index) => <button type="button" key={`${image.url}-${index}`} aria-label={`Xem ảnh ${index + 1}`} aria-pressed={imageIndex === index} onClick={() => setImageIndex(index)}><img src={image.url} alt="" loading="lazy" /></button>)}</div>}<span className="detail-image-note">Ảnh minh họa sản phẩm demo</span></div>
      <div className="detail-info"><span className="eyebrow">{product.categoryName.toUpperCase()}</span><h1>{product.name}</h1><p className="detail-intro">{product.shortDescription}</p><div className="detail-prices"><strong>{formatMoney(selected?.price)}</strong>{selected?.compareAtPrice && <del>{formatMoney(selected.compareAtPrice)}</del>}</div><p className="detail-stock">{selected?.availableQuantity > 0 ? `Còn ${selected.availableQuantity} sản phẩm` : 'Tạm hết hàng'}</p>
        {product.variants.length > 1 && <div className="detail-options"><span>Phiên bản</span><div>{product.variants.map(variant => <button type="button" key={variant.id} className={selected?.id === variant.id ? 'selected' : ''} onClick={() => { setSelectedId(variant.id); setQuantity(1); }}>{variant.name}</button>)}</div></div>}
        <div className="detail-purchase"><label>Số lượng<input type="number" min="1" max={Math.max(1, Math.min(100, selected?.availableQuantity || 1))} value={quantity} onChange={event => setQuantity(Math.max(1, Math.min(100, selected?.availableQuantity || 1, Number(event.target.value) || 1)))} /></label><button className="button" type="button" disabled={busy || !selected?.availableQuantity} onClick={addToCart}><Icon name="cart" size={19} />{busy ? 'Đang thêm…' : 'Thêm vào giỏ'}</button></div><div className="sr-only" role="status">{notice}</div>{notice && <p className="detail-notice">{notice} <Link to="/cart">Xem giỏ hàng</Link></p>}
        <dl className="detail-facts"><div><dt>Mã SKU</dt><dd>{selected?.sku}</dd></div><div><dt>Kích thước</dt><dd>{[selected?.widthMm, selected?.depthMm, selected?.heightMm].filter(Boolean).join(' × ')} mm</dd></div><div><dt>Chất liệu</dt><dd>{selected?.materials.join(', ') || 'Đang cập nhật'}</dd></div><div><dt>Bảo hành</dt><dd>{product.warrantyMonths} tháng</dd></div><div><dt>Lắp đặt</dt><dd>{product.assemblyRequired ? 'Cần lắp đặt' : 'Không cần lắp đặt'}</dd></div>{selected?.leadTimeDays > 0 && <div><dt>Dự kiến chuẩn bị</dt><dd>{selected.leadTimeDays} ngày</dd></div>}</dl>
      </div></div><div className="detail-sections"><section><span className="eyebrow">THÔNG TIN SẢN PHẨM</span><h2>Một món đồ cho không gian của bạn</h2><p>{product.description || product.shortDescription}</p>{product.careInstructions && <><h3>Chăm sóc sản phẩm</h3><p>{product.careInstructions}</p></>}</section><section><span className="eyebrow">THÔNG SỐ</span><h2>Chi tiết kỹ thuật</h2><dl>{product.specifications.map(spec => <div key={spec.name}><dt>{spec.name}</dt><dd>{spec.value} {spec.unit || ''}</dd></div>)}</dl></section></div><div className="detail-back"><Link to="/sale" className="inline-link">Xem thêm sản phẩm <Icon name="arrow" size={18} /></Link></div></>}
  </div>;
}
