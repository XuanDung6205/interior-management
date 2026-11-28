import { Link } from 'react-router-dom';
import { BrandLogo } from './BrandLogo';
import { Icon } from './ui';

const columns = [
  { title: 'Mua sắm', links: [['/sale', 'Tất cả sản phẩm'], ['/sale?room=phong-khach', 'Nội thất phòng khách'], ['/sale?room=phong-an', 'Nội thất phòng ăn'], ['/sale?room=phong-ngu', 'Nội thất phòng ngủ']] },
  { title: 'Cảm hứng', links: [['/articles', 'Tạp chí Nếp Nhà'], ['/#home-inspiration', 'Ý tưởng không gian']] },
  { title: 'Hỗ trợ', links: [['/orders', 'Theo dõi đơn hàng'], ['/cart', 'Giỏ hàng của bạn'], ['/account', 'Sổ địa chỉ giao hàng']] },
];
const promises = [['truck', 'Giao miễn phí từ 10 triệu'], ['tools', 'Lắp đặt tận nhà'], ['shield', 'Bảo hành theo sản phẩm']];

export default function SiteFooter() {
  return <footer className="site-footer">
    <div className="site-footer-inner">
      <div className="site-footer-brand">
        <Link to="/" className="footer-brand" aria-label="Nếp Nhà — về trang chủ"><BrandLogo size={38} /></Link>
        <p>Chọn điều giản dị, sống thật an yên. Nội thất gỗ và vải mộc cho tổ ấm của bạn.</p>
        <ul className="site-footer-promises">{promises.map(([icon, text]) => <li key={icon}><Icon name={icon} size={18} />{text}</li>)}</ul>
      </div>
      {columns.map(column => <nav className="site-footer-column" key={column.title} aria-label={column.title}>
        <h2>{column.title}</h2>
        {column.links.map(([to, label]) => <Link key={to} to={to}>{label}</Link>)}
      </nav>)}
    </div>
    <div className="site-footer-bottom"><span>© {new Date().getFullYear()} Nếp Nhà · Đồ án nhóm, dữ liệu demo</span><span className="site-footer-icons" aria-hidden="true"><Icon name="sofa" size={18} /><Icon name="armchair" size={18} /><Icon name="lamp" size={18} /><Icon name="plant" size={18} /></span></div>
  </footer>;
}
