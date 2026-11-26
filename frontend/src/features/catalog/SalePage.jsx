import { useEffect, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { ErrorNotice, Icon, Loading, Pager, ProductImage } from '../../components/ui';
import { formatMoney } from '../../lib/format';
import { useCommerce } from '../../lib/session';
import './catalog.css';

const roomLabels = { 'phong-khach': 'Phòng khách', 'phong-an': 'Phòng ăn', 'phong-ngu': 'Phòng ngủ' };
const catalogCache = new WeakMap();

function cached(api, key) {
  const entry = catalogCache.get(api)?.get(key);
  return entry && entry.expiresAt > Date.now() ? entry.value : null;
}

function remember(api, key, value, duration) {
  if (!catalogCache.has(api)) catalogCache.set(api, new Map());
  const entries = catalogCache.get(api);
  if (entries.size >= 24) entries.delete(entries.keys().next().value);
  entries.set(key, { value, expiresAt: Date.now() + duration });
}

export default function SalePage() {
  const { api } = useCommerce();
  const headingRef = useRef(null);
  const [params, setParams] = useSearchParams();
  const [draft, setDraft] = useState(params.get('query') || '');
  const [categories, setCategories] = useState([]);
  const [catalog, setCatalog] = useState(null);
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(true);
  const [revision, setRevision] = useState(0);
  const query = params.get('query') || '';
  const category = params.get('category') || '';
  const room = params.get('room') || '';
  const roomLabel = roomLabels[room];
  const sort = params.get('sort') || 'newest';
  const page = Math.max(0, Number.parseInt(params.get('page') || '0', 10) || 0);

  useEffect(() => { setDraft(query); }, [query]);
  useEffect(() => {
    const controller = new AbortController();
    const path = `/catalog/categories?${new URLSearchParams({ room })}`;
    const previous = cached(api, path);
    if (previous) setCategories(previous);
    api.request(path, { signal: controller.signal }).then(value => {
      if (!controller.signal.aborted) { remember(api, path, value, 60_000); setCategories(value); }
    })
      .catch(value => { if (value.name !== 'AbortError') setError(value); });
    return () => controller.abort();
  }, [api, room, revision]);
  useEffect(() => {
    const controller = new AbortController();
    const path = `/catalog/products?${new URLSearchParams({ query, category, room, sort, page, size: 12 })}`;
    const previous = cached(api, path);
    if (previous) setCatalog(previous);
    setLoading(true); setError(null);
    api.request(path, { signal: controller.signal })
      .then(value => { if (!controller.signal.aborted) { remember(api, path, value, 15_000); setCatalog(value); } })
      .catch(value => { if (value.name !== 'AbortError') { setError(value); if (!previous) setCatalog(null); } })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [api, query, category, room, sort, page, revision]);

  function update(values) {
    const next = new URLSearchParams(params);
    for (const [key, value] of Object.entries(values)) value ? next.set(key, String(value)) : next.delete(key);
    setParams(next);
  }
  function changePage(value) {
    update({ page: value || '' });
    headingRef.current?.scrollIntoView?.({ behavior: window.matchMedia?.('(prefers-reduced-motion: reduce)')?.matches ? 'auto' : 'smooth', block: 'start' });
  }

  return <div className="sale-page">
    <div className="breadcrumb"><Link to="/">Nếp Nhà</Link><span>/</span>{roomLabel && <><Link to="/sale">Sản phẩm</Link><span>/</span></>}<span>{roomLabel || 'Sale & sản phẩm'}</span></div>
    <div className="sale-heading" ref={headingRef}><div><span className="eyebrow">KHÁM PHÁ NẾP NHÀ</span><h1>{roomLabel || 'Sale & tất cả sản phẩm'}<span className="heading-dot">.</span></h1><p>{roomLabel ? `Khám phá các món nội thất cho ${roomLabel.toLowerCase()}. Chọn danh mục để tìm món đồ phù hợp.` : 'Khám phá toàn bộ nội thất hiện có. Những món giảm giá được đánh dấu riêng.'}</p></div><span className="sale-count">{catalog?.totalElements ?? '…'} sản phẩm</span></div>
    <div className="sale-layout">
      <aside className="sale-filters" aria-label="Lọc theo danh mục"><h2>Danh mục</h2><button type="button" aria-pressed={!category} className={!category ? 'selected' : ''} onClick={() => update({ category: '', page: '' })}>Tất cả</button>{categories.map(item => <button type="button" key={item.id} aria-pressed={category === item.slug} className={category === item.slug ? 'selected' : ''} onClick={() => update({ category: item.slug, page: '' })}>{item.name}</button>)}</aside>
      <div className="sale-results" aria-busy={loading}><div className="sale-toolbar"><form role="search" onSubmit={event => { event.preventDefault(); update({ query: draft.trim(), page: '' }); }}><label className="sr-only" htmlFor="sale-query">Tìm sản phẩm</label><Icon name="search" size={19} /><input id="sale-query" type="search" value={draft} onChange={event => setDraft(event.target.value)} placeholder="Tìm sản phẩm hoặc SKU" /><button type="submit">Tìm</button></form><label className="sale-sort">Sắp xếp<select value={sort} onChange={event => update({ sort: event.target.value, page: '' })}><option value="newest">Mới nhất</option><option value="price_asc">Giá tăng dần</option><option value="price_desc">Giá giảm dần</option><option value="name">Tên A–Z</option></select></label></div>
        <ErrorNotice error={error} onRetry={() => setRevision(value => value + 1)} />
        {loading && !catalog && <Loading text="Đang tải sản phẩm…" />}
        {loading && catalog && <div className="sale-refresh-status" role="status">Đang cập nhật sản phẩm…</div>}
        {catalog && <div className={loading ? 'sale-list is-updating' : 'sale-list'} inert={loading}>{catalog.items.length ? <div className="sale-grid">{catalog.items.map(item => <Link className="sale-card" to={`/products/${item.slug}`} key={item.id}><div className="sale-card-photo"><ProductImage src={item.imageUrl} name={`Ảnh minh họa ${item.name}`} />{item.compareAtPrice && <span className="sale-badge">GIẢM GIÁ</span>}</div><div className="sale-card-body"><span className="sale-category">{item.categoryName}</span><h2>{item.name}</h2><p>{item.shortDescription}</p><div className="sale-card-price"><strong>{formatMoney(item.price)}</strong>{item.compareAtPrice && <del>{formatMoney(item.compareAtPrice)}</del>}</div><span className="sale-card-foot">{item.availableQuantity > 0 ? 'Còn hàng' : 'Tạm hết hàng'}<Icon name="arrow" size={18} /></span></div></Link>)}</div> : <div className="empty-state"><h2>Chưa tìm thấy sản phẩm</h2><p>Thử danh mục hoặc tên khác.</p><button className="button secondary" onClick={() => setParams(room ? { room } : {})}>Xem tất cả</button></div>}<Pager page={catalog} busy={loading} onChange={changePage} /></div>}
      </div>
    </div>
    <p className="sale-disclaimer">Ảnh và thông số của các sản phẩm demo chỉ dùng để minh họa cho project nhóm.</p>
  </div>;
}
