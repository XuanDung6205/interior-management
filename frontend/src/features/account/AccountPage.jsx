import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { ErrorNotice, Icon, Loading } from '../../components/ui';
import { useCommerce } from '../../lib/session';
import PasswordChangeForm from './PasswordChangeForm';
import './account.css';

const accountShortcuts = [['/orders', 'orders', 'Đơn hàng', 'Theo dõi, thanh toán, đổi trả'], ['/notifications', 'bell', 'Hộp thư', 'Cập nhật từ cửa hàng'], ['/wishlist', 'heart', 'Món đã lưu', 'Những món bạn yêu thích'], ['/cart', 'cart', 'Giỏ đang chọn', 'Tiếp tục mua sắm']];

const emptyAddress = () => ({
  label: '',
  recipientName: '',
  phone: '',
  addressLine1: '',
  addressLine2: '',
  wardName: '',
  districtName: '',
  provinceName: '',
  postalCode: '',
  countryCode: 'VN',
});

const addressFields = [
  ['label', 'Nhãn địa chỉ', { maxLength: 80, placeholder: 'Nhà riêng, Công ty…' }],
  ['recipientName', 'Người nhận', { required: true, maxLength: 150, autoComplete: 'name' }],
  ['phone', 'Số điện thoại', { required: true, maxLength: 30, type: 'tel', autoComplete: 'tel' }],
  ['addressLine1', 'Địa chỉ cụ thể', { required: true, maxLength: 255, autoComplete: 'address-line1' }],
  ['addressLine2', 'Địa chỉ bổ sung', { maxLength: 255, autoComplete: 'address-line2' }],
  ['wardName', 'Phường / Xã', { maxLength: 120 }],
  ['districtName', 'Quận / Huyện', { maxLength: 120 }],
  ['provinceName', 'Tỉnh / Thành phố', { maxLength: 120, autoComplete: 'address-level1' }],
  ['postalCode', 'Mã bưu chính', { maxLength: 20, autoComplete: 'postal-code' }],
  ['countryCode', 'Mã quốc gia', { required: true, maxLength: 2, pattern: '[A-Za-z]{2}', title: 'Nhập mã quốc gia 2 chữ cái, ví dụ VN.' }],
];

