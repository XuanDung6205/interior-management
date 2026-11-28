import { useState } from 'react';
import { ErrorNotice, Icon } from '../../components/ui';
import { useCommerce } from '../../lib/session';

export default function PasswordChangeForm() {
  const { api } = useCommerce();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const [fieldErrors, setFieldErrors] = useState({});
  const [notice, setNotice] = useState('');

  async function submit(event) {
    event.preventDefault();
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    setBusy(true);
    setError(null);
    setFieldErrors({});
    setNotice('');
    try {
      await api.request('/account/password', {
        method: 'POST',
        body: {
          currentPassword: String(form.get('currentPassword') || ''),
          newPassword: String(form.get('newPassword') || ''),
          confirmPassword: String(form.get('confirmPassword') || ''),
        },
      });
      formElement.reset();
      setNotice('Mật khẩu đã được cập nhật.');
    } catch (value) {
      setError(value);
      setFieldErrors(value.fieldErrors || {});
    } finally {
      setBusy(false);
    }
  }

  return <form className="account-form account-password-form" onSubmit={submit}>
    <p className="muted">Xác nhận mật khẩu hiện tại trước khi đặt mật khẩu mới.</p>
    <ErrorNotice error={error} />
    {notice && <div className="notice success" role="status">{notice}</div>}
    <div className="account-password-fields">
      <div className="form-field">
        <label htmlFor="account-current-password">Mật khẩu hiện tại</label>
        <input id="account-current-password" name="currentPassword" type="password" autoComplete="current-password"
          required maxLength={72} disabled={busy} aria-invalid={!!fieldErrors.currentPassword} />
        {fieldErrors.currentPassword && <span className="field-error">{fieldErrors.currentPassword}</span>}
      </div>
      <div className="form-field">
        <label htmlFor="account-new-password">Mật khẩu mới</label>
        <input id="account-new-password" name="newPassword" type="password" autoComplete="new-password"
          required minLength={8} maxLength={72} disabled={busy} aria-invalid={!!fieldErrors.newPassword} />
        {fieldErrors.newPassword && <span className="field-error">{fieldErrors.newPassword}</span>}
      </div>
      <div className="form-field">
        <label htmlFor="account-confirm-password">Nhập lại mật khẩu mới</label>
        <input id="account-confirm-password" name="confirmPassword" type="password" autoComplete="new-password"
          required minLength={8} maxLength={72} disabled={busy} aria-invalid={!!fieldErrors.confirmPassword} />
        {fieldErrors.confirmPassword && <span className="field-error">{fieldErrors.confirmPassword}</span>}
      </div>
    </div>
    <button className="button" disabled={busy}>
      {busy ? 'Đang cập nhật…' : 'Đổi mật khẩu'}<Icon name="arrow" size={18} />
    </button>
  </form>;
}
