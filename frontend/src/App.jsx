import { useEffect, useState } from 'react';
import { Link, NavLink, Navigate, Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import { createApiClient } from './lib/apiClient';
import { SessionContext } from './lib/session';
import { ErrorNotice, Icon, Loading } from './components/ui';
import CartPage from './features/cart/CartPage';
import LoginPage from './features/auth/LoginPage';
import CouponAdminPage from './features/promotions/CouponAdminPage';
import InventoryReceiptPage from './features/inventory/InventoryReceiptPage';
import StockOperationsPage from './features/inventory/StockOperationsPage';
import CheckoutPage from './features/checkout/CheckoutPage';
import OrdersPage from './features/orders/OrdersPage';
import OrderDetailPage from './features/orders/OrderDetailPage';

function PrivatePage({ session, children }) {
  const location = useLocation();
  return session.authenticated ? children : <section className="access-state panel"><Icon name="shield" size={40} /><h1>Đăng nhập để tiếp tục</h1><p>Giỏ hàng khách vẫn được giữ lại và sẽ gộp vào tài khoản khi đăng nhập.</p><Link to="/login" state={{ returnTo: location.pathname + location.search }} className="button">Đăng nhập</Link></section>;
}

export default function App() {
  const [api] = useState(() => createApiClient());
  const [session, setSession] = useState(null);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const [revision, setRevision] = useState(0);
  const location = useLocation();
  const navigate = useNavigate();
  useEffect(() => {
    let active = true;
    setError(null);
    api.request('/session').then(value => { if (active) setSession(value); }).catch(value => { if (active) setError(value); });
    return () => { active = false; };
  }, [api, revision]);
  useEffect(() => { document.title = `${location.pathname.startsWith('/admin/inventory/operations') ? 'Điều chỉnh và chuyển kho' : location.pathname.startsWith('/admin/inventory') ? 'Nhập kho' : location.pathname.startsWith('/admin') ? 'Quản lý ưu đãi' : location.pathname === '/login' ? 'Đăng nhập' : location.pathname === '/checkout' ? 'Đặt hàng' : location.pathname.startsWith('/orders') ? 'Đơn hàng' : 'Giỏ hàng'} · Nếp Nhà`; }, [location.pathname]);
  const isStaff = session?.user?.roles?.some(role => role === 'STAFF' || role === 'ADMIN');
  async function logout() {
    setBusy(true); setError(null);
    try { setSession(await api.logout()); navigate('/cart'); setRevision(value => value + 1); }
    catch (value) { setError(value); }
    finally { setBusy(false); }
  }
  return <SessionContext.Provider value={{ api, session, setSession, isStaff }}>
    <a className="skip-link" href="#main">Đến nội dung chính</a>
    <div className="announcement">Một chút tinh tế. Một tổ ấm riêng bạn.</div>
    <header className="site-header"><div className="header-inner">
      <Link to="/cart" className="brand" aria-label="Nếp Nhà — Trang giỏ hàng"><span className="brand-mark"><Icon name="leaf" size={27} /></span><span>Nếp Nhà<small>NỘI THẤT & KHÔNG GIAN SỐNG</small></span></Link>
      <nav className="main-nav" aria-label="Điều hướng chính"><NavLink to="/cart"><Icon name="bag" />Giỏ hàng</NavLink>{session?.authenticated && <NavLink to="/orders"><Icon name="orders" />Đơn hàng</NavLink>}{isStaff && <><NavLink to="/admin/coupons"><Icon name="ticket" />Quản lý ưu đãi</NavLink><NavLink to="/admin/inventory/receipts"><Icon name="box" />Nhập kho</NavLink><NavLink to="/admin/inventory/operations"><Icon name="box" />Điều chuyển</NavLink></>}</nav>
      <div className="account-nav">{session?.authenticated ? <><span className="account-name"><Icon name="user" />{session.user.fullName || session.user.username}</span><button className="text-button" disabled={busy} onClick={logout}>{busy ? 'Đang thoát…' : 'Đăng xuất'}</button></> : <NavLink to="/login" className="login-link"><Icon name="user" />Đăng nhập</NavLink>}</div>
    </div></header>
    <main id="main" className="page-shell" tabIndex="-1">
      <ErrorNotice error={error} onRetry={() => setRevision(value => value + 1)} />
      {!session ? !error && <Loading text="Đang kết nối cửa hàng…" /> : <Routes>
        <Route path="/cart" element={<CartPage key={`${session.user?.id ?? 'guest'}-${revision}`} />} />
        <Route path="/login" element={<LoginPage />} />
        <Route path="/checkout" element={<PrivatePage session={session}><CheckoutPage key={session.user?.id ?? 'guest'} /></PrivatePage>} />
        <Route path="/orders" element={<PrivatePage session={session}><OrdersPage key={session.user?.id ?? 'guest'} /></PrivatePage>} />
        <Route path="/orders/:id" element={<PrivatePage session={session}><OrderDetailPage key={session.user?.id ?? 'guest'} /></PrivatePage>} />
        <Route path="/admin/coupons" element={isStaff ? <CouponAdminPage /> : <section className="access-state panel"><Icon name="shield" size={40} /><h1>Khu vực dành cho nhân viên</h1><p>Bạn cần tài khoản nhân viên hoặc quản trị viên để quản lý ưu đãi.</p><Link to="/login" className="button">Đăng nhập tài khoản khác</Link></section>} />
        <Route path="/admin/inventory/receipts" element={isStaff ? <InventoryReceiptPage key={session.user.id} /> : <section className="access-state panel"><Icon name="shield" size={40} /><h1>Khu vực dành cho nhân viên</h1><p>Bạn cần tài khoản nhân viên hoặc quản trị viên để nhập kho.</p><Link to="/login" className="button">Đăng nhập tài khoản khác</Link></section>} />
        <Route path="/admin/inventory/operations" element={isStaff ? <StockOperationsPage key={session.user.id} /> : <section className="access-state panel"><Icon name="shield" size={40} /><h1>Khu vực dành cho nhân viên</h1><p>Bạn cần tài khoản nhân viên hoặc quản trị viên để điều chuyển kho.</p><Link to="/login" className="button">Đăng nhập tài khoản khác</Link></section>} />
        <Route path="/" element={<Navigate to="/cart" replace />} />
        <Route path="*" element={<section className="access-state panel"><h1>Chưa tìm thấy trang này</h1><Link to="/cart" className="button">Về giỏ hàng</Link></section>} />
      </Routes>}
    </main>
    <footer className="site-footer"><Link to="/cart" className="footer-brand">Nếp Nhà.</Link><span>Chọn điều giản dị, sống thật an yên.</span><span>Nội thất cho tổ ấm của bạn</span></footer>
  </SessionContext.Provider>;
}
