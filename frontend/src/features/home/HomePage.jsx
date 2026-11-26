import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { ErrorNotice, Icon, Loading, ProductImage } from '../../components/ui';
import { formatDate, formatMoney } from '../../lib/format';
import { useCommerce } from '../../lib/session';
import { trackEvent } from '../../lib/tracking';
import HomeGallery from './HomeGallery';
import './home.css';

const inspirations = [
  { title: 'Góc sum vầy', detail: 'Sofa êm ái cho những cuộc chuyện trò', image: '/images/sofa-modena.jpg', alt: 'Sofa màu kem trong không gian phòng khách sáng', href: '/sale?category=demo-sofa' },
  { title: 'Khoảng thảnh thơi', detail: 'Bàn trà mộc mạc, vừa đủ tinh tế', image: '/images/ban-tra-oslo.jpg', alt: 'Bàn trà gỗ sồi trong không gian tối giản', href: '/sale?category=demo-ban-tra' },
  { title: 'Một chỗ riêng', detail: 'Ghế thư giãn cho phút nghỉ ngơi', image: '/images/ghe-aalto.jpg', alt: 'Ghế thư giãn màu beige bên cửa sổ', href: '/sale?category=demo-ghe-thu-gian' },
];
const featuredSkus = ['NN-DEMO-01-04', 'NN-DEMO-03-05', 'NN-DEMO-04-04'];
const inspirationImages = new Set(inspirations.map(item => item.image));
const homeCatalogCache = new WeakMap();

