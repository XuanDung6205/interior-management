import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { useCommerce } from '../../lib/session';
import { ErrorNotice, Loading, Pager } from '../../components/ui';
import './catalog-admin.css';

const kinds = { categories: 'Danh mục', rooms: 'Phòng / không gian', brands: 'Thương hiệu', materials: 'Chất liệu', collections: 'Bộ sưu tập' };
const productStates = { DRAFT: 'Bản nháp', ACTIVE: 'Đang bán', DISCONTINUED: 'Ngừng kinh doanh', ARCHIVED: 'Lưu trữ' };
const slugify = s => s.normalize('NFD').replace(/[\u0300-\u036f]/g, '').replace(/[đĐ]/g, 'd').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');
const variant = () => ({ sku: '', variantKey: `option-${crypto.randomUUID().slice(0, 8)}`, name: '', status: 'ACTIVE', colorName: '', colorHex: '', sizeLabel: '', widthMm: '', depthMm: '', heightMm: '', weightKg: '', price: '', compareAtPrice: '', leadTimeDays: 0, materialIds: [] });
const product = () => ({ name: '', slug: '', status: 'DRAFT', categoryId: '', brandId: '', warrantyMonths: 12, assemblyRequired: false, shortDescription: '', description: '', careInstructions: '', seoTitle: '', seoDescription: '', roomIds: [], collectionIds: [], variants: [variant()], images: [], specifications: [] });
const taxonomy = () => ({ name: '', slug: '', description: '', imageUrl: '', logoUrl: '', countryCode: '', careInstructions: '', sortOrder: 0, parentId: '', active: true });
const money = value => { const text = String(value); if (!/^\d+(?:\.\d{0,2})?$/.test(text)) return text; const [whole, fraction = ''] = text.split('.'); return `${whole}.${fraction.padEnd(2, '0')}`; };
const optionalNumber = n => n === '' || n == null ? null : Number(n);
function Field({ label, children, ...props }) { return <label className="ca-field"><span>{label}</span>{children || <input {...props} />}</label>; }
function Choices({ label, items = [], value = [], onChange }) { return <fieldset className="ca-choices"><legend>{label}</legend>{items.map(i => <label key={i.id}><input type="checkbox" checked={value.includes(i.id)} onChange={() => onChange(value.includes(i.id) ? value.filter(id => id !== i.id) : [...value, i.id])} />{i.name}{!i.active && ' (đã ẩn)'}</label>)}{!items.length && <small>Thêm dữ liệu ở tab Phân loại.</small>}</fieldset>; }

