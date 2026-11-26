import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { ErrorNotice, Icon, Loading, ProductImage } from '../../components/ui';
import { formatMoney } from '../../lib/format';
import { useCommerce } from '../../lib/session';
import HomeGallery from './HomeGallery';
import './home.css';

const inspirations = [
  { title: 'Góc sum vầy', detail: 'Sofa êm ái cho những cuộc chuyện trò', image: '/images/sofa-modena.jpg', alt: 'Sofa màu kem trong không gian phòng khách sáng' },
  { title: 'Khoảng thảnh thơi', detail: 'Bàn trà mộc mạc, vừa đủ tinh tế', image: '/images/ban-tra-oslo.jpg', alt: 'Bàn trà gỗ sồi trong không gian tối giản' },
  { title: 'Một chỗ riêng', detail: 'Ghế thư giãn cho phút nghỉ ngơi', image: '/images/ghe-aalto.jpg', alt: 'Ghế thư giãn màu beige bên cửa sổ' },
];

export default function HomePage() {
  const { api } = useCommerce();
  const [catalog, setCatalog] = useState(null);
  const [error, setError] = useState(null);
  const [revision, setRevision] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    setError(null);
    api.request('/commerce/catalog/variants?query=&page=0&size=6', { signal: controller.signal })
      .then(setCatalog)
      .catch(value => { if (value.name !== 'AbortError') setError(value); });
    return () => controller.abort();
  }, [api, revision]);

  return <div className="home-page">
    <section className="home-hero" aria-labelledby="home-title">
      <img className="home-hero-image" src="/images/home-hero.jpg" alt="Phòng khách ấm áp với sofa màu kem và nội thất gỗ" fetchPriority="high" />
      <div className="home-hero-shade" />
      <div className="home-hero-copy">
        <span className="home-kicker">NẾP NHÀ · NỘI THẤT CHO TỔ ẤM</span>
        <h1 id="home-title">Một không gian<br /><em>thật là mình.</em></h1>
        <p>Mỗi món đồ góp một chút dịu dàng, để nhà luôn là nơi bạn muốn trở về.</p>
        <a className="home-hero-cta" href="#home-inspiration">Khám phá cảm hứng <Icon name="arrow" size={19} /></a>
      </div>
      <div className="home-hero-caption">SỐNG CHẬM · SỐNG ĐẸP · SỐNG CÙNG NẾP NHÀ</div>
    </section>

    <section className="home-intro home-container" id="home-inspiration">
      <span className="home-kicker">CẢM HỨNG TỪ NHỮNG ĐIỀU GẦN GŨI</span>
      <div className="home-intro-row"><h2>Nhà đẹp theo<br /><em>cách của bạn.</em></h2><p>Đường nét giản dị, chất liệu ấm áp và những góc nhỏ có thể ở lại cùng bạn thật lâu. Bắt đầu từ món đồ bạn yêu, rồi để không gian tự kể câu chuyện của mình.</p></div>
    </section>

    <section className="home-inspiration home-container" aria-label="Ý tưởng cho không gian sống">
      {inspirations.map((item, index) => <Link to="/cart#catalog" className="home-inspiration-card" key={item.title}>
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
      {catalog && (catalog.items.length ? <div className="home-product-grid">{catalog.items.slice(0, 3).map(item => <Link to="/cart#catalog" className="home-product-card" key={item.id}>
        <ProductImage src={item.imageUrl} name={`${item.productName} — ${item.variantName}`} />
        <div className="home-product-details"><span className="home-product-type">{item.variantName}</span><h3>{item.productName}</h3><strong>{formatMoney(item.price)}</strong><span className="home-product-arrow" aria-hidden="true"><Icon name="arrow" size={19} /></span></div>
      </Link>)}</div> : <p className="home-empty">Chưa có sản phẩm để hiển thị. Hãy thêm sản phẩm vào catalog để bắt đầu.</p>)}
    </div></section>

    <HomeGallery />

    <section className="home-closing"><div className="home-container home-closing-inner"><span className="home-kicker">NẾP NHÀ · MỘT CHÚT BÌNH YÊN</span><h2>Chọn món đồ mình yêu.<br /><em>Gọi đó là nhà.</em></h2><p>Cùng tạo một không gian vừa vặn với nhịp sống của bạn.</p><Link to="/cart#catalog" className="home-underlined-link">Bắt đầu khám phá <Icon name="arrow" size={18} /></Link></div></section>
  </div>;
}
