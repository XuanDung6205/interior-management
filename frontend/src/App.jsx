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
import OrderDispatchPage from './features/inventory/OrderDispatchPage';
import ShipmentLifecyclePage from './features/inventory/ShipmentLifecyclePage';
import CheckoutPage from './features/checkout/CheckoutPage';
import OrdersPage from './features/orders/OrdersPage';
import OrderDetailPage from './features/orders/OrderDetailPage';
import OrderAdminPage from './features/orders/OrderAdminPage';
import OrderAdminDetailPage from './features/orders/OrderAdminDetailPage';
import PaymentPage from './features/payments/PaymentPage';
import PaymentAdminPage from './features/payments/PaymentAdminPage';
import PaymentAdminDetailPage from './features/payments/PaymentAdminDetailPage';
import RefundAdminPage from './features/refunds/RefundAdminPage';
import RefundOrderPage from './features/refunds/RefundOrderPage';

function PrivatePage({ session, children }) {
  const location = useLocation();
  return session.authenticated ? children : <section className="access-state panel"><Icon name="shield" size={40} /><h1>Đăng nhập để tiếp tục</h1><p>Giỏ hàng khách vẫn được giữ lại và sẽ gộp vào tài khoản khi đăng nhập.</p><Link to="/login" state={{ returnTo: location.pathname + location.search }} className="button">Đăng nhập</Link></section>;
}
function StaffPage({ isStaff, children }) {
  const location = useLocation();
  return isStaff ? children : <section className="access-state panel"><Icon name="shield" size={40} /><h1>Khu vực dành cho nhân viên</h1><p>Bạn cần tài khoản nhân viên hoặc quản trị viên để sử dụng khu vực này.</p><Link to="/login" state={{ returnTo: location.pathname + location.search }} className="button">Đăng nhập tài khoản khác</Link></section>;
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
  useEffect(() => { document.title = `${(location.pathname.startsWith('/admin/refunds') || /^\/admin\/orders\/\d+\/refunds$/.test(location.pathname)) ? 'Quản lý hoàn tiền' : location.pathname.startsWith('/admin/payments') ? 'Quản lý thanh toán' : location.pathname.startsWith('/admin/orders') ? 'Quản lý đơn hàng' : location.pathname.startsWith('/admin/inventory/shipments') ? 'Theo dõi vận đơn' : location.pathname.startsWith('/admin/inventory/dispatch') ? 'Xuất kho giao hàng' : location.pathname.startsWith('/admin/inventory/operations') ? 'Điều chỉnh và chuyển kho' : location.pathname.startsWith('/admin/inventory/receipts') ? 'Nhập kho' : location.pathname.startsWith('/admin/coupons') ? 'Quản lý ưu đãi' : location.pathname === '/login' ? 'Đăng nhập' : location.pathname === '/checkout' ? 'Đặt hàng' : /^\/orders\/\d+\/payments$/.test(location.pathname) ? 'Thanh toán đơn' : location.pathname.startsWith('/orders') ? 'Đơn hàng' : 'Giỏ hàng'} · Nếp Nhà`; }, [location.pathname]);
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
      <nav className={isStaff ? 'main-nav staff-nav' : 'main-nav'} aria-label="Điều hướng chính"><NavLink to="/cart"><Icon name="bag" />Giỏ hàng</NavLink>{session?.authenticated && <NavLink to="/orders"><Icon name="orders" />Đơn hàng</NavLink>}{isStaff && <><NavLink to="/admin/orders"><Icon name="shield" />Quản lý đơn hàng</NavLink><NavLink to="/admin/payments"><Icon name="ticket" />Khoản thu</NavLink><NavLink to="/admin/refunds"><Icon name="ticket" />Hoàn tiền</NavLink><NavLink to="/admin/coupons"><Icon name="ticket" />Quản lý ưu đãi</NavLink><NavLink to="/admin/inventory/receipts"><Icon name="box" />Nhập kho</NavLink><NavLink to="/admin/inventory/operations"><Icon name="box" />Điều chuyển</NavLink><NavLink to="/admin/inventory/dispatch"><Icon name="orders" />Xuất kho</NavLink><NavLink to="/admin/inventory/shipments"><Icon name="orders" />Vận đơn</NavLink></>}</nav>
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
        <Route path="/orders/:id/payments" element={<PrivatePage session={session}><PaymentPage key={`${session.user?.id ?? 'guest'}-${location.pathname}`} /></PrivatePage>} />
        <Route path="/admin/coupons" element={<StaffPage isStaff={isStaff}><CouponAdminPage key={session.user?.id ?? 'guest'} /></StaffPage>} />
        <Route path="/admin/orders" element={<StaffPage isStaff={isStaff}><OrderAdminPage key={session.user?.id ?? 'guest'} /></StaffPage>} />
        <Route path="/admin/orders/:id" element={<StaffPage isStaff={isStaff}><OrderAdminDetailPage key={`${session.user?.id ?? 'guest'}-${location.pathname}`} /></StaffPage>} />
        <Route path="/admin/refunds" element={<StaffPage isStaff={isStaff}><RefundAdminPage key={session.user?.id ?? 'guest'} /></StaffPage>} />
        <Route path="/admin/refunds/:id" element={<StaffPage isStaff={isStaff}><RefundOrderPage selected key={`${session.user?.id ?? 'guest'}-${location.pathname}`} /></StaffPage>} />
        <Route path="/admin/orders/:id/refunds" element={<StaffPage isStaff={isStaff}><RefundOrderPage key={`${session.user?.id ?? 'guest'}-${location.pathname}`} /></StaffPage>} />
        <Route path="/admin/payments" element={<StaffPage isStaff={isStaff}><PaymentAdminPage key={session.user?.id ?? 'guest'} /></StaffPage>} />
        <Route path="/admin/payments/:id" element={<StaffPage isStaff={isStaff}><PaymentAdminDetailPage key={`${session.user?.id ?? 'guest'}-${location.pathname}`} /></StaffPage>} />
        <Route path="/admin/inventory/receipts" element={<StaffPage isStaff={isStaff}><InventoryReceiptPage key={session.user?.id ?? 'guest'} /></StaffPage>} />
        <Route path="/admin/inventory/operations" element={<StaffPage isStaff={isStaff}><StockOperationsPage key={session.user?.id ?? 'guest'} /></StaffPage>} />
        <Route path="/admin/inventory/dispatch" element={<StaffPage isStaff={isStaff}><OrderDispatchPage key={session.user?.id ?? 'guest'} /></StaffPage>} />
        <Route path="/admin/inventory/shipments" element={<StaffPage isStaff={isStaff}><ShipmentLifecyclePage key={session.user?.id ?? 'guest'} /></StaffPage>} />
        <Route path="/" element={<Navigate to="/cart" replace />} />
        <Route path="*" element={<section className="access-state panel"><h1>Chưa tìm thấy trang này</h1><Link to="/cart" className="button">Về giỏ hàng</Link></section>} />
      </Routes>}
    </main>
    <footer className="site-footer"><Link to="/cart" className="footer-brand">Nếp Nhà.</Link><span>Chọn điều giản dị, sống thật an yên.</span><span>Nội thất cho tổ ấm của bạn</span></footer>
  </SessionContext.Provider>;
}
