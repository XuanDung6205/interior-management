import { useEffect, useMemo, useState } from 'react';
import { ErrorNotice, Icon, Loading, Pager } from '../../components/ui';
import { formatDate, toLocalInput, toUtc } from '../../lib/format';
import { useCommerce } from '../../lib/session';
import { contentApi } from './contentApi';

const emptyBanner = () => ({
  title: '', subtitle: '', imageUrl: '', mobileImageUrl: '',
  altText: '', targetUrl: '', placement: 'HOME_HERO',
  sortOrder: '0', isActive: true, startsAt: '', endsAt: '',
});

const emptyArticle = () => ({
  title: '', slug: '', summary: '', content: '',
  coverImageUrl: '', status: 'DRAFT', seoTitle: '', seoDescription: '', publishedAt: '',
});

// Chuyển tiêu đề tiếng Việt sang slug không dấu để gợi ý nhanh cho nhân viên
function slugify(value) {
  return value
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .replace(/đ/gi, 'd')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 250);
}

// Trang quản trị nội dung gồm 2 tab: Banner và Bài viết (route /admin/content)
export default function ContentAdminPage() {
  const { api } = useCommerce();
  const client = useMemo(() => contentApi(api), [api]);
  const [tab, setTab] = useState('banners');

  // State chung cho bảng dữ liệu & form
  const [data, setData] = useState(null);
  const [search, setSearch] = useState('');
  const [query, setQuery] = useState('');
  const [filter, setFilter] = useState('');
  const [page, setPage] = useState(0);
  const [revision, setRevision] = useState(0);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const [notice, setNotice] = useState('');
  const [fields, setFields] = useState({});

  const [editingBanner, setEditingBanner] = useState(undefined);
  const [bannerForm, setBannerForm] = useState(emptyBanner);
  const [editingArticle, setEditingArticle] = useState(undefined);
  const [articleForm, setArticleForm] = useState(emptyArticle);

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError(null);
    const req = tab === 'banners'
      ? client.adminBanners(query, filter, page, controller.signal)
      : client.adminArticles(query, filter, page, controller.signal);
    req.then(setData)
      .catch(err => { if (err.name !== 'AbortError') setError(err); })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [client, tab, query, filter, page, revision]);

  function switchTab(next) {
    setTab(next);
    setSearch('');
    setQuery('');
    setFilter('');
    setPage(0);
    setNotice('');
    setError(null);
    setFields({});
    setEditingBanner(undefined);
    setEditingArticle(undefined);
  }

  function openBannerEditor(banner) {
    setFields({});
    setError(null);
    setEditingBanner(banner);
    setBannerForm(banner ? {
      title: banner.title || '',
      subtitle: banner.subtitle || '',
      imageUrl: banner.imageUrl || '',
      mobileImageUrl: banner.mobileImageUrl || '',
      altText: banner.altText || '',
      targetUrl: banner.targetUrl || '',
      placement: banner.placement || 'HOME_HERO',
      sortOrder: String(banner.sortOrder ?? 0),
      isActive: !!banner.isActive,
      startsAt: toLocalInput(banner.startsAt),
      endsAt: toLocalInput(banner.endsAt),
    } : emptyBanner());
  }

  async function openArticleEditor(summary) {
    setFields({});
    setError(null);
    if (!summary) {
      setEditingArticle(null);
      setArticleForm(emptyArticle());
      return;
    }
    setBusy(true);
    try {
      const detail = await client.adminArticle(summary.id);
      setEditingArticle(detail);
      setArticleForm({
        title: detail.title || '',
        slug: detail.slug || '',
        summary: detail.summary || '',
        content: detail.content || '',
        coverImageUrl: detail.coverImageUrl || '',
        status: detail.status || 'DRAFT',
        seoTitle: detail.seoTitle || '',
        seoDescription: detail.seoDescription || '',
        publishedAt: toLocalInput(detail.publishedAt),
      });
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  }

  async function saveBanner(event) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    setFields({});
    try {
      const body = {
        ...bannerForm,
        sortOrder: Number(bannerForm.sortOrder || 0),
        startsAt: bannerForm.startsAt ? toUtc(bannerForm.startsAt, editingBanner?.startsAt) : null,
        endsAt: bannerForm.endsAt ? toUtc(bannerForm.endsAt, editingBanner?.endsAt) : null,
      };
      if (editingBanner) await client.updateBanner(editingBanner.id, body);
      else await client.createBanner(body);
      setEditingBanner(undefined);
      setNotice('Đã lưu banner thành công.');
      setRevision(v => v + 1);
    } catch (err) {
      setError(err);
      setFields(err.fieldErrors || {});
    } finally {
      setBusy(false);
    }
  }

  async function saveArticle(event) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    setFields({});
    try {
      const body = {
        ...articleForm,
        publishedAt: articleForm.publishedAt ? toUtc(articleForm.publishedAt, editingArticle?.publishedAt) : null,
      };
      if (editingArticle) await client.updateArticle(editingArticle.id, body);
      else await client.createArticle(body);
      setEditingArticle(undefined);
      setNotice('Đã lưu bài viết thành công.');
      setRevision(v => v + 1);
    } catch (err) {
      setError(err);
      setFields(err.fieldErrors || {});
    } finally {
      setBusy(false);
    }
  }

  async function removeItem(id) {
    if (busy) return;
    setBusy(true);
    setError(null);
    setNotice('');
    try {
      if (tab === 'banners') await client.deleteBanner(id);
      else await client.deleteArticle(id);
      setNotice(tab === 'banners' ? 'Đã xóa banner.' : 'Đã xóa bài viết.');
      setRevision(v => v + 1);
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  }

  return <>
    <div className="breadcrumb"><span>Quản trị</span><span>/</span><span>Nội dung</span></div>
    <div className="page-heading">
      <div>
        <span className="eyebrow">KHÔNG GIAN QUẢN TRỊ</span>
        <h1>Quản lý nội dung<span className="heading-dot">.</span></h1>
        <p>Quản lý banner trang chủ và các bài viết tạp chí nội thất.</p>
      </div>
      <button
        className="button"
        disabled={busy}
        onClick={() => (tab === 'banners' ? openBannerEditor(null) : openArticleEditor(null))}
      >
        <Icon name="plus" size={18} />{tab === 'banners' ? 'Tạo banner' : 'Viết bài mới'}
      </button>
    </div>

    <div className="tv5-tabs" role="tablist">
      <button
        role="tab"
        aria-selected={tab === 'banners'}
        className={tab === 'banners' ? 'button' : 'button secondary'}
        onClick={() => switchTab('banners')}
      >Banner</button>
      <button
        role="tab"
        aria-selected={tab === 'articles'}
        className={tab === 'articles' ? 'button' : 'button secondary'}
        onClick={() => switchTab('articles')}
      >Bài viết</button>
    </div>

    <ErrorNotice error={error} onRetry={() => setRevision(v => v + 1)} />
    {notice && <div className="notice success" role="status">{notice}</div>}

    {tab === 'banners' && editingBanner !== undefined && (
      <section className="panel coupon-editor">
        <div className="panel-heading">
          <h2>{editingBanner ? `Sửa banner #${editingBanner.id}` : 'Tạo banner mới'}</h2>
          <button className="icon-button" aria-label="Đóng" onClick={() => setEditingBanner(undefined)}>
            <Icon name="close" />
          </button>
        </div>
        <form onSubmit={saveBanner} className="tv5-form-pad">
          <div className="form-grid">
            <div className="form-field">
              <label htmlFor="bn-title">Tiêu đề</label>
              <input id="bn-title" required value={bannerForm.title} onChange={e => setBannerForm(f => ({ ...f, title: e.target.value }))} />
              {fields.title && <span className="field-error">{fields.title}</span>}
            </div>
            <div className="form-field">
              <label htmlFor="bn-placement">Vị trí hiển thị</label>
              <select id="bn-placement" value={bannerForm.placement} onChange={e => setBannerForm(f => ({ ...f, placement: e.target.value }))}>
                <option value="HOME_HERO">HOME_HERO</option>
                <option value="HOME_GALLERY">HOME_GALLERY</option>
                <option value="PROMO_STRIP">PROMO_STRIP</option>
              </select>
            </div>
            <div className="form-field wide">
              <label htmlFor="bn-image">URL ảnh chính</label>
              <input id="bn-image" required placeholder="/images/home-hero.jpg" value={bannerForm.imageUrl} onChange={e => setBannerForm(f => ({ ...f, imageUrl: e.target.value }))} />
              {fields.imageUrl && <span className="field-error">{fields.imageUrl}</span>}
            </div>
            <div className="form-field">
              <label htmlFor="bn-target">Liên kết đích</label>
              <input id="bn-target" placeholder="/articles hoặc /cart#catalog" value={bannerForm.targetUrl} onChange={e => setBannerForm(f => ({ ...f, targetUrl: e.target.value }))} />
              {fields.targetUrl && <span className="field-error">{fields.targetUrl}</span>}
            </div>
            <div className="form-field">
              <label htmlFor="bn-order">Thứ tự</label>
              <input id="bn-order" type="number" min="0" value={bannerForm.sortOrder} onChange={e => setBannerForm(f => ({ ...f, sortOrder: e.target.value }))} />
            </div>
            <div className="form-field">
              <label htmlFor="bn-start">Bắt đầu hiển thị</label>
              <input id="bn-start" type="datetime-local" value={bannerForm.startsAt} onChange={e => setBannerForm(f => ({ ...f, startsAt: e.target.value }))} />
            </div>
            <div className="form-field">
              <label htmlFor="bn-end">Kết thúc hiển thị</label>
              <input id="bn-end" type="datetime-local" value={bannerForm.endsAt} onChange={e => setBannerForm(f => ({ ...f, endsAt: e.target.value }))} />
              {fields.endsAt && <span className="field-error">{fields.endsAt}</span>}
            </div>
            <label className="checkbox-label wide">
              <input type="checkbox" checked={bannerForm.isActive} onChange={e => setBannerForm(f => ({ ...f, isActive: e.target.checked }))} />
              Đang bật hiển thị
            </label>
          </div>
          <div className="editor-actions">
            <button type="button" className="button secondary" onClick={() => setEditingBanner(undefined)}>Hủy</button>
            <button className="button" disabled={busy}>{busy ? 'Đang lưu…' : 'Lưu banner'}</button>
          </div>
        </form>
      </section>
    )}

    {tab === 'articles' && editingArticle !== undefined && (
      <section className="panel coupon-editor">
        <div className="panel-heading">
          <h2>{editingArticle ? `Sửa bài #${editingArticle.id}` : 'Viết bài mới'}</h2>
          <button className="icon-button" aria-label="Đóng" onClick={() => setEditingArticle(undefined)}>
            <Icon name="close" />
          </button>
        </div>
        <form onSubmit={saveArticle} className="tv5-form-pad">
          <div className="form-grid">
            <div className="form-field">
              <label htmlFor="art-title">Tiêu đề</label>
              <input
                id="art-title"
                required
                value={articleForm.title}
                onChange={e => {
                  const title = e.target.value;
                  setArticleForm(f => ({
                    ...f,
                    title,
                    slug: editingArticle ? f.slug : slugify(title),
                  }));
                }}
              />
              {fields.title && <span className="field-error">{fields.title}</span>}
            </div>
            <div className="form-field">
              <label htmlFor="art-slug">Slug URL</label>
              <input id="art-slug" required value={articleForm.slug} onChange={e => setArticleForm(f => ({ ...f, slug: e.target.value }))} />
              {fields.slug && <span className="field-error">{fields.slug}</span>}
            </div>
            <div className="form-field">
              <label htmlFor="art-status">Trạng thái</label>
              <select id="art-status" value={articleForm.status} onChange={e => setArticleForm(f => ({ ...f, status: e.target.value }))}>
                <option value="DRAFT">Nháp (DRAFT)</option>
                <option value="PUBLISHED">Xuất bản (PUBLISHED)</option>
                <option value="ARCHIVED">Lưu trữ (ARCHIVED)</option>
              </select>
            </div>
            <div className="form-field">
              <label htmlFor="art-cover">URL ảnh bìa</label>
              <input id="art-cover" placeholder="/images/sofa-modena.jpg" value={articleForm.coverImageUrl} onChange={e => setArticleForm(f => ({ ...f, coverImageUrl: e.target.value }))} />
              {fields.coverImageUrl && <span className="field-error">{fields.coverImageUrl}</span>}
            </div>
            <div className="form-field wide">
              <label htmlFor="art-summary">Tóm tắt ngắn</label>
              <input id="art-summary" value={articleForm.summary} onChange={e => setArticleForm(f => ({ ...f, summary: e.target.value }))} />
            </div>
            <div className="form-field wide">
              <label htmlFor="art-content">Nội dung bài viết</label>
              <textarea id="art-content" rows={8} required value={articleForm.content} onChange={e => setArticleForm(f => ({ ...f, content: e.target.value }))} />
              {fields.content && <span className="field-error">{fields.content}</span>}
            </div>
          </div>
          <div className="editor-actions">
            <button type="button" className="button secondary" onClick={() => setEditingArticle(undefined)}>Hủy</button>
            <button className="button" disabled={busy}>{busy ? 'Đang lưu…' : 'Lưu bài viết'}</button>
          </div>
        </form>
      </section>
    )}

    <section className="panel">
      <div className="panel-heading coupon-toolbar">
        <h2>
          {tab === 'banners' ? 'Danh sách banner' : 'Danh sách bài viết'}
          <span className="count-badge">{data?.totalElements ?? '—'}</span>
        </h2>
        <form className="search-form" onSubmit={e => { e.preventDefault(); setPage(0); setQuery(search.trim()); }}>
          <input type="search" value={search} onChange={e => setSearch(e.target.value)} placeholder="Tìm theo tiêu đề..." />
          <button className="button secondary" disabled={loading}>Lọc</button>
        </form>
      </div>
      {loading ? <Loading text="Đang tải dữ liệu…" /> : data?.items.length ? (
        <div className="tv5-table-wrap">
          <table className="tv5-table">
            <thead>
              <tr>
                <th>Tiêu đề</th>
                <th>{tab === 'banners' ? 'Vị trí' : 'Slug'}</th>
                <th>Trạng thái</th>
                <th>Cập nhật</th>
                <th>Thao tác</th>
              </tr>
            </thead>
            <tbody>
              {data.items.map(item => (
                <tr key={item.id}>
                  <td><strong>{item.title}</strong></td>
                  <td>{tab === 'banners' ? item.placement : item.slug}</td>
                  <td>
                    <span className={`status-pill ${(tab === 'banners' ? item.isActive : item.status === 'PUBLISHED') ? 'active' : 'inactive'}`}>
                      <span />{tab === 'banners' ? (item.isActive ? 'Đang bật' : 'Đã tắt') : item.status}
                    </span>
                  </td>
                  <td>{item.updatedAt ? formatDate(item.updatedAt) : '—'}</td>
                  <td className="tv5-row-actions">
                    <button className="text-button" disabled={busy} onClick={() => (tab === 'banners' ? openBannerEditor(item) : openArticleEditor(item))}>Sửa</button>
                    <button className="text-button destructive" disabled={busy} onClick={() => removeItem(item.id)}>Xóa</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : !error && <div className="empty-state"><p>Chưa có dữ liệu phù hợp.</p></div>}
      <Pager page={data} busy={loading || busy} onChange={setPage} />
    </section>
  </>;
}
