import { useEffect, useRef, useState } from 'react';
import { Link, useSearchParams, useParams, useNavigate } from 'react-router-dom';
import { ErrorNotice, Icon, Loading, Pager, ProductImage } from '../../components/ui';
import { formatMoney } from '../../lib/format';
import { useCommerce } from '../../lib/session';
import { trackEvent } from '../../lib/tracking';
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
  const route = useParams();
  const navigate = useNavigate();
  const [facets, setFacets] = useState({});
  const [facetError, setFacetError] = useState(null);
  const headingRef = useRef(null);
  const scrollRequested = useRef(false);
  const [params, setParams] = useSearchParams();
  const [draft, setDraft] = useState(params.get('query') || '');
  const [categories, setCategories] = useState([]);
  const [catalog, setCatalog] = useState(null);
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(true);
  const [revision, setRevision] = useState(0);
  const query = params.get('query') || '';
  const category = route.categorySlug || params.get('category') || '';
  const collection = route.collectionSlug || params.get('collection') || '';
  const extras = Object.fromEntries(['brand', 'material', 'color', 'sizeLabel', 'minPrice', 'maxPrice', 'minWidth', 'maxWidth', 'minDepth', 'maxDepth', 'minHeight', 'maxHeight'].filter(key => params.get(key)).map(key => [key, params.get(key)]));
  if (collection) extras.collection = collection;
  const extraKey = JSON.stringify(extras);
  const collectionInfo = facets.collections?.find(i => i.slug === collection);
  const categoryInfo = categories.find(i => i.slug === category);

  const room = params.get('room') || '';
  const roomLabel = facets.rooms?.find(i => i.slug === room || i.slug === `demo-${room}`)?.name || roomLabels[room];
  const selectionLabel = collectionInfo?.name || (route.categorySlug ? categoryInfo?.name : '') || roomLabel;
  const sort = params.get('sort') || 'newest';
  const page = Math.max(0, Number.parseInt(params.get('page') || '0', 10) || 0);

  useEffect(() => { setDraft(query); }, [query]);
  useEffect(() => {
    const controller = new AbortController(); setFacetError(null);
    api.request('/catalog/facets', { signal: controller.signal }).then(value => { if (!controller.signal.aborted) setFacets(value); })
      .catch(value => { if (value.name !== 'AbortError') setFacetError(value); });
    return () => controller.abort();
  }, [api, revision]);
  useEffect(() => {
    // Ghi nhận lượt xem trang danh mục / sale cho phễu hành vi
    trackEvent(api, { eventType: 'PAGE_VIEW', pagePath: '/sale' });
  }, [api]);
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
    const path = `/catalog/products?${new URLSearchParams({ query, category, room, sort, page, size: 12, ...JSON.parse(extraKey) })}`;
    const previous = cached(api, path);
    if (previous) setCatalog(previous);
    setLoading(true); setError(null);
    api.request(path, { signal: controller.signal })
      .then(value => {
        if (!controller.signal.aborted) {
          remember(api, path, value, 15_000);
          setCatalog(value);
          // Khi khách có gõ từ khóa tìm kiếm thì lưu sự kiện SEARCH phục vụ cá nhân hóa & dashboard
          if (query) {
            trackEvent(api, { eventType: 'SEARCH', keyword: query, resultCount: value.totalElements ?? 0, pagePath: '/sale' });
          }
        }
      })
      .catch(value => { if (value.name !== 'AbortError') { setError(value); if (!previous) setCatalog(null); } })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [api, query, category, room, sort, page, extraKey, revision]);

  function update(values) {
    const next = new URLSearchParams(params);
    if (route.categorySlug) next.set('category', route.categorySlug);
    if (route.collectionSlug) next.set('collection', route.collectionSlug);
    for (const [key, value] of Object.entries(values)) value ? next.set(key, String(value)) : next.delete(key);
    if (route.categorySlug || route.collectionSlug) navigate(`/sale?${next}`); else setParams(next);
  }
  useEffect(() => {
    if (!loading && catalog?.page === page && scrollRequested.current) {
      scrollRequested.current = false;
      // Wait for the replacement grid: inert/focus changes can cancel an earlier smooth scroll.
      headingRef.current?.focus?.({ preventScroll: true });
      headingRef.current?.scrollIntoView?.({ behavior: 'instant', block: 'start' });
    }
  }, [loading, catalog, page]);
  function changePage(value) {
    scrollRequested.current = true;
    update({ page: value || '' });
  }

  return <div className="sale-page">
    <div className="breadcrumb"><Link to="/">Nếp Nhà</Link><span>/</span>{roomLabel && <><Link to="/sale">Sản phẩm</Link><span>/</span></>}<span>{selectionLabel || 'Sale & sản phẩm'}</span></div>
    <div className="sale-heading" ref={headingRef} tabIndex={-1}><div><span className="eyebrow">KHÁM PHÁ NẾP NHÀ</span><h1>{selectionLabel || 'Sale & tất cả sản phẩm'}<span className="heading-dot">.</span></h1><p>{roomLabel ? `Khám phá các món nội thất cho ${roomLabel.toLowerCase()}. Chọn danh mục để tìm món đồ phù hợp.` : 'Khám phá toàn bộ nội thất hiện có. Những món giảm giá được đánh dấu riêng.'}</p></div><span className="sale-count">{catalog?.totalElements ?? '…'} sản phẩm</span></div>
    {collectionInfo?.description && <p className="collection-description">{collectionInfo.description}</p>}
    {facets.collections?.length > 0 && <nav className="catalog-collections" aria-label="Bộ sưu tập"><span>Bộ sưu tập</span>{facets.collections.map(item => <Link key={item.id} className={collection === item.slug ? 'selected' : ''} to={`/collections/${item.slug}`}>{item.name} ↗</Link>)}</nav>}
    <div className="sale-layout">
      <aside className="sale-filters" aria-label="Lọc theo danh mục"><h2>Danh mục</h2><button type="button" aria-pressed={!category} className={!category ? 'selected' : ''} onClick={() => update({ category: '', page: '' })}>Tất cả</button>{categories.map(item => <button type="button" key={item.id} aria-pressed={category === item.slug} className={category === item.slug ? 'selected' : ''} onClick={() => { trackEvent(api, { eventType: 'FILTER', categoryId: item.id, pagePath: '/sale' }); update({ category: item.slug, page: '' }); }}>{item.name}</button>)}
        <details className="catalog-filter-details" open><summary>Bộ lọc chi tiết</summary><ErrorNotice error={facetError} onRetry={() => setRevision(n => n + 1)} />
          <form key={`${params.toString()}:${facets.rooms?.length}:${facets.materials?.length}`} className="catalog-filter-form" onSubmit={event => {
            event.preventDefault(); const values = Object.fromEntries(new FormData(event.currentTarget));
            trackEvent(api, { eventType: 'FILTER', pagePath: '/sale' }); update({ ...values, page: '' });
          }}>
            {[['room', 'Phòng / không gian', facets.rooms], ['brand', 'Thương hiệu', facets.brands], ['material', 'Chất liệu', facets.materials]].map(([key, label, values]) => <label key={key}>{label}<select name={key} defaultValue={key === 'room' ? (facets.rooms?.find(i => i.slug === room || i.slug === `demo-${room}`)?.slug || '') : (params.get(key) || '')}><option value="">Tất cả</option>{(values || []).map(i => <option key={i.id} value={i.slug}>{i.name}</option>)}</select></label>)}
            {[['color', 'Màu sắc', facets.colors], ['sizeLabel', 'Kích thước', facets.sizes]].map(([key, label, values]) => <label key={key}>{label}<select name={key} defaultValue={params.get(key) || ''}><option value="">Tất cả</option>{(values || []).map(i => <option key={i} value={i}>{i}</option>)}</select></label>)}
            <fieldset><legend>Ngân sách (₫)</legend><label>Từ<input name="minPrice" type="number" min="0" step="0.01" defaultValue={params.get('minPrice') || ''} /></label><label>Đến<input name="maxPrice" type="number" min="0" step="0.01" defaultValue={params.get('maxPrice') || ''} /></label></fieldset>
            <details><summary>Số đo phù hợp không gian</summary>{[['Width', 'Rộng'], ['Depth', 'Sâu'], ['Height', 'Cao']].map(([dimension, label]) => <fieldset key={dimension}><legend>{label} (mm)</legend>{['min', 'max'].map(bound => <label key={bound}>{bound === 'min' ? 'Từ' : 'Đến'}<input name={bound + dimension} aria-label={`${label} ${bound === 'min' ? 'từ' : 'đến'} (mm)`} type="number" min="0" step="0.01" defaultValue={params.get(bound + dimension) || ''} /></label>)}</fieldset>)}</details>
            <button type="submit" className="button">Áp dụng bộ lọc</button><button type="button" onClick={() => navigate('/sale')}>Xóa tất cả bộ lọc</button>
          </form>
        </details>
      </aside>
      <div className="sale-results" aria-busy={loading}><div className="sale-toolbar"><form role="search" onSubmit={event => { event.preventDefault(); update({ query: draft.trim(), page: '' }); }}><label className="sr-only" htmlFor="sale-query">Tìm sản phẩm</label><Icon name="search" size={19} /><input id="sale-query" type="search" value={draft} onChange={event => setDraft(event.target.value)} placeholder="Tìm sản phẩm hoặc SKU" /><button type="submit">Tìm</button></form><label className="sale-sort">Sắp xếp<select value={sort} onChange={event => update({ sort: event.target.value, page: '' })}><option value="newest">Mới nhất</option><option value="price_asc">Giá tăng dần</option><option value="price_desc">Giá giảm dần</option><option value="name">Tên A–Z</option></select></label></div>
        <ErrorNotice error={error} onRetry={() => setRevision(value => value + 1)} />
        {loading && !catalog && <Loading text="Đang tải sản phẩm…" />}
        {loading && catalog && <div className="sale-refresh-status" role="status">Đang cập nhật sản phẩm…</div>}
        {catalog && <div className={loading ? 'sale-list is-updating' : 'sale-list'} inert={loading}>{catalog.items.length ? <div className="sale-grid">{catalog.items.map(item => <Link className="sale-card" to={`/products/${item.slug}?variant=${item.variantId}`} key={item.id}><div className="sale-card-photo"><ProductImage thumbnail src={item.imageUrl} name={`Ảnh minh họa ${item.name}`} />{item.compareAtPrice && <span className="sale-badge">GIẢM GIÁ</span>}</div><div className="sale-card-body"><span className="sale-category">{item.categoryName}</span><h2>{item.name}</h2><p>{item.shortDescription}</p><div className="sale-card-price"><strong>{formatMoney(item.price)}</strong>{item.compareAtPrice && <del>{formatMoney(item.compareAtPrice)}</del>}</div><span className="sale-card-foot">{item.availableQuantity > 0 ? 'Còn hàng' : 'Tạm hết hàng'}<Icon name="arrow" size={18} /></span></div></Link>)}</div> : <div className="empty-state"><h2>Chưa tìm thấy sản phẩm</h2><p>Thử danh mục hoặc tên khác.</p><button className="button secondary" onClick={() => navigate('/sale')}>Xem tất cả</button></div>}<Pager page={catalog} busy={loading} onChange={changePage} /></div>}
      </div>
    </div>
    <p className="sale-disclaimer">Ảnh và thông số của các sản phẩm demo chỉ dùng để minh họa cho project nhóm.</p>
  </div>;
}