export default function HomePage() {
  const { api } = useCommerce();
  const [catalog, setCatalog] = useState(() => homeCatalogCache.get(api) || null);
  const [heroBanner, setHeroBanner] = useState(null); // Banner HOME_HERO admin tạo (ko có thì xài ảnh mặc định)
  const [latestArticles, setLatestArticles] = useState([]); // Top 3 bài viết mới xuất bản
  const [error, setError] = useState(null);
  const [revision, setRevision] = useState(0);

  useEffect(() => {
    // Ghi lượt xem trang chủ cho phễu hành vi (ko ảnh hưởng UI nếu lỗi mạng)
    trackEvent(api, { eventType: 'PAGE_VIEW', pagePath: '/' });
  }, [api]);

  useEffect(() => {
    const controller = new AbortController();
    setError(null);
    api.request('/catalog/products?size=100', { signal: controller.signal })
      .then(value => { if (!controller.signal.aborted) { homeCatalogCache.set(api, value); setCatalog(value); } })
      .catch(value => { if (value.name !== 'AbortError' && !homeCatalogCache.has(api)) setError(value); });
    // Lấy thêm banner hero + 3 bài viết mới nhất của TV5; lỗi thì bỏ qua nhẹ nhàng ko làm hỏng trang chủ
    api.request('/commerce/content/banners?placement=HOME_HERO', { signal: controller.signal })
      .then(list => { if (!controller.signal.aborted && Array.isArray(list) && list.length > 0) setHeroBanner(list[0]); })
      .catch(() => { });
    api.request('/commerce/content/articles?query=&page=0&size=3', { signal: controller.signal })
      .then(res => { if (!controller.signal.aborted && Array.isArray(res?.items)) setLatestArticles(res.items.slice(0, 3)); })
      .catch(() => { });
    return () => controller.abort();
  }, [api, revision]);
  const products = catalog?.items || [];
  const preferred = featuredSkus.map(sku => products.find(item => item.sku === sku)).filter(Boolean);
  const usedImages = new Set(inspirationImages);
  const featured = [...preferred, ...products].filter(item => {
    if (!item.imageUrl || usedImages.has(item.imageUrl)) return false;
    usedImages.add(item.imageUrl);
    return true;
  }).slice(0, 3);

  return <div className="home-page">
    <section className="home-hero" aria-labelledby="home-title">
      <img
        className="home-hero-image"
        src={heroBanner?.imageUrl || '/images/home-hero.jpg'}
        alt={heroBanner?.altText || heroBanner?.title || 'Phòng khách ấm áp với sofa màu kem và nội thất gỗ'}
        fetchPriority="high"
      />
      <div className="home-hero-shade" />
      <div className="home-hero-copy">
        <span className="home-kicker">NẾP NHÀ · NỘI THẤT CHO TỔ ẤM</span>
        {heroBanner ? (
          <>
            <h1 id="home-title">{heroBanner.title}</h1>
            <p>{heroBanner.subtitle || 'Mỗi món đồ góp một chút dịu dàng, để nhà luôn là nơi bạn muốn trở về.'}</p>
            {heroBanner.targetUrl && heroBanner.targetUrl.startsWith('/') ? (
              <Link className="home-hero-cta" to={heroBanner.targetUrl}>Khám phá ngay <Icon name="arrow" size={19} /></Link>
            ) : (
              <a className="home-hero-cta" href={heroBanner.targetUrl || '#home-inspiration'}>Khám phá cảm hứng <Icon name="arrow" size={19} /></a>
            )}
          </>
        ) : (
          <>
            <h1 id="home-title">Một không gian<br /><em>thật là mình.</em></h1>
            <p>Mỗi món đồ góp một chút dịu dàng, để nhà luôn là nơi bạn muốn trở về.</p>
            <a className="home-hero-cta" href="#home-inspiration">Khám phá cảm hứng <Icon name="arrow" size={19} /></a>
          </>
        )}
      </div>
      <div className="home-hero-caption">SỐNG CHẬM · SỐNG ĐẸP · SỐNG CÙNG NẾP NHÀ</div>
    </section>

    <section className="home-intro home-container" id="home-inspiration">
      <span className="home-kicker">CẢM HỨNG TỪ NHỮNG ĐIỀU GẦN GŨI</span>
      <div className="home-intro-row"><h2>Nhà đẹp theo<br /><em>cách của bạn.</em></h2><p>Đường nét giản dị, chất liệu ấm áp và những góc nhỏ có thể ở lại cùng bạn thật lâu. Bắt đầu từ món đồ bạn yêu, rồi để không gian tự kể câu chuyện của mình.</p></div>
    </section>

    <section className="home-inspiration home-container" aria-label="Ý tưởng cho không gian sống">
      {inspirations.map((item, index) => <Link to={item.href} className="home-inspiration-card" key={item.title}>
        <div className="home-inspiration-image"><img src={item.image} alt={item.alt} loading="lazy" /></div>
        <span className="home-inspiration-number">0{index + 1}</span>
        <h3>{item.title}</h3><p>{item.detail}</p>
        <span className="home-underlined-link">Xem sản phẩm <Icon name="arrow" size={16} /></span>
      </Link>)}
    </section>

    <section className="home-featured" aria-labelledby="home-featured-title"><div className="home-container">
      <div className="home-section-heading"><div><span className="home-kicker">LỰA CHỌN TỪ NẾP NHÀ</span><h2 id="home-featured-title">Món đồ cho ngày thường <em>đẹp hơn.</em></h2></div><Link to="/sale" className="home-underlined-link">Xem tất cả <Icon name="arrow" size={17} /></Link></div>
      <ErrorNotice error={error} onRetry={() => setRevision(value => value + 1)} />
      {!catalog && !error && <Loading text="Đang tải sản phẩm…" />}
      {catalog && (featured.length ? <div className="home-product-grid">{featured.map(item => <Link to={`/products/${item.slug}`} className="home-product-card" key={item.id}>
        <ProductImage src={item.imageUrl} name={`Ảnh minh họa ${item.name}`} />
        <div className="home-product-details"><span className="home-product-type">{item.categoryName}</span><h3>{item.name}</h3><strong>{formatMoney(item.price)}</strong><span className="home-product-arrow" aria-hidden="true"><Icon name="arrow" size={19} /></span></div>
      </Link>)}</div> : <p className="home-empty">Chưa có sản phẩm để hiển thị. Hãy thêm sản phẩm vào catalog để bắt đầu.</p>)}
    </div></section>

    <HomeGallery />

    {latestArticles.length > 0 && (
      <section className="tv5-home-articles" aria-labelledby="home-articles-title">
        <div className="home-container">
          <div className="home-section-heading">
            <div>
              <span className="home-kicker">TẠP CHÍ NẾP NHÀ</span>
              <h2 id="home-articles-title">Chuyện nhà &amp; <em>cảm hứng sống.</em></h2>
            </div>
            <Link to="/articles" className="home-underlined-link">Đọc tất cả <Icon name="arrow" size={17} /></Link>
          </div>
          <div className="home-product-grid">
            {latestArticles.map(article => (
              <Link to={`/articles/${article.slug}`} className="home-product-card" key={article.id}>
                <ProductImage src={article.coverImageUrl} name={article.title} />
                <div className="home-product-details">
                  <span className="home-product-type">
                    {article.authorName} · {article.publishedAt ? formatDate(article.publishedAt) : 'Mới đăng'}
                  </span>
                  <h3>{article.title}</h3>
                  {article.summary && <p className="muted small">{article.summary}</p>}
                </div>
              </Link>
            ))}
          </div>
        </div>
      </section>
    )}

    <section className="home-closing"><div className="home-container home-closing-inner"><span className="home-kicker">NẾP NHÀ · MỘT CHÚT BÌNH YÊN</span><h2>Chọn món đồ mình yêu.<br /><em>Gọi đó là nhà.</em></h2><p>Cùng tạo một không gian vừa vặn với nhịp sống của bạn.</p><Link to="/sale" className="home-underlined-link">Bắt đầu khám phá <Icon name="arrow" size={18} /></Link></div></section>
  </div>;
}
