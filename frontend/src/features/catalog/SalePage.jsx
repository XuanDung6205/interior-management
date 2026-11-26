import { useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { ErrorNotice, Icon, Loading, Pager, ProductImage } from '../../components/ui';
import { formatMoney } from '../../lib/format';
import { useCommerce } from '../../lib/session';
import './catalog.css';

export default function SalePage() {
  const { api } = useCommerce();
  const [params, setParams] = useSearchParams();
  const [draft, setDraft] = useState(params.get('query') || '');
  const [categories, setCategories] = useState([]);
  const [catalog, setCatalog] = useState(null);
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(true);
  const [revision, setRevision] = useState(0);
  const query = params.get('query') || '';
  const category = params.get('category') || '';
  const sort = params.get('sort') || 'newest';
  const page = Math.max(0, Number.parseInt(params.get('page') || '0', 10) || 0);

  useEffect(() => { setDraft(query); }, [query]);
  useEffect(() => {
    const controller = new AbortController();
    api.request('/catalog/categories', { signal: controller.signal }).then(setCategories)
      .catch(value => { if (value.name !== 'AbortError') setError(value); });
    return () => controller.abort();
  }, [api, revision]);
  useEffect(() => {
    const controller = new AbortController();
    setLoading(true); setError(null);
    api.request(`/catalog/products?${new URLSearchParams({ query, category, sort, page, size: 12 })}`, { signal: controller.signal })
      .then(setCatalog).catch(value => { if (value.name !== 'AbortError') setError(value); })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [api, query, category, sort, page, revision]);

  function update(values) {
    const next = new URLSearchParams(params);
    for (const [key, value] of Object.entries(values)) value ? next.set(key, String(value)) : next.delete(key);
    setParams(next);
  }

  return <div className="sale-page">
    <div className="breadcrumb"><Link to="/">Nếp Nhà</Link><span>/</span><span>Sale &amp; sản phẩm</span></div>
    <div className="sale-heading"><div><span className="eyebrow">KHÁM PHÁ NẾP NHÀ</span><h1>Sale &amp; tất cả sản phẩm<span className="heading-dot">.</span></h1><p>Khám phá toàn bộ nội thất hiện có. Những món giảm giá được đánh dấu riêng.</p></div><span className="sale-count">{catalog?.totalElements ?? '…'} sản phẩm</span></div>
    <div className="sale-layout">
      <aside className="sale-filters" aria-label="Lọc theo danh mục"><h2>Danh mục</h2><button type="button" className={!category ? 'selected' : ''} onClick={() => update({ category: '', page: '' })}>Tất cả</button>{categories.map(item => <button type="button" key={item.id} className={category === item.slug ? 'selected' : ''} onClick={() => update({ category: item.slug, page: '' })}>{item.name}</button>)}</aside>
      <div className="sale-results"><div className="sale-toolbar"><form role="search" onSubmit={event => { event.preventDefault(); update({ query: draft.trim(), page: '' }); }}><label className="sr-only" htmlFor="sale-query">Tìm sản phẩm</label><Icon name="search" size={19} /><input id="sale-query" type="search" value={draft} onChange={event => setDraft(event.target.value)} placeholder="Tìm sản phẩm hoặc SKU" /><button type="submit">Tìm</button></form><label className="sale-sort">Sắp xếp<select value={sort} onChange={event => update({ sort: event.target.value, page: '' })}><option value="newest">Mới nhất</option><option value="price_asc">Giá tăng dần</option><option value="price_desc">Giá giảm dần</option><option value="name">Tên A–Z</option></select></label></div>
        <ErrorNotice error={error} onRetry={() => setRevision(value => value + 1)} />
        {loading && <Loading text="Đang tải sản phẩm…" />}
        {!loading && catalog && <>{catalog.items.length ? <div className="sale-grid">{catalog.items.map(item => <Link className="sale-card" to={`/products/${item.slug}`} key={item.id}><div className="sale-card-photo"><ProductImage src={item.imageUrl} name={`Ảnh minh họa ${item.name}`} />{item.compareAtPrice && <span className="sale-badge">GIẢM GIÁ</span>}</div><div className="sale-card-body"><span className="sale-category">{item.categoryName}</span><h2>{item.name}</h2><p>{item.shortDescription}</p><div className="sale-card-price"><strong>{formatMoney(item.price)}</strong>{item.compareAtPrice && <del>{formatMoney(item.compareAtPrice)}</del>}</div><span className="sale-card-foot">{item.availableQuantity > 0 ? 'Còn hàng' : 'Tạm hết hàng'}<Icon name="arrow" size={18} /></span></div></Link>)}</div> : <div className="empty-state"><h2>Chưa tìm thấy sản phẩm</h2><p>Thử danh mục hoặc tên khác.</p><button className="button secondary" onClick={() => setParams({})}>Xem tất cả</button></div>}<Pager page={catalog} busy={loading} onChange={value => update({ page: value || '' })} /></>}
      </div>
    </div>
    <p className="sale-disclaimer">Ảnh và thông số của các sản phẩm demo chỉ dùng để minh họa cho project nhóm.</p>
  </div>;
}
