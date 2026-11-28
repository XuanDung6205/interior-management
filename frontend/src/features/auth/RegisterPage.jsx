import { useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { ErrorNotice, Icon } from '../../components/ui';
import { useCommerce } from '../../lib/session';

export default function RegisterPage() {
  const { api, session } = useCommerce();
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const [showPassword, setShowPassword] = useState(false);
  const navigate = useNavigate();
  const location = useLocation();

  async function submit(event) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const details = {
      fullName: String(form.get('fullName') || '').trim(),
      username: String(form.get('username') || '').trim(),
      email: String(form.get('email') || '').trim(),
      phone: String(form.get('phone') || '').trim(),
      password: String(form.get('password') || ''),
      confirmPassword: String(form.get('confirmPassword') || ''),
    };
    if (details.password !== details.confirmPassword) {
      setError({ message: 'Kiểm tra lại mật khẩu.', fieldErrors: { confirmPassword: 'Mật khẩu nhập lại chưa khớp.' } });
      return;
    }
    setBusy(true); setError(null);
    try {
      const account = await api.register(details);
      navigate('/login', { replace: true, state: { registeredUsername: account.username, returnTo: location.state?.returnTo } });
    } catch (value) { setError(value); }
    finally { setBusy(false); }
  }

  const fields = error?.fieldErrors || {};
  return <section className="login-layout">
    <div className="login-story"><span className="eyebrow">CHÀO MỪNG VỀ NHÀ</span><h1>Tạo tài khoản.<br />Lưu điều bạn yêu.</h1><p>Lưu giỏ hàng và theo dõi đơn mua nội thất của bạn trong một nơi.</p><div className="line-art" aria-hidden="true"><div className="art-window" /><div className="art-lamp" /><div className="art-sofa" /><div className="art-floor" /></div><div className="story-note"><Icon name="leaf" /><span>Bắt đầu một không gian vừa vặn với bạn.</span></div></div>
    <div className="login-panel panel"><span className="eyebrow">NẾP NHÀ / TÀI KHOẢN</span><h2>Đăng ký</h2><p className="muted">Tạo tài khoản khách hàng để mua sắm và theo dõi đơn hàng.</p>
      {session.authenticated ? <div className="notice">Bạn đang đăng nhập với tài khoản <strong>{session.user.username}</strong>. <Link to="/cart">Xem giỏ hàng</Link></div> : <form className="stack-form" onSubmit={submit}>
        <ErrorNotice error={error} />
        <label htmlFor="register-full-name">Họ và tên</label><input id="register-full-name" name="fullName" autoComplete="name" required minLength={2} maxLength={150} disabled={busy} aria-invalid={!!fields.fullName} />{fields.fullName && <span className="field-error">{fields.fullName}</span>}
        <label htmlFor="register-username">Tên đăng nhập</label><input id="register-username" name="username" autoComplete="username" required minLength={3} maxLength={50} pattern="[A-Za-z0-9._-]+" title="Dùng chữ, số, dấu chấm, gạch dưới hoặc gạch ngang" disabled={busy} aria-invalid={!!fields.username} />{fields.username && <span className="field-error">{fields.username}</span>}
        <label htmlFor="register-email">Email</label><input id="register-email" name="email" type="email" autoComplete="email" required maxLength={254} disabled={busy} aria-invalid={!!fields.email} />{fields.email && <span className="field-error">{fields.email}</span>}
        <label htmlFor="register-phone">Số điện thoại (không bắt buộc)</label><input id="register-phone" name="phone" type="tel" autoComplete="tel" maxLength={30} disabled={busy} aria-invalid={!!fields.phone} />{fields.phone && <span className="field-error">{fields.phone}</span>}
        <label htmlFor="register-password">Mật khẩu</label><div className="password-field"><input id="register-password" name="password" type={showPassword ? 'text' : 'password'} autoComplete="new-password" required minLength={8} disabled={busy} aria-invalid={!!fields.password} /><button type="button" className="text-button" aria-label={showPassword ? 'Ẩn mật khẩu' : 'Hiện mật khẩu'} onClick={() => setShowPassword(value => !value)}><Icon name={showPassword ? 'eye-off' : 'eye'} size={20} /></button></div>{fields.password && <span className="field-error">{fields.password}</span>}
        <label htmlFor="register-confirm">Nhập lại mật khẩu</label><input id="register-confirm" name="confirmPassword" type={showPassword ? 'text' : 'password'} autoComplete="new-password" required minLength={8} disabled={busy} aria-invalid={!!fields.confirmPassword} />{fields.confirmPassword && <span className="field-error">{fields.confirmPassword}</span>}
        <button className="button full" disabled={busy}>{busy ? 'Đang tạo tài khoản…' : 'Tạo tài khoản'}<Icon name="arrow" /></button><p className="form-hint"><Icon name="shield" />Sau khi đăng ký, bạn sẽ đăng nhập để tiếp tục mua sắm.</p>
      </form>}
      <Link className="back-link" to="/login" state={{ returnTo: location.state?.returnTo }}>← Đã có tài khoản? Đăng nhập</Link>
    </div>
  </section>;
}
