import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { ErrorNotice, Icon, Loading, Pager, ProductImage } from '../../components/ui';
import { formatDate } from '../../lib/format';
import { useCommerce } from '../../lib/session';
import { trackEvent } from '../../lib/tracking';
import { contentApi } from './contentApi';

// Trang danh sách bài viết công khai cho khách (route /articles)
export default function ArticlesPage() {
  const { api, isStaff } = useCommerce();
  const client = useMemo(() => contentApi(api), [api]);
  const [data, setData] = useState(null);
  const [search, setSearch] = useState(''); // Giá trị đang gõ trên ô tìm kiếm
  const [query, setQuery] = useState('');   // Từ khóa chính thức dùng gọi API sau khi submit
  const [page, setPage] = useState(0);
  const [revision, setRevision] = useState(0);
  const [error, setError] = useState(null);

  useEffect(() => {
    // Ghi nhận lượt xem trang tạp chí để dashboard phễu hành vi có số PAGE_VIEW thật
    trackEvent(api, { eventType: 'PAGE_VIEW', pagePath: '/articles' });
  }, [api]);

  useEffect(() => {
    const controller = new AbortController(); // Hủy request cũ nếu user đổi trang nhanh
    setError(null);
    client.publicArticles(query, page, controller.signal)
      .then(setData)
      .catch(err => {
        if (err.name !== 'AbortError') setError(err);
      });
    return () => controller.abort();
  }, [client, query, page, revision]);

  function onSearch(event) {
    event.preventDefault();
    setPage(0);
    setQuery(search.trim());
  }

  return <>
    <div className="breadcrumb">
      <Link to="/">Trang chủ</Link><span>/</span><span>Bài viết</span>
    </div>
    <div className="page-heading">
      <div>
        <span className="eyebrow">CẢM HỨNG KHÔNG GIAN</span>
        <h1>Tạp chí Nếp Nhà<span className="heading-dot">.</span></h1>
        <p>Gợi ý bài trí, chọn chất liệu và giữ gìn đồ nội thất trong tổ ấm của bạn.</p>
      </div>
      <div className="tv5-row-actions">
        {isStaff && (
          // Nếu là admin/staff thì hiện nút tắt qua trang quản trị viết bài luôn cho tiện
          <Link to="/admin/content" className="button">Quản lý / Viết bài</Link>
        )}
        <form className="search-form" onSubmit={onSearch}>
          <Icon name="search" />
          <label className="sr-only" htmlFor="article-search">Tìm bài viết</label>
          <input
            id="article-search"
            type="search"
            value={search}
            onChange={e => setSearch(e.target.value)}
            placeholder="Tìm theo tiêu đề..."
          />
          <button className="button secondary">Tìm kiếm</button>
        </form>
      </div>
    </div>

    <ErrorNotice error={error} onRetry={() => setRevision(v => v + 1)} />
    {!data && !error && <Loading text="Đang tải bài viết…" />}

    {data && (data.items.length ? (
      <div className="product-grid">
        {data.items.map(item => (
          <Link key={item.id} to={`/articles/${item.slug}`} className="product-card">
            <ProductImage src={item.coverImageUrl} name={item.title} />
            <div className="product-card-content">
              <span className="sku">
                {item.authorName} · {item.publishedAt ? formatDate(item.publishedAt) : 'Mới đăng'}
              </span>
              <h3>{item.title}</h3>
              {item.summary && <p className="muted small">{item.summary}</p>}
            </div>
          </Link>
        ))}
      </div>
    ) : (
      <div className="empty-state">
        <h3>Chưa có bài viết phù hợp</h3>
        <p>Thử tìm bằng từ khóa khác hoặc quay lại sau nhé.</p>
      </div>
    ))}

    <Pager page={data} busy={!data && !error} onChange={setPage} />
  </>;
}
