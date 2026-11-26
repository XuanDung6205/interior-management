import { useEffect, useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { ErrorNotice, Loading, ProductImage } from '../../components/ui';
import { formatDate } from '../../lib/format';
import { useCommerce } from '../../lib/session';
import { contentApi } from './contentApi';

// Trang đọc chi tiết bài viết (render theo đoạn văn thuần để tránh XSS thay vì dangerouslySetInnerHTML)
export default function ArticleDetailPage() {
  const { api } = useCommerce();
  const { slug } = useParams();
  const client = useMemo(() => contentApi(api), [api]);
  const [article, setArticle] = useState(null);
  const [error, setError] = useState(null);
  const [revision, setRevision] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    setError(null);
    setArticle(null);
    client.publicArticle(slug, controller.signal)
      .then(setArticle)
      .catch(err => {
        if (err.name !== 'AbortError') setError(err);
      });
    return () => controller.abort();
  }, [client, slug, revision]);

  return <>
    <div className="breadcrumb">
      <Link to="/">Trang chủ</Link><span>/</span>
      <Link to="/articles">Bài viết</Link><span>/</span>
      <span>{article?.title || 'Chi tiết'}</span>
    </div>
    <ErrorNotice error={error} onRetry={() => setRevision(v => v + 1)} />
    {!article && !error && <Loading text="Đang tải bài viết…" />}
    {article && (
      <article className="panel tv5-article-detail">
        <span className="eyebrow">
          {article.authorName} · {article.publishedAt ? formatDate(article.publishedAt) : 'Đã xuất bản'}
        </span>
        <h1>{article.title}</h1>
        {article.summary && <p className="muted tv5-article-summary">{article.summary}</p>}
        {article.coverImageUrl && (
          <div className="tv5-article-cover">
            <ProductImage src={article.coverImageUrl} name={article.title} />
          </div>
        )}
        <div className="tv5-article-body">
          {article.content.split(/\n{2,}/).map((paragraph, index) => (
            <p key={index}>{paragraph}</p>
          ))}
        </div>
        <div className="tv5-article-footer">
          <Link to="/articles" className="inline-link">← Quay lại danh sách bài viết</Link>
        </div>
      </article>
    )}
  </>;
}
