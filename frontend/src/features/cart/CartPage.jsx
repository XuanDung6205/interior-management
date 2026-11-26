import { useEffect, useMemo, useState } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { useCommerce } from '../../lib/session';
import { formatMoney } from '../../lib/format';
import { ErrorNotice, Icon, Loading, Pager, ProductImage } from '../../components/ui';
import { cartApi } from './cartApi';

const issueLabels = { OUT_OF_STOCK: 'Tạm hết hàng', INSUFFICIENT_STOCK: 'Số lượng vượt tồn kho', UNAVAILABLE_VARIANT: 'Sản phẩm ngừng bán' };

function CartLine({ item, busy, onUpdate, onRemove }) {
  const [quantity, setQuantity] = useState(String(item.quantity));
  const [error, setError] = useState('');
  useEffect(() => { setQuantity(String(item.quantity)); setError(''); }, [item.quantity]);
  const canIncrease = item.active && item.quantity < item.availableQuantity && item.quantity < 1000000;
  async function submit(event) {
    event.preventDefault();
    const value = Number(quantity);
    if (!/^\d+$/.test(quantity) || !Number.isSafeInteger(value) || value < 1 || value > 1000000) { setError('Nhập số lượng từ 1 đến 1.000.000.'); return; }
    if (value > item.quantity && (!item.active || value > item.availableQuantity)) { setError('Số lượng tăng thêm vượt hàng hiện có.'); return; }
    setError('');
    await onUpdate(item.id, value);
  }
  return <article className="cart-line">
    <ProductImage src={item.imageUrl} name={`${item.productName} — ${item.variantName}`} />
    <div className="line-details"><span className="sku">{item.sku}</span><h2>{item.productName}</h2><p className="muted">{item.variantName}</p><p className="unit-price">{formatMoney(item.unitPrice)} <span>/ sản phẩm</span></p><div className="line-stock">{item.active ? `Có sẵn: ${item.availableQuantity}` : 'Sản phẩm ngừng bán'}</div>{item.issues?.length > 0 && <ul className="line-issues">{item.issues.map(issue => <li key={issue}>{issueLabels[issue] || issue}</li>)}</ul>}</div>
    <div className="line-controls"><strong className="line-total">{formatMoney(item.lineTotal)}</strong><form className="quantity-form" onSubmit={submit}><div className="quantity-stepper"><button type="button" aria-label={`Giảm số lượng ${item.productName}`} disabled={busy || item.quantity <= 1} onClick={() => onUpdate(item.id, item.quantity - 1)}><Icon name="minus" size={16} /></button><input aria-label={`Số lượng ${item.productName}`} inputMode="numeric" value={quantity} onChange={event => setQuantity(event.target.value)} disabled={busy} aria-describedby={error ? `quantity-error-${item.id}` : undefined} /><button type="button" aria-label={`Tăng số lượng ${item.productName}`} disabled={busy || !canIncrease} onClick={() => onUpdate(item.id, item.quantity + 1)}><Icon name="plus" size={16} /></button></div>{quantity !== String(item.quantity) && <button className="text-button" disabled={busy}>Cập nhật</button>}</form>{error && <span className="field-error" id={`quantity-error-${item.id}`} role="alert">{error}</span>}<button className="text-button remove-button" disabled={busy} onClick={() => onRemove(item.id)} aria-label={`Xóa ${item.productName}`}><Icon name="trash" size={16} />Xóa</button></div>
  </article>;
}

