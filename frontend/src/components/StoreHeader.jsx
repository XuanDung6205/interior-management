import { useEffect, useRef, useState } from 'react';
import { Link, NavLink, useLocation, useNavigate } from 'react-router-dom';
import { Icon } from './ui';
import { UnreadBadge } from '../features/notifications/NotificationsPage';

const rooms = [
  { key: 'living', slug: 'phong-khach', label: 'Phòng khách', links: [['Sofa', 'demo-sofa'], ['Bàn trà', 'demo-ban-tra'], ['Ghế thư giãn', 'demo-ghe-thu-gian']] },
  { key: 'dining', slug: 'phong-an', label: 'Phòng ăn', links: [['Bàn ăn', 'demo-ban-an'], ['Ghế ăn', 'demo-ghe-an']] },
  { key: 'bedroom', slug: 'phong-ngu', label: 'Phòng ngủ', links: [['Giường', 'demo-giuong'], ['Tủ', 'demo-tu-ke']] },
];
const adminLinks = [
  ['/admin', 'Tổng quan quản trị'],
  ['/admin/dashboard', 'Thống kê'],
  ['/admin/content', 'Nội dung'],
  ['/admin/reviews', 'Duyệt đánh giá'],
  ['/admin/operations', 'Vận hành'],
  ['/admin/orders', 'Quản lý đơn hàng'],
  ['/admin/payments', 'Khoản thu'],
  ['/admin/refunds', 'Hoàn tiền'],
  ['/admin/coupons', 'Quản lý ưu đãi'],
  ['/admin/inventory/receipts', 'Nhập kho'],
  ['/admin/inventory/operations', 'Điều chuyển'],
  ['/admin/inventory/dispatch', 'Xuất kho'],
  ['/admin/inventory/shipments', 'Vận đơn'],
];
const catalogHref = query => query ? `/sale?query=${encodeURIComponent(query)}` : '/sale';
const roomHref = (room, category) => `/sale?${new URLSearchParams({ room, ...(category ? { category } : {}) })}`;

