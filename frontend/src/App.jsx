import { useEffect, useState } from 'react';
import { Link, Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import { createApiClient } from './lib/apiClient';
import { SessionContext } from './lib/session';
import { ErrorNotice, Icon, Loading } from './components/ui';
import StoreHeader from './components/StoreHeader';
import CartPage from './features/cart/CartPage';
import HomePage from './features/home/HomePage';
import SalePage from './features/catalog/SalePage';
import ProductDetailPage from './features/catalog/ProductDetailPage';
import LoginPage from './features/auth/LoginPage';
import RegisterPage from './features/auth/RegisterPage';
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
import OrderPrintPage from './features/orders/OrderPrintPage';
import PaymentPage from './features/payments/PaymentPage';
import PaymentAdminPage from './features/payments/PaymentAdminPage';
import PaymentAdminDetailPage from './features/payments/PaymentAdminDetailPage';
import RefundAdminPage from './features/refunds/RefundAdminPage';
import RefundOrderPage from './features/refunds/RefundOrderPage';
import NotificationsPage from './features/notifications/NotificationsPage';
import OperationsPage from './features/operations/OperationsPage';
import AftercarePage from './features/aftercare/AftercarePage';
import ArticlesPage from './features/content/ArticlesPage';
import ArticleDetailPage from './features/content/ArticleDetailPage';
import ContentAdminPage from './features/content/ContentAdminPage';
import WishlistPage from './features/engagement/WishlistPage';
import ReviewsPage from './features/engagement/ReviewsPage';
import ReviewAdminPage from './features/engagement/ReviewAdminPage';
import DashboardAdminPage from './features/analytics/DashboardAdminPage';
import ChatWidget from './features/ai/ChatWidget';

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
  useEffect(() => {
    if (location.hash && session) document.getElementById(location.hash.slice(1))?.scrollIntoView?.();
  }, [location.pathname, location.hash, session]);
  useEffect(() => { document.title = `${location.pathname === '/' ? 'Trang chủ' : location.pathname === '/sale' ? ({ 'phong-khach': 'Phòng khách', 'phong-an': 'Phòng ăn', 'phong-ngu': 'Phòng ngủ' }[new URLSearchParams(location.search).get('room')] || 'Sale & sản phẩm') : location.pathname.startsWith('/products/') ? 'Chi tiết sản phẩm' : location.pathname.startsWith('/articles') ? 'Bài viết' : location.pathname === '/wishlist' ? 'Yêu thích' : location.pathname === '/reviews' ? 'Đánh giá' : location.pathname === '/admin/content' ? 'Quản lý nội dung' : location.pathname === '/admin/reviews' ? 'Kiểm duyệt đánh giá' : location.pathname === '/admin/dashboard' ? 'Bảng điều khiển' : /^\/(?:admin\/)?orders\/\d+\/print$/.test(location.pathname) ? 'Phiếu đơn hàng' : location.pathname === '/notifications' ? 'Thông báo' : location.pathname === '/admin/operations' ? 'Vận hành hệ thống' : /^\/admin\/orders\/\d+\/aftercare$/.test(location.pathname) ? 'Hậu mãi đơn hàng' : /^\/orders\/\d+\/returns$/.test(location.pathname) ? 'Trả hàng' : (location.pathname.startsWith('/admin/refunds') || /^\/admin\/orders\/\d+\/refunds$/.test(location.pathname)) ? 'Quản lý hoàn tiền' : location.pathname.startsWith('/admin/payments') ? 'Quản lý thanh toán' : location.pathname.startsWith('/admin/orders') ? 'Quản lý đơn hàng' : location.pathname.startsWith('/admin/inventory/shipments') ? 'Theo dõi vận đơn' : location.pathname.startsWith('/admin/inventory/dispatch') ? 'Xuất kho giao hàng' : location.pathname.startsWith('/admin/inventory/operations') ? 'Điều chỉnh và chuyển kho' : location.pathname.startsWith('/admin/inventory/receipts') ? 'Nhập kho' : location.pathname.startsWith('/admin/coupons') ? 'Quản lý ưu đãi' : location.pathname === '/register' ? 'Đăng ký' : location.pathname === '/login' ? 'Đăng nhập' : location.pathname === '/checkout' ? 'Đặt hàng' : /^\/orders\/\d+\/payments$/.test(location.pathname) ? 'Thanh toán đơn' : location.pathname.startsWith('/orders') ? 'Đơn hàng' : 'Giỏ hàng'} · Nếp Nhà`; }, [location.pathname, location.search]);
  const isStaff = session?.user?.roles?.some(role => role === 'STAFF' || role === 'ADMIN');
  async function logout() {
    setBusy(true); setError(null);
    try { setSession(await api.logout()); navigate('/cart'); setRevision(value => value + 1); }
    catch (value) { setError(value); }
    finally { setBusy(false); }
  }
  return <SessionContext.Provider value={{ api, session, setSession, isStaff }}>
    <a className="skip-link" href="#main">Đến nội dung chính</a>
    <StoreHeader session={session} isStaff={isStaff} busy={busy} onLogout={logout} />
    <main id="main" className={location.pathname === '/' ? 'page-shell home-shell' : 'page-shell'} tabIndex="-1">
      <ErrorNotice error={error} onRetry={() => setRevision(value => value + 1)} />
      {!session ? !error && <Loading text="Đang kết nối cửa hàng…" /> : <Routes>
        <Route path="/sale" element={<SalePage />} />
        <Route path="/products/:slug" element={<ProductDetailPage />} />
        <Route path="/cart" element={<CartPage key={`${session.user?.id ?? 'guest'}-${revision}`} />} />
        <Route path="/login" element={<LoginPage />} />
        <Route path="/register" element={<RegisterPage />} />
        <Route path="/checkout" element={<PrivatePage session={session}><CheckoutPage key={session.user?.id ?? 'guest'} /></PrivatePage>} />
        <Route path="/orders" element={<PrivatePage session={session}><OrdersPage key={session.user?.id ?? 'guest'} /></PrivatePage>} />
        <Route path="/orders/:id" element={<PrivatePage session={session}><OrderDetailPage key={session.user?.id ?? 'guest'} /></PrivatePage>} />
        <Route path="/orders/:id/print" element={<PrivatePage session={session}><OrderPrintPage key={session.user?.id ?? 'guest'} /></PrivatePage>} />
        <Route path="/orders/:id/payments" element={<PrivatePage session={session}><PaymentPage key={`${session.user?.id ?? 'guest'}-${location.pathname}`} /></PrivatePage>} />
        <Route path="/notifications" element={<PrivatePage session={session}><NotificationsPage key={`${session.user?.id ?? 'guest'}-${location.pathname}`} /></PrivatePage>} />
        <Route path="/admin/operations" element={<StaffPage isStaff={isStaff}><OperationsPage key={`${session.user?.id ?? 'guest'}-${location.pathname}`} /></StaffPage>} />
        <Route path="/orders/:id/returns" element={<PrivatePage session={session}><AftercarePage key={`${session.user?.id ?? 'guest'}-${location.pathname}`} /></PrivatePage>} />
        <Route path="/admin/orders/:id/aftercare" element={<StaffPage isStaff={isStaff}><AftercarePage staff key={`${session.user?.id ?? 'guest'}-${location.pathname}`} /></StaffPage>} />
        <Route path="/admin/coupons" element={<StaffPage isStaff={isStaff}><CouponAdminPage key={session.user?.id ?? 'guest'} /></StaffPage>} />
        <Route path="/admin/orders" element={<StaffPage isStaff={isStaff}><OrderAdminPage key={session.user?.id ?? 'guest'} /></StaffPage>} />
        <Route path="/admin/orders/:id" element={<StaffPage isStaff={isStaff}><OrderAdminDetailPage key={`${session.user?.id ?? 'guest'}-${location.pathname}`} /></StaffPage>} />
        <Route path="/admin/orders/:id/print" element={<StaffPage isStaff={isStaff}><OrderPrintPage staff key={session.user?.id ?? 'guest'} /></StaffPage>} />
        <Route path="/admin/refunds" element={<StaffPage isStaff={isStaff}><RefundAdminPage key={session.user?.id ?? 'guest'} /></StaffPage>} />
        <Route path="/admin/refunds/:id" element={<StaffPage isStaff={isStaff}><RefundOrderPage selected key={`${session.user?.id ?? 'guest'}-${location.pathname}`} /></StaffPage>} />
        <Route path="/admin/orders/:id/refunds" element={<StaffPage isStaff={isStaff}><RefundOrderPage key={`${session.user?.id ?? 'guest'}-${location.pathname}`} /></StaffPage>} />
        <Route path="/admin/payments" element={<StaffPage isStaff={isStaff}><PaymentAdminPage key={session.user?.id ?? 'guest'} /></StaffPage>} />
        <Route path="/admin/payments/:id" element={<StaffPage isStaff={isStaff}><PaymentAdminDetailPage key={`${session.user?.id ?? 'guest'}-${location.pathname}`} /></StaffPage>} />
        <Route path="/admin/inventory/receipts" element={<StaffPage isStaff={isStaff}><InventoryReceiptPage key={session.user?.id ?? 'guest'} /></StaffPage>} />
        <Route path="/admin/inventory/operations" element={<StaffPage isStaff={isStaff}><StockOperationsPage key={session.user?.id ?? 'guest'} /></StaffPage>} />
        <Route path="/admin/inventory/dispatch" element={<StaffPage isStaff={isStaff}><OrderDispatchPage key={session.user?.id ?? 'guest'} /></StaffPage>} />
        <Route path="/admin/inventory/shipments" element={<StaffPage isStaff={isStaff}><ShipmentLifecyclePage key={session.user?.id ?? 'guest'} /></StaffPage>} />
        <Route path="/articles" element={<ArticlesPage />} />
        <Route path="/articles/:slug" element={<ArticleDetailPage />} />
        <Route path="/wishlist" element={<PrivatePage session={session}><WishlistPage key={session.user?.id ?? 'guest'} /></PrivatePage>} />
        <Route path="/reviews" element={<PrivatePage session={session}><ReviewsPage key={session.user?.id ?? 'guest'} /></PrivatePage>} />
        <Route path="/admin/content" element={<StaffPage isStaff={isStaff}><ContentAdminPage key={session.user?.id ?? 'guest'} /></StaffPage>} />
        <Route path="/admin/reviews" element={<StaffPage isStaff={isStaff}><ReviewAdminPage key={session.user?.id ?? 'guest'} /></StaffPage>} />
        <Route path="/admin/dashboard" element={<StaffPage isStaff={isStaff}><DashboardAdminPage key={session.user?.id ?? 'guest'} /></StaffPage>} />
        <Route path="/" element={<HomePage />} />
        <Route path="*" element={<section className="access-state panel"><h1>Chưa tìm thấy trang này</h1><Link to="/cart" className="button">Về giỏ hàng</Link></section>} />
      </Routes>}
    </main>
    {session && <ChatWidget />}
    <footer className="site-footer"><Link to="/" className="footer-brand">Nếp Nhà.</Link><span>Chọn điều giản dị, sống thật an yên.</span><span>Nội thất cho tổ ấm của bạn</span></footer>
  </SessionContext.Provider>;
}