export default function CartPage() {
  const location = useLocation();
  const requestedQuery = new URLSearchParams(location.search).get('query') ?? '';
  const { api, session } = useCommerce();
  const client = useMemo(() => cartApi(api), [api]);
  const [cart, setCart] = useState(null);
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [clearConfirm, setClearConfirm] = useState(false);
  const [notice, setNotice] = useState('');
  const [catalog, setCatalog] = useState(null);
  const [catalogError, setCatalogError] = useState(null);
  const [catalogLoading, setCatalogLoading] = useState(true);
  const [search, setSearch] = useState(requestedQuery);
  const [query, setQuery] = useState(requestedQuery);
  const [page, setPage] = useState(0);
  const [revision, setRevision] = useState(0);
  useEffect(() => { setSearch(requestedQuery); setQuery(requestedQuery); setPage(0); }, [requestedQuery]);
  useEffect(() => {
    const controller = new AbortController();
    setLoading(true); setError(null);
    client.get({ signal: controller.signal }).then(setCart).catch(value => { if (value.name !== 'AbortError') setError(value); }).finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [client, revision]);
  useEffect(() => {
    const controller = new AbortController();
    setCatalogLoading(true); setCatalogError(null);
    client.catalog(query, page, controller.signal).then(setCatalog).catch(value => { if (value.name !== 'AbortError') setCatalogError(value); }).finally(() => { if (!controller.signal.aborted) setCatalogLoading(false); });
    return () => controller.abort();
  }, [client, query, page, revision]);
  async function mutate(action, message) {
    if (busy) return;
    setBusy(true); setError(null); setNotice('');
    try { setCart(await action()); setNotice(message); setClearConfirm(false); }
    catch (value) { setError(value); }
    finally { setBusy(false); }
  }
  return <>
    <div className="breadcrumb"><span>Nếp Nhà</span><span>/</span><span>Giỏ hàng</span></div>
    <div className="page-heading"><div><span className="eyebrow">NHỮNG LỰA CHỌN CHO TỔ ẤM</span><h1>Giỏ hàng của bạn<span className="heading-dot">.</span></h1><p>Mỗi món đồ, một chút gần hơn với không gian bạn yêu.</p></div><a className="inline-link" href="#catalog">Tiếp tục khám phá<Icon name="arrow" size={18} /></a></div>
    <ErrorNotice error={error} onRetry={() => setRevision(value => value + 1)} />
    <div className="sr-only" role="status">{notice}</div>
    {loading ? <Loading text="Đang tải giỏ hàng…" /> : cart && <div className="cart-layout">
      <section className="panel cart-panel" aria-label="Sản phẩm trong giỏ"><div className="panel-heading"><h2>Sản phẩm <span className="count-badge">{cart.itemCount}</span></h2>{cart.items.length > 0 && <button className="text-button" disabled={busy} onClick={() => setClearConfirm(value => !value)}>Xóa tất cả</button>}</div>
        {clearConfirm && <div className="notice clear-confirm"><span>Bỏ tất cả sản phẩm khỏi giỏ?</span><button className="text-button" disabled={busy} onClick={() => mutate(client.clear, 'Đã xóa tất cả sản phẩm.')}>Đồng ý xóa hết</button><button className="text-button" onClick={() => setClearConfirm(false)} disabled={busy}>Giữ lại</button></div>}
        {cart.items.length ? cart.items.map(item => <CartLine key={item.id} item={item} busy={busy} onUpdate={(id, quantity) => mutate(() => client.update(id, quantity), 'Đã cập nhật số lượng.')} onRemove={id => mutate(() => client.remove(id), 'Đã xóa sản phẩm.')} />) : <div className="empty-state"><div className="empty-icon"><Icon name="bag" size={34} /></div><h2>Giỏ hàng đang chờ bạn</h2><p>Khám phá những món nội thất phù hợp với tổ ấm của bạn.</p><a href="#catalog" className="button">Khám phá sản phẩm<Icon name="arrow" size={18} /></a></div>}
        <div className="cart-note"><Icon name="shield" size={18} /><span>Giỏ hàng được lưu lại. Tồn kho chưa được giữ trước.</span></div>
      </section>
      <aside className="summary-column"><section className="panel summary-panel"><span className="eyebrow">LỰA CHỌN CỦA BẠN</span><h2>Tóm tắt giỏ hàng</h2><dl><div><dt>Số lượng sản phẩm</dt><dd>{cart.itemCount}</dd></div><div className="summary-total"><dt>Tạm tính</dt><dd data-testid="cart-subtotal">{formatMoney(cart.subtotal)}</dd></div></dl><p className="muted small">Tiền hàng hiện tại. Chưa bao gồm phí giao hàng, lắp đặt và ưu đãi.</p>{cart.items.length > 0 && <Link to="/checkout" className="button full cart-checkout-button">Tiếp tục đặt hàng<Icon name="arrow" size={18} /></Link>}</section>
        {!session.authenticated && <div className="guest-card"><Icon name="user" size={25} /><h3>Mang giỏ hàng theo bạn</h3><p>Đăng nhập để gộp giỏ khách vào tài khoản của bạn.</p><Link to="/login" className="inline-link">Đăng nhập<Icon name="arrow" size={18} /></Link></div>}
      </aside>
    </div>}
    {!!cart?.warnings?.length && <section className="notice warning" aria-label="Cảnh báo giỏ hàng"><h2>Kiểm tra lại sản phẩm</h2><ul>{cart.warnings.map((warning, index) => <li key={`${warning.code}-${warning.itemId}-${index}`}>{warning.message}</li>)}</ul></section>}
    <section id="catalog" className="catalog-section"><div className="section-heading"><div><span className="eyebrow">THÊM MỘT CHÚT CẢM HỨNG</span><h2>Tìm món đồ dành cho bạn</h2></div><form className="search-form" onSubmit={event => { event.preventDefault(); setPage(0); setQuery(search.trim()); }}><label className="sr-only" htmlFor="catalog-search">Tìm sản phẩm</label><Icon name="search" /><input id="catalog-search" type="search" value={search} onChange={event => setSearch(event.target.value)} placeholder="Tên sản phẩm hoặc mã SKU" /><button className="button" disabled={catalogLoading}>Tìm kiếm</button></form></div>
      <ErrorNotice error={catalogError} onRetry={() => setRevision(value => value + 1)} />
      {catalogLoading ? <Loading text="Đang tìm sản phẩm…" /> : catalog && <><div className="product-grid">{catalog.items.map(item => <article className="product-card" key={item.id}><ProductImage src={item.imageUrl} name={`${item.productName} — ${item.variantName}`} /><div className="product-card-content"><span className="sku">{item.sku}</span><h3>{item.productName}</h3><p className="muted">{item.variantName}</p><strong>{formatMoney(item.price)}</strong><div className="product-card-bottom"><span>{!item.active ? 'Ngừng bán' : item.availableQuantity > 0 ? `Có sẵn: ${item.availableQuantity}` : 'Tạm hết hàng'}</span><button className="add-button" aria-label={`Thêm ${item.productName} vào giỏ`} disabled={busy || !item.active || item.availableQuantity < 1 || !cart} onClick={() => mutate(() => client.add(item.id), 'Đã thêm sản phẩm vào giỏ.')}><Icon name="plus" /></button></div></div></article>)}</div>{!catalog.items.length && <div className="empty-state"><Icon name="search" size={32} /><h3>Chưa tìm thấy sản phẩm</h3><p>Thử tên khác hoặc tìm theo mã SKU.</p></div>}<Pager page={catalog} busy={catalogLoading} onChange={setPage} /></>}
    </section>
  </>;
}