export default function AccountPage() {
  const { api, session, setSession } = useCommerce();
  const [activeTab, setActiveTab] = useState('profile');

  const [profile, setProfile] = useState(null);
  const [profileLoading, setProfileLoading] = useState(true);
  const [profileBusy, setProfileBusy] = useState(false);
  const [profileError, setProfileError] = useState(null);
  const [profileSaveError, setProfileSaveError] = useState(null);
  const [profileFields, setProfileFields] = useState({});
  const [profileNotice, setProfileNotice] = useState('');
  const [profileRevision, setProfileRevision] = useState(0);

  const [addresses, setAddresses] = useState([]);
  const [addressesLoaded, setAddressesLoaded] = useState(false);
  const [addressesLoading, setAddressesLoading] = useState(false);
  const [addressBusy, setAddressBusy] = useState(false);
  const [addressError, setAddressError] = useState(null);
  const [addressActionError, setAddressActionError] = useState(null);
  const [addressFormError, setAddressFormError] = useState(null);
  const [addressFieldsError, setAddressFieldsError] = useState({});
  const [addressNotice, setAddressNotice] = useState('');
  const [reloadAddresses, setReloadAddresses] = useState(0);
  const [addressEditorOpen, setAddressEditorOpen] = useState(false);
  const [editingAddress, setEditingAddress] = useState(null);
  const [addressForm, setAddressForm] = useState(emptyAddress);
  const [confirmingDelete, setConfirmingDelete] = useState(null);

  useEffect(() => {
    const controller = new AbortController();
    setProfileLoading(true);
    setProfileError(null);
    api.request('/account/profile', { signal: controller.signal })
      .then(setProfile)
      .catch(error => { if (error.name !== 'AbortError') setProfileError(error); })
      .finally(() => { if (!controller.signal.aborted) setProfileLoading(false); });
    return () => controller.abort();
  }, [api, profileRevision]);

  useEffect(() => {
    if (activeTab !== 'addresses') return undefined;
    const controller = new AbortController();
    setAddressesLoading(true);
    setAddressError(null);
    api.request('/account/addresses', { signal: controller.signal })
      .then(result => {
        if (!Array.isArray(result)) throw new Error('Không thể tải danh sách địa chỉ.');
        setAddresses(result);
        setAddressesLoaded(true);
      })
      .catch(error => { if (error.name !== 'AbortError') setAddressError(error); })
      .finally(() => { if (!controller.signal.aborted) setAddressesLoading(false); });
    return () => controller.abort();
  }, [activeTab, api, reloadAddresses]);

  const user = session.user;
  const roleNames = user.roles?.join(', ') || 'CUSTOMER';
  const addressText = address => [
    address.addressLine1,
    address.addressLine2,
    address.wardName,
    address.districtName,
    address.provinceName,
    address.postalCode,
    address.countryCode === 'VN' ? 'Việt Nam' : address.countryCode,
  ].filter(Boolean).join(', ');

  async function saveProfile(event) {
    event.preventDefault();
    setProfileBusy(true);
    setProfileSaveError(null);
    setProfileFields({});
    setProfileNotice('');
    const form = new FormData(event.currentTarget);
    try {
      const saved = await api.request('/account/profile', {
        method: 'PUT',
        body: {
          fullName: String(form.get('fullName') || '').trim(),
          email: String(form.get('email') || '').trim(),
          phone: String(form.get('phone') || '').trim(),
        },
      });
      setProfile(saved);
      setSession(current => ({ ...current, user: { ...current.user, fullName: saved.fullName } }));
      setProfileNotice('Đã cập nhật hồ sơ.');
    } catch (error) {
      setProfileSaveError(error);
      setProfileFields(error.fieldErrors || {});
    } finally {
      setProfileBusy(false);
    }
  }

  function openAddressForm(address = null) {
    setEditingAddress(address);
    setAddressForm(address ? {
      label: address.label || '',
      recipientName: address.recipientName || '',
      phone: address.phone || '',
      addressLine1: address.addressLine1 || '',
      addressLine2: address.addressLine2 || '',
      wardName: address.wardName || '',
      districtName: address.districtName || '',
      provinceName: address.provinceName || '',
      postalCode: address.postalCode || '',
      countryCode: address.countryCode || 'VN',
    } : emptyAddress());
    setAddressFormError(null);
    setAddressFieldsError({});
    setAddressNotice('');
    setConfirmingDelete(null);
    setAddressEditorOpen(true);
  }

  async function saveAddress(event) {
    event.preventDefault();
    setAddressBusy(true);
    setAddressFormError(null);
    setAddressActionError(null);
    setAddressFieldsError({});
    setAddressError(null);
    setAddressNotice('');
    const method = editingAddress ? 'PUT' : 'POST';
    const path = editingAddress ? `/account/addresses/${editingAddress.id}` : '/account/addresses';
    try {
      await api.request(path, { method, body: addressForm });
      setAddressNotice(editingAddress ? 'Đã cập nhật địa chỉ.' : 'Đã thêm địa chỉ.');
      setAddressEditorOpen(false);
      setEditingAddress(null);
      setReloadAddresses(value => value + 1);
    } catch (error) {
      setAddressFormError(error);
      setAddressFieldsError(error.fieldErrors || {});
    } finally {
      setAddressBusy(false);
    }
  }

  async function makeDefault(address) {
    setAddressBusy(true);
    setAddressActionError(null);
    setAddressNotice('');
    try {
      await api.request(`/account/addresses/${address.id}/default`, { method: 'PATCH' });
      setAddressNotice(`Đã đặt "${address.label || 'địa chỉ nhận hàng'}" làm mặc định.`);
      setReloadAddresses(value => value + 1);
    } catch (error) {
      setAddressActionError(error);
    } finally {
      setAddressBusy(false);
    }
  }

  async function deleteAddress(address) {
    setAddressBusy(true);
    setAddressActionError(null);
    setAddressNotice('');
    try {
      await api.request(`/account/addresses/${address.id}`, { method: 'DELETE' });
      setAddressNotice(`Đã xóa "${address.label || 'địa chỉ nhận hàng'}".`);
      setConfirmingDelete(null);
      setReloadAddresses(value => value + 1);
    } catch (error) {
      setAddressActionError(error);
    } finally {
      setAddressBusy(false);
    }
  }

  return <section className="account-page">
    <header className="page-heading">
      <div>
        <span className="eyebrow">KHÔNG GIAN CỦA BẠN</span>
        <h1>Tài khoản của bạn</h1>
        <p>Chỉnh sửa thông tin cá nhân và quản lý địa chỉ nhận hàng.</p>
      </div>
    </header>
    <div className="account-overview">
      <div className="account-identity panel">
        <span className="account-avatar" aria-hidden="true">{(user.fullName || user.username || '?').trim().charAt(0).toUpperCase()}</span>
        <div><strong>{user.fullName || user.username}</strong><span>@{user.username} · {roleNames}</span></div>
      </div>
      <nav className="account-shortcuts" aria-label="Lối tắt tài khoản">
        {accountShortcuts.map(([to, icon, title, detail]) => <Link key={to} to={to} className="account-shortcut panel"><span className="account-shortcut-icon"><Icon name={icon} size={22} /></span><span><strong>{title}</strong><small>{detail}</small></span></Link>)}
      </nav>
    </div>
    <div className="account-tabs" role="tablist" aria-label="Khu vực tài khoản">
      <button id="account-profile-tab" type="button" role="tab" aria-selected={activeTab === 'profile'} aria-controls="account-profile-panel" className={`button ${activeTab === 'profile' ? '' : 'secondary'}`} onClick={() => setActiveTab('profile')}>Hồ sơ</button>
      <button id="account-addresses-tab" type="button" role="tab" aria-selected={activeTab === 'addresses'} aria-controls="account-addresses-panel" className={`button ${activeTab === 'addresses' ? '' : 'secondary'}`} onClick={() => setActiveTab('addresses')}>Sổ địa chỉ</button>
      <button id="account-password-tab" type="button" role="tab" aria-selected={activeTab === 'password'} aria-controls="account-password-panel" className={`button ${activeTab === 'password' ? '' : 'secondary'}`} onClick={() => setActiveTab('password')}>Đổi mật khẩu</button>
    </div>
    {activeTab === 'profile'
      ? <section id="account-profile-panel" className="panel account-panel" role="tabpanel" aria-labelledby="account-profile-tab">
        <div className="panel-heading"><h2>Thông tin hồ sơ</h2></div>
        <div className="account-panel-body">
          <ErrorNotice error={profileError} onRetry={() => setProfileRevision(value => value + 1)} />
          <ErrorNotice error={profileSaveError} />
          {profileNotice && <div className="notice success" role="status">{profileNotice}</div>}
          {profileLoading ? <Loading text="Đang tải hồ sơ…" /> : profile && <form key={`${profile.fullName}|${profile.email}|${profile.phone || ''}`} className="account-form" onSubmit={saveProfile}>
            <div className="account-profile-grid">
              <div className="form-field">
                <label htmlFor="account-full-name">Họ và tên</label>
                <input id="account-full-name" name="fullName" autoComplete="name" required minLength={2} maxLength={150}
                  defaultValue={profile.fullName || ''} disabled={profileBusy} aria-invalid={!!profileFields.fullName} />
                {profileFields.fullName && <span className="field-error">{profileFields.fullName}</span>}
              </div>
              <div className="form-field">
                <label htmlFor="account-email">Email</label>
                <input id="account-email" name="email" type="email" autoComplete="email" required maxLength={254}
                  defaultValue={profile.email || ''} disabled={profileBusy} aria-invalid={!!profileFields.email} />
                {profileFields.email && <span className="field-error">{profileFields.email}</span>}
              </div>
              <div className="form-field">
                <label htmlFor="account-phone">Số điện thoại</label>
                <input id="account-phone" name="phone" type="tel" autoComplete="tel" maxLength={30}
                  defaultValue={profile.phone || ''} disabled={profileBusy} aria-invalid={!!profileFields.phone} />
                {profileFields.phone && <span className="field-error">{profileFields.phone}</span>}
              </div>
              <div className="account-readonly">
                <span className="eyebrow">TÊN ĐĂNG NHẬP</span><strong>{profile.username}</strong>
                <span className="eyebrow">VAI TRÒ</span><strong>{roleNames}</strong>
              </div>
            </div>
            <button className="button" disabled={profileBusy}>{profileBusy ? 'Đang lưu…' : 'Lưu hồ sơ'}<Icon name="arrow" size={18} /></button>
          </form>}
        </div>
      </section>
      : activeTab === 'addresses' ? <section id="account-addresses-panel" className="panel account-panel" role="tabpanel" aria-labelledby="account-addresses-tab">
        <div className="panel-heading account-addresses-heading">
          <h2>Sổ địa chỉ</h2>
          <button type="button" className="button" disabled={addressBusy || addressEditorOpen} onClick={() => openAddressForm()}>
            <Icon name="plus" size={17} />Thêm địa chỉ
          </button>
        </div>
        <div className="account-panel-body">
          <ErrorNotice error={addressError} onRetry={() => setReloadAddresses(value => value + 1)} />
          <ErrorNotice error={addressActionError} />
          {addressNotice && <div className="notice success" role="status">{addressNotice}</div>}
          {addressEditorOpen && <form className="account-address-editor" onSubmit={saveAddress} aria-label={editingAddress ? 'Sửa địa chỉ' : 'Thêm địa chỉ'}>
            <div className="account-address-editor-heading">
              <div><span className="eyebrow">THÔNG TIN GIAO HÀNG</span><h3>{editingAddress ? 'Chỉnh sửa địa chỉ' : 'Thêm địa chỉ mới'}</h3></div>
              <button type="button" className="icon-button" aria-label="Đóng biểu mẫu địa chỉ" disabled={addressBusy} onClick={() => setAddressEditorOpen(false)}><Icon name="close" /></button>
            </div>
            <ErrorNotice error={addressFormError} />
            <div className="account-address-form-grid">
              {addressFields.map(([key, label, options]) => <div className="form-field" key={key}>
                <label htmlFor={`address-${key}`}>{label}</label>
                <input id={`address-${key}`} name={key} value={addressForm[key]}
                  onChange={event => setAddressForm(current => ({ ...current, [key]: event.target.value }))}
                  disabled={addressBusy} aria-invalid={!!addressFieldsError[key]} {...options} />
                {addressFieldsError[key] && <span className="field-error">{addressFieldsError[key]}</span>}
              </div>)}
            </div>
            <div className="editor-actions">
              <button type="button" className="button secondary" disabled={addressBusy} onClick={() => setAddressEditorOpen(false)}>Hủy</button>
              <button className="button" disabled={addressBusy}>{addressBusy ? 'Đang lưu…' : editingAddress ? 'Lưu địa chỉ' : 'Thêm địa chỉ'}<Icon name="arrow" size={18} /></button>
            </div>
          </form>}
          {addressesLoading && <Loading text="Đang tải địa chỉ…" />}
          {!addressesLoading && !addressError && addressesLoaded && addresses.length === 0 && <p className="muted">Bạn chưa lưu địa chỉ nhận hàng nào.</p>}
          {!addressesLoading && !addressError && addresses.length > 0 && <div className="account-address-list">
            {addresses.map(address => <article className="account-address" key={address.id}>
              <div className="account-address-heading">
                <h3>{address.label || 'Địa chỉ nhận hàng'}</h3>
                {address.isDefault && <span className="status-pill active">Mặc định</span>}
              </div>
              <p><strong>{address.recipientName}</strong> · {address.phone}</p>
              <p className="muted">{addressText(address)}</p>
              <div className="account-address-actions">
                {!address.isDefault && <button type="button" className="text-button" disabled={addressBusy} onClick={() => makeDefault(address)}>Đặt làm mặc định</button>}
                <button type="button" className="text-button" disabled={addressBusy || addressEditorOpen} onClick={() => openAddressForm(address)}>Sửa</button>
                <button type="button" className="text-button destructive" disabled={addressBusy} onClick={() => setConfirmingDelete(address)}>Xóa</button>
              </div>
              {confirmingDelete?.id === address.id && <div className="notice warning account-address-confirm" role="alert">
                <span>Xóa địa chỉ này?</span>
                <button type="button" className="button secondary" disabled={addressBusy} onClick={() => setConfirmingDelete(null)}>Giữ lại</button>
                <button type="button" className="button danger" disabled={addressBusy} onClick={() => deleteAddress(address)}>{addressBusy ? 'Đang xóa…' : 'Xác nhận xóa'}</button>
              </div>}
            </article>)}
          </div>}
        </div>
      </section>
      : <section id="account-password-panel" className="panel account-panel" role="tabpanel" aria-labelledby="account-password-tab">
        <div className="panel-heading"><h2>Đổi mật khẩu</h2></div>
        <div className="account-panel-body"><PasswordChangeForm /></div>
      </section>}
  </section>;
}