export default function CatalogAdminPage() {
  const { api } = useCommerce();
  const [tab, setTab] = useState('products');
  const [options, setOptions] = useState({});
  const [list, setList] = useState(null);
  const [page, setPage] = useState(0);
  const [query, setQuery] = useState('');
  const [search, setSearch] = useState('');
  const [draft, setDraft] = useState(null);
  const [kind, setKind] = useState('categories');
  const [term, setTerm] = useState(taxonomy);
  const [error, setError] = useState(null);
  const [notice, setNotice] = useState('');
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [revision, setRevision] = useState(0);
  const editor = useRef(null);
  const feedback = useRef(null);
  useEffect(() => {
    if (error || notice) { feedback.current?.focus?.({ preventScroll: true }); feedback.current?.scrollIntoView?.({ behavior: 'instant', block: 'start' }); }
  }, [error, notice]);
  useEffect(() => {
    const controller = new AbortController(); setLoading(true); setError(null);
    Promise.all([api.request('/admin/catalog/options', { signal: controller.signal }), api.request(`/admin/catalog/products?${new URLSearchParams({ query: search, page, size: 12 })}`, { signal: controller.signal })])
      .then(([all, products]) => { if (!controller.signal.aborted) { setOptions(all); setList(products); } })
      .catch(e => { if (e.name !== 'AbortError') setError(e); })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [api, page, search, revision]);
  const patch = (key, value) => setDraft(d => ({ ...d, [key]: value }));
  const row = (key, index, value) => setDraft(d => ({ ...d, [key]: d[key].map((r, i) => i === index ? { ...r, ...value } : r) }));
  const add = (key, value) => setDraft(d => ({ ...d, [key]: [...d[key], value] }));
  const remove = (key, index) => setDraft(d => ({ ...d, [key]: d[key].filter((_, i) => i !== index) }));
  async function open(id) {
    setBusy(true); setError(null); setNotice('');
    try { setDraft(await api.request(`/admin/catalog/products/${id}`)); requestAnimationFrame(() => editor.current?.scrollIntoView?.({ block: 'start' })); }
    catch (e) { setError(e); } finally { setBusy(false); }
  }
  async function save(event) {
    event.preventDefault(); setBusy(true); setError(null); setNotice('');
    const payload = { ...draft, categoryId: Number(draft.categoryId), brandId: optionalNumber(draft.brandId), warrantyMonths: Number(draft.warrantyMonths), variants: draft.variants.map(v => ({ ...v, colorHex: v.colorHex || null, price: money(v.price), compareAtPrice: v.compareAtPrice === '' || v.compareAtPrice == null ? null : money(v.compareAtPrice), widthMm: optionalNumber(v.widthMm), depthMm: optionalNumber(v.depthMm), heightMm: optionalNumber(v.heightMm), weightKg: optionalNumber(v.weightKg), leadTimeDays: Number(v.leadTimeDays) })), images: draft.images.map((i, n) => ({ ...i, variantId: optionalNumber(i.variantId), sortOrder: n })), specifications: draft.specifications.map((s, n) => ({ ...s, sortOrder: n })) };
    try {
      const saved = await api.request(`/admin/catalog/products${draft.id ? `/${draft.id}` : ''}`, { method: draft.id ? 'PUT' : 'POST', body: payload });
      setDraft(saved); setRevision(n => n + 1); setNotice('Đã lưu sản phẩm và các phiên bản.');
    } catch (e) { setError(e); } finally { setBusy(false); }
  }
  async function saveTerm(event) {
    event.preventDefault(); setBusy(true); setError(null); setNotice('');
    try {
      await api.request(`/admin/catalog/taxonomies/${kind}${term.id ? `/${term.id}` : ''}`, { method: term.id ? 'PUT' : 'POST', body: { ...term, parentId: optionalNumber(term.parentId), countryCode: term.countryCode || null, sortOrder: Number(term.sortOrder || 0) } });
      setTerm(taxonomy()); setRevision(n => n + 1); setNotice('Đã lưu phân loại.');
    } catch (e) { setError(e); } finally { setBusy(false); }
  }
  const termField = (key, value) => setTerm(t => ({ ...t, [key]: value }));
  return <div className="catalog-admin">
    <header className="ca-heading"><div><span className="eyebrow">NẾP NHÀ · CATALOG</span><h1>Quản lý sản phẩm</h1><p>Từ một thiết kế đến từng màu sắc, kích thước và chất liệu.</p></div><button className="button" disabled={busy} onClick={() => { setTab('products'); setDraft(product()); setError(null); setNotice(''); }}>+ Thêm sản phẩm</button></header>
    <nav className="ca-tabs" aria-label="Quản trị catalog"><button aria-pressed={tab === 'products'} onClick={() => setTab('products')}>Sản phẩm & SKU</button><button aria-pressed={tab === 'taxonomies'} onClick={() => setTab('taxonomies')}>Phân loại</button></nav>
    <div ref={feedback} tabIndex={-1} className="ca-feedback"><ErrorNotice error={error} onRetry={() => setRevision(n => n + 1)} />
    {Object.keys(error?.fieldErrors || {}).length > 0 && <ul className="ca-validation">{Object.entries(error.fieldErrors).map(([key, message]) => <li key={key}>{key.replace(/variants\[(\d+)\]/g, (_, n) => `SKU ${Number(n) + 1}`).replace(/images\[(\d+)\]/g, (_, n) => `Ảnh ${Number(n) + 1}`)}: {message}</li>)}</ul>}
    {error?.code === 'CATALOG_STALE' && draft?.id && <button className="button secondary" disabled={busy} onClick={() => open(draft.id)}>Tải lại sản phẩm từ máy chủ</button>}
    {notice && <p role="status" className="ca-notice">{notice}</p>}</div>
    {loading && !list && <Loading />}
    {tab === 'products' ? <>
      <section className="panel" aria-busy={loading}><form className="ca-search" role="search" onSubmit={e => { e.preventDefault(); setSearch(query); setPage(0); }}><Field label="Tìm trong catalog" type="search" value={query} maxLength={200} onChange={e => setQuery(e.target.value)} /><button className="button secondary" disabled={loading}>Tìm kiếm</button></form>
        <div className="ca-table"><table><thead><tr><th>Sản phẩm</th><th>Đường dẫn</th><th>Trạng thái</th><th>Thao tác</th></tr></thead><tbody>{list?.items.map(p => <tr key={p.id}><td><strong>{p.name}</strong><small>#{p.id} · {p.categoryName}</small></td><td>{p.slug}</td><td>{productStates[p.status]}</td><td><button type="button" disabled={busy} onClick={() => open(p.id)} aria-label={`Sửa ${p.name}`}>Chỉnh sửa</button></td></tr>)}</tbody></table></div>
        {list && !list.items.length && <p className="empty-state">Chưa có sản phẩm phù hợp.</p>}{list && <Pager page={list} busy={loading} onChange={setPage} />}
      </section>
      {draft && <form className="ca-editor" ref={editor} onSubmit={save}>
        <header className="ca-heading"><h2>{draft.id ? `Chỉnh sửa #${draft.id}` : 'Một thiết kế mới'}</h2><div>{draft.id && draft.status === 'ACTIVE' && <Link target="_blank" rel="noreferrer" to={`/products/${draft.slug}`}>Xem trang khách ↗</Link>} <button type="button" disabled={busy} onClick={() => setDraft(null)}>Đóng trình sửa</button></div></header>
        <fieldset disabled={busy} className="ca-edit-fields"><section className="panel"><h3>01 / Thông tin sản phẩm</h3><div className="ca-grid">
          <Field label="Tên sản phẩm *" required maxLength={200} value={draft.name} onChange={e => patch('name', e.target.value)} onBlur={() => { if (!draft.slug) patch('slug', slugify(draft.name)); }} />
          <Field label="Đường dẫn *" required pattern="[a-z0-9]+(-[a-z0-9]+)*" maxLength={220} value={draft.slug} onChange={e => patch('slug', e.target.value)} />
          <Field label="Danh mục *"><select required value={draft.categoryId} onChange={e => patch('categoryId', e.target.value)}><option value="">Chọn danh mục</option>{options.categories?.map(c => <option key={c.id} value={c.id}>{c.name}{!c.active && ' (đã ẩn)'}</option>)}</select></Field>
          <Field label="Thương hiệu"><select value={draft.brandId ?? ''} onChange={e => patch('brandId', e.target.value)}><option value="">Chưa chọn</option>{options.brands?.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}</select></Field>
          <Field label="Trạng thái sản phẩm"><select value={draft.status} onChange={e => patch('status', e.target.value)}>{Object.entries(productStates).map(([key, value]) => <option key={key} value={key}>{value}</option>)}</select></Field>
          <Field label="Bảo hành (tháng)" type="number" min="0" max="1200" required value={draft.warrantyMonths} onChange={e => patch('warrantyMonths', e.target.value)} />
          {[['shortDescription', 'Mô tả ngắn', 500], ['description', 'Mô tả chi tiết', 30000], ['careInstructions', 'Hướng dẫn chăm sóc', 10000]].map(([key, label, max]) => <Field key={key} label={label}><textarea rows={3} maxLength={max} value={draft[key] || ''} onChange={e => patch(key, e.target.value)} /></Field>)}
          <label className="ca-check"><input type="checkbox" checked={!!draft.assemblyRequired} onChange={e => patch('assemblyRequired', e.target.checked)} />Cần lắp đặt</label>
          <Field label="Tiêu đề SEO" maxLength={200} value={draft.seoTitle || ''} onChange={e => patch('seoTitle', e.target.value)} />
          <Field label="Mô tả SEO" maxLength={320} value={draft.seoDescription || ''} onChange={e => patch('seoDescription', e.target.value)} />
          <Choices label="Phòng / không gian" items={options.rooms} value={draft.roomIds} onChange={v => patch('roomIds', v)} />
          <Choices label="Bộ sưu tập" items={options.collections} value={draft.collectionIds} onChange={v => patch('collectionIds', v)} />
        </div></section>
        <section className="panel"><div className="ca-heading"><div><h3>02 / Phiên bản & giá bán</h3><p>Mỗi SKU có giá và số đo riêng. Nhập tồn tại màn hình Kho.</p></div><button type="button" disabled={draft.variants.length >= 50} onClick={() => add('variants', variant())}>+ Thêm SKU</button></div>
          {draft.variants.map((v, n) => <fieldset key={v.id || n} className="ca-variant"><legend>SKU {n + 1}{v.id && ` · #${v.id}`}</legend><div className="ca-grid ca-three">
            {[['sku', 'Mã SKU *', 80], ['name', 'Tên phiên bản *', 200], ['variantKey', 'Khóa phiên bản *', 180], ['colorName', 'Tên màu', 80], ['colorHex', 'Mã màu HEX', 7], ['sizeLabel', 'Nhãn kích thước', 100]].map(([key, label, max]) => <Field key={key} label={label} required={['sku', 'name', 'variantKey'].includes(key)} maxLength={max} pattern={key === 'colorHex' ? '#[0-9a-fA-F]{6}' : undefined} value={v[key] || ''} onChange={e => row('variants', n, { [key]: e.target.value })} />)}
            {[['widthMm', 'Rộng (mm)'], ['depthMm', 'Sâu (mm)'], ['heightMm', 'Cao (mm)'], ['weightKg', 'Khối lượng (kg)']].map(([key, label]) => <Field key={key} label={label} type="number" step={key === 'weightKg' ? '0.001' : '0.01'} min={key === 'weightKg' ? '0.001' : '0.01'} value={v[key] ?? ''} onChange={e => row('variants', n, { [key]: e.target.value })} />)}
            <Field label="Giá bán (₫) *" type="number" required min="0" step="0.01" value={v.price} onChange={e => row('variants', n, { price: e.target.value })} />
            <Field label="Giá tham chiếu (₫)" type="number" min={v.price || 0} step="0.01" value={v.compareAtPrice ?? ''} onChange={e => row('variants', n, { compareAtPrice: e.target.value })} />
            <Field label="Chuẩn bị hàng (ngày)" type="number" min="0" max="3650" required value={v.leadTimeDays} onChange={e => row('variants', n, { leadTimeDays: e.target.value })} />
            <Field label="Trạng thái SKU"><select value={v.status} onChange={e => row('variants', n, { status: e.target.value })}><option value="ACTIVE">Đang bán</option><option value="INACTIVE">Tạm ẩn</option><option value="DISCONTINUED">Ngừng kinh doanh</option></select></Field>
          </div><Choices label="Chất liệu SKU" items={options.materials} value={v.materialIds} onChange={value => row('variants', n, { materialIds: value })} />
          {!v.id && draft.variants.length > 1 && <button type="button" onClick={() => remove('variants', n)}>Bỏ SKU mới</button>}{v.id && <small>Chuyển sang Tạm ẩn để ngừng bán và giữ lịch sử.</small>}</fieldset>)}
        </section>
        <section className="panel"><div className="ca-heading"><div><h3>03 / Thư viện ảnh</h3><p>Ảnh đầu tiên làm ảnh đại diện. Lưu sản phẩm mới trước khi gắn ảnh riêng cho SKU.</p></div><button type="button" disabled={draft.images.length >= 30} onClick={() => add('images', { url: '', altText: '', variantId: null })}>+ Thêm ảnh</button></div>
          {draft.images.map((image, n) => <div className="ca-media" key={n}><div className="ca-preview">{image.url && <img src={image.url} alt={image.altText || ''} loading="lazy" />}</div><div className="ca-grid"><Field label={`URL ảnh ${n + 1} *`} required maxLength={1000} value={image.url} onChange={e => row('images', n, { url: e.target.value })} /><Field label={`Mô tả ảnh ${n + 1}`} maxLength={255} value={image.altText || ''} onChange={e => row('images', n, { altText: e.target.value })} /><Field label={`Ảnh ${n + 1} áp dụng cho`}><select value={image.variantId ?? ''} onChange={e => row('images', n, { variantId: e.target.value })}><option value="">Toàn bộ sản phẩm</option>{draft.variants.filter(v => v.id).map(v => <option key={v.id} value={v.id}>{v.sku}</option>)}</select></Field><div className="ca-actions"><button type="button" disabled={n === 0} onClick={() => { const images = [...draft.images]; [images[n - 1], images[n]] = [images[n], images[n - 1]]; patch('images', images); }}>↑ Lên trước</button><button type="button" onClick={() => remove('images', n)}>Bỏ ảnh {n + 1}</button></div></div></div>)}
        </section>
        <section className="panel"><div className="ca-heading"><h3>04 / Thông số bổ sung</h3><button type="button" disabled={draft.specifications.length >= 80} onClick={() => add('specifications', { name: '', value: '', unit: '' })}>+ Thêm thông số</button></div>{draft.specifications.map((s, n) => <div className="ca-grid ca-spec" key={n}><Field label={`Tên thông số ${n + 1}`} required maxLength={100} value={s.name} onChange={e => row('specifications', n, { name: e.target.value })} /><Field label={`Giá trị ${n + 1}`} required maxLength={1000} value={s.value} onChange={e => row('specifications', n, { value: e.target.value })} /><Field label={`Đơn vị ${n + 1}`} maxLength={30} value={s.unit || ''} onChange={e => row('specifications', n, { unit: e.target.value })} /><button type="button" onClick={() => remove('specifications', n)}>Bỏ thông số {n + 1}</button></div>)}</section>
        <div className="ca-save"><span>Kiểm tra giá, SKU và ảnh trước khi xuất bản.</span><button className="button" type="submit">{busy ? 'Đang lưu…' : 'Lưu sản phẩm'}</button></div></fieldset>
      </form>}
    </> : <section className="panel"><nav className="ca-tabs" aria-label="Nhóm phân loại">{Object.entries(kinds).map(([key, label]) => <button key={key} disabled={busy} aria-pressed={kind === key} onClick={() => { setKind(key); setTerm(taxonomy()); }}>{label}</button>)}</nav><div className="ca-tax-layout"><div className="ca-table"><table><thead><tr><th>{kinds[kind]}</th><th>Hiển thị</th><th>Thao tác</th></tr></thead><tbody>{options[kind]?.map(t => <tr key={t.id}><td><strong>{t.name}</strong><small>{t.slug}</small>{t.parentId && <small>Thuộc: {options.categories?.find(c => c.id === t.parentId)?.name}</small>}</td><td>{t.active ? 'Đang hiện' : 'Đã ẩn'}</td><td><button disabled={busy} onClick={() => setTerm({ ...taxonomy(), ...t })}>Sửa {t.name}</button></td></tr>)}</tbody></table></div>
      <form className="ca-tax-form" onSubmit={saveTerm}><fieldset disabled={busy} className="ca-edit-fields"><h2>{term.id ? 'Chỉnh sửa' : 'Thêm mới'} {kinds[kind].toLowerCase()}</h2><Field label="Tên phân loại *" required maxLength={120} value={term.name} onChange={e => termField('name', e.target.value)} onBlur={() => { if (!term.slug) termField('slug', slugify(term.name)); }} /><Field label="Đường dẫn phân loại *" required pattern="[a-z0-9]+(-[a-z0-9]+)*" maxLength={150} value={term.slug} onChange={e => termField('slug', e.target.value)} />
        {kind === 'categories' && <Field label="Danh mục cha"><select value={term.parentId ?? ''} onChange={e => termField('parentId', e.target.value)}><option value="">Danh mục gốc</option>{options.categories?.filter(t => t.id !== term.id).map(t => <option key={t.id} value={t.id}>{t.name}</option>)}</select></Field>}
        <Field label="Mô tả phân loại"><textarea rows={3} maxLength={10000} value={term.description || ''} onChange={e => termField('description', e.target.value)} /></Field>
        {['categories', 'rooms', 'collections'].includes(kind) && <><Field label="URL ảnh phân loại" maxLength={1000} value={term.imageUrl || ''} onChange={e => termField('imageUrl', e.target.value)} /><Field label="Thứ tự hiển thị" type="number" min="0" required value={term.sortOrder} onChange={e => termField('sortOrder', e.target.value)} /></>}
        {kind === 'brands' && <><Field label="URL logo" maxLength={1000} value={term.logoUrl || ''} onChange={e => termField('logoUrl', e.target.value)} /><Field label="Mã quốc gia (VD: VN)" pattern="[A-Z]{2}" maxLength={2} value={term.countryCode || ''} onChange={e => termField('countryCode', e.target.value.toUpperCase())} /></>}
        {kind === 'materials' && <Field label="Chăm sóc chất liệu"><textarea rows={3} maxLength={10000} value={term.careInstructions || ''} onChange={e => termField('careInstructions', e.target.value)} /></Field>}
        <label className="ca-check"><input type="checkbox" checked={term.active} onChange={e => termField('active', e.target.checked)} />Hiển thị trong catalog</label>{kind === 'categories' && <p className="muted small">Ẩn danh mục sẽ ẩn toàn bộ danh mục con. Khi mở lại, hãy bật các danh mục con cần hiển thị.</p>}<div className="ca-actions"><button className="button" type="submit">Lưu phân loại</button><button type="button" onClick={() => setTerm(taxonomy())}>Tạo phân loại mới</button></div></fieldset></form></div></section>}
  </div>;
}
