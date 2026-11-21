import { useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { useCommerce } from '../../lib/session';
import { ErrorNotice, Icon } from '../../components/ui';

export default function LoginPage() {
  const { api, session, setSession } = useCommerce();
  const [error, setError] = useState(null);
  const [mergeError, setMergeError] = useState(null);
  const [busy, setBusy] = useState(false);
  const [showPassword, setShowPassword] = useState(false);
  const navigate = useNavigate();
  const location = useLocation();
  const requested = location.state?.returnTo;
  const returnTo = typeof requested === 'string' && /^\/(?:checkout|notifications|orders(?:\/\d+(?:\/(?:payments|returns|print))?)?|admin\/(?:(?:orders(?:\/\d+(?:\/(?:refunds|aftercare|print))?)?|payments(?:\/\d+)?|refunds(?:\/\d+)?)|operations|coupons|inventory\/(?:receipts|operations|dispatch|shipments)))(?:\?.*)?$/.test(requested) ? requested : '/cart';
  async function merge() {
    try { await api.mergeCart(); navigate(returnTo); }
    catch (value) { setMergeError(value); }
  }
  async function submit(event) {
    event.preventDefault(); setBusy(true); setError(null); setMergeError(null);
    const form = new FormData(event.currentTarget);
    try { setSession(await api.login(form.get('username').trim(), form.get('password'))); await merge(); }
    catch (value) { setError(value); }
    finally { setBusy(false); }
  }
  return <section className="login-layout">
    <div className="login-story"><span className="eyebrow">CHÀO MỪNG VỀ NHÀ</span><h1>Một tài khoản.<br />Cả tổ ấm của bạn.</h1><p>Giữ những lựa chọn yêu thích trong giỏ và tiếp tục hoàn thiện không gian sống theo cách của riêng mình.</p><div className="line-art" aria-hidden="true"><div className="art-window" /><div className="art-lamp" /><div className="art-sofa" /><div className="art-floor" /></div><div className="story-note"><Icon name="leaf" /><span>Không gian đẹp bắt đầu từ những điều giản dị.</span></div></div>
    <div className="login-panel panel"><span className="eyebrow">NẾP NHÀ / TÀI KHOẢN</span><h2>Đăng nhập</h2><p className="muted">Tiếp tục với tài khoản cửa hàng của bạn.</p>
      {mergeError ? <><div className="notice">Đã đăng nhập thành công. Giỏ khách chưa gộp được; các sản phẩm vẫn được giữ lại.</div><ErrorNotice error={mergeError} /><button className="button full" disabled={busy} onClick={async () => { setBusy(true); await merge(); setBusy(false); }}>Thử gộp lại</button><Link to="/cart" className="text-button block-link">Xem giỏ tài khoản để điều chỉnh</Link></> : session.authenticated ? <div className="notice">Bạn đang đăng nhập với tài khoản <strong>{session.user.username}</strong>. Có thể nhập tài khoản khác bên dưới.</div> : null}
      {!mergeError && <form onSubmit={submit} className="stack-form"><ErrorNotice error={error} /><label htmlFor="username">Tên đăng nhập</label><input id="username" name="username" autoComplete="username" required disabled={busy} aria-invalid={!!error?.fieldErrors?.username} aria-describedby={error?.fieldErrors?.username ? 'username-error' : undefined} />{error?.fieldErrors?.username && <span className="field-error" id="username-error">{error.fieldErrors.username}</span>}<label htmlFor="password">Mật khẩu</label><div className="password-field"><input id="password" name="password" type={showPassword ? 'text' : 'password'} autoComplete="current-password" required disabled={busy} /><button type="button" className="text-button" aria-label={showPassword ? 'Ẩn mật khẩu' : 'Hiện mật khẩu'} onClick={() => setShowPassword(value => !value)}>{showPassword ? 'Ẩn' : 'Hiện'}</button></div><button className="button full" disabled={busy}>{busy ? 'Đang đăng nhập và gộp giỏ…' : 'Đăng nhập'}<Icon name="arrow" /></button><p className="form-hint"><Icon name="shield" />Giỏ khách được tự động gộp sau khi đăng nhập.</p></form>}
      <Link className="back-link" to="/cart">← Trở về giỏ hàng</Link>
    </div>
  </section>;
}