export default function StoreHeader({ session, isStaff, isAdmin, busy, onLogout }) {
  const location = useLocation();
  const navigate = useNavigate();
  const headerRef = useRef(null);
  const searchInputRef = useRef(null);
  const [openPanel, setOpenPanel] = useState(null);
  const [mobileOpen, setMobileOpen] = useState(false);
  const [search, setSearch] = useState('');

  useEffect(() => { setOpenPanel(null); setMobileOpen(false); }, [location.pathname, location.search, location.hash]);
  useEffect(() => {
    if (!openPanel && !mobileOpen) return;
    const closeOutside = event => { if (!headerRef.current?.contains(event.target)) { setOpenPanel(null); setMobileOpen(false); } };
    const closeOnEscape = event => { if (event.key === 'Escape') { setOpenPanel(null); setMobileOpen(false); } };
    document.addEventListener('pointerdown', closeOutside);
    document.addEventListener('keydown', closeOnEscape);
    return () => { document.removeEventListener('pointerdown', closeOutside); document.removeEventListener('keydown', closeOnEscape); };
  }, [openPanel, mobileOpen]);
  useEffect(() => { if (openPanel === 'search') searchInputRef.current?.focus(); }, [openPanel]);

  function closeMenus() { setOpenPanel(null); setMobileOpen(false); }
  function togglePanel(panel) { setOpenPanel(value => value === panel ? null : panel); }
  function toggleActionPanel(panel) { setMobileOpen(false); togglePanel(panel); }
  function submitSearch(event) {
    event.preventDefault();
    const query = search.trim();
    closeMenus();
    navigate(catalogHref(query));
  }

  return <header className="site-header store-header" ref={headerRef}>
    <div className="store-header-inner">
      <button type="button" className="store-icon-button store-menu-toggle" aria-label={mobileOpen ? 'Đóng menu' : 'Mở menu'} aria-expanded={mobileOpen} aria-controls="store-navigation" onClick={() => { setMobileOpen(value => !value); setOpenPanel(null); }}><Icon name={mobileOpen ? 'close' : 'menu'} size={25} /></button>
      <Link to={isAdmin ? '/admin' : '/'} className="store-brand" aria-label={isAdmin ? 'Nếp Nhà — Tổng quan quản trị' : 'Nếp Nhà — Trang chủ'} onClick={closeMenus}>Nếp Nhà</Link>
      <nav id="store-navigation" className={`store-navigation${mobileOpen ? ' is-open' : ''}`} aria-label="Điều hướng chính" onClick={event => { if (event.target.closest('a')) closeMenus(); }}>
        {!isAdmin && <>
          <NavLink to="/" end>Trang chủ</NavLink>
          <NavLink to="/sale">Sale &amp; sản phẩm</NavLink>
          {rooms.map(room => <div className="store-nav-group" key={room.key}>
            <button type="button" aria-expanded={openPanel === room.key} aria-controls={`store-menu-${room.key}`} onClick={() => togglePanel(room.key)}>{room.label}<Icon name="chevron" size={15} /></button>
            {openPanel === room.key && <div className="store-nav-popover" id={`store-menu-${room.key}`}><span className="store-popover-label">KHÁM PHÁ {room.label.toUpperCase()}</span><Link to={roomHref(room.slug)}>Tất cả {room.label.toLowerCase()}<Icon name="arrow" size={16} /></Link>{room.links.map(([label, category]) => <Link key={label} to={roomHref(room.slug, category)}>{label}<Icon name="arrow" size={16} /></Link>)}</div>}
          </div>)}
          <Link to="/#home-inspiration">Cảm hứng</Link>
          <NavLink to="/articles">Bài viết</NavLink>
        </>}
        {isStaff && <div className="store-nav-group store-admin-group">
          <button type="button" aria-expanded={openPanel === 'admin'} aria-controls="store-menu-admin" onClick={() => togglePanel('admin')}>Quản trị<Icon name="chevron" size={15} /></button>
          {openPanel === 'admin' && <div className="store-nav-popover store-admin-popover" id="store-menu-admin"><span className="store-popover-label">KHÔNG GIAN QUẢN TRỊ</span>{[...adminLinks, ...(isAdmin ? [['/admin/users', 'Quản lý tài khoản']] : [])].map(([path, label]) => <NavLink key={path} to={path} end={path === '/admin'}>{label}<Icon name="arrow" size={16} /></NavLink>)}</div>}
        </div>}
      </nav>
      <div className="store-actions">
        {!isAdmin && <button type="button" className="store-icon-button" aria-label="Tìm kiếm sản phẩm" aria-expanded={openPanel === 'search'} aria-controls="store-search-panel" onClick={() => toggleActionPanel('search')}><Icon name="search" size={25} /></button>}
        {session?.authenticated ? <button type="button" className="store-icon-button" aria-label="Tài khoản" aria-expanded={openPanel === 'account'} aria-controls="store-account-panel" onClick={() => toggleActionPanel('account')}><Icon name="user" size={25} /></button> : <NavLink to="/login" className="store-icon-button" aria-label="Đăng nhập"><Icon name="user" size={25} /></NavLink>}
        {!isAdmin && <NavLink to="/cart" className="store-icon-button" aria-label="Giỏ hàng"><Icon name="cart" size={27} /></NavLink>}
      </div>
    </div>
    {!isAdmin && openPanel === 'search' && <form className="store-search-panel" id="store-search-panel" role="search" onSubmit={submitSearch}><label htmlFor="store-search">Tìm món đồ cho nhà bạn</label><div><Icon name="search" size={21} /><input id="store-search" ref={searchInputRef} type="search" value={search} onChange={event => setSearch(event.target.value)} placeholder="Thử sofa, bàn ăn, ghế..." maxLength={200} /><button type="submit">Tìm kiếm<Icon name="arrow" size={18} /></button></div></form>}
    {openPanel === 'account' && session?.authenticated && <div className="store-account-panel" id="store-account-panel"><span className="store-popover-label">{isAdmin ? 'TÀI KHOẢN QUẢN TRỊ' : 'TÀI KHOẢN CỦA BẠN'}</span><strong>{session.user.fullName || session.user.username}</strong>{!isAdmin && <><Link to="/account" onClick={closeMenus}>Hồ sơ &amp; sổ địa chỉ</Link><Link to="/orders" onClick={closeMenus}>Đơn hàng của tôi</Link><Link to="/wishlist" onClick={closeMenus}>Yêu thích</Link><Link to="/reviews" onClick={closeMenus}>Đánh giá</Link><Link to="/notifications" onClick={closeMenus}>Thông báo<UnreadBadge key={session.user.id} /></Link></>}<button type="button" disabled={busy} onClick={() => { closeMenus(); onLogout(); }}>{busy ? 'Đang thoát…' : 'Đăng xuất'}</button></div>}
  </header>;
}
