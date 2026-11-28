import PasswordChangeForm from './PasswordChangeForm';

export default function AccountPasswordPage() {
  return <section className="account-page">
    <header className="page-heading">
      <div>
        <span className="eyebrow">BẢO MẬT TÀI KHOẢN</span>
        <h1>Đổi mật khẩu</h1>
        <p>Giữ an toàn cho tài khoản bằng mật khẩu mạnh và riêng tư.</p>
      </div>
    </header>
    <section className="panel account-panel">
      <div className="panel-heading"><h2>Đặt mật khẩu mới</h2></div>
      <div className="account-panel-body"><PasswordChangeForm /></div>
    </section>
  </section>;
}
