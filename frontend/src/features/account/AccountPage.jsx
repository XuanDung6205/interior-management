import { useEffect, useState } from 'react';
import { ErrorNotice, Loading } from '../../components/ui';
import { useCommerce } from '../../lib/session';
import './account.css';

export default function AccountPage() {
  const { api, session } = useCommerce();
  const [activeTab, setActiveTab] = useState('profile');
  const [addresses, setAddresses] = useState([]);
  const [addressesLoaded, setAddressesLoaded] = useState(false);
  const [addressesLoading, setAddressesLoading] = useState(false);
  const [addressError, setAddressError] = useState(null);
  const [reload, setReload] = useState(0);

  useEffect(() => {
    if (activeTab !== 'addresses') return undefined;
    let active = true;
    setAddressesLoading(true);
    setAddressError(null);
    api.request('/account/addresses')
      .then(result => {
        if (!Array.isArray(result)) throw new Error('Không thể tải danh sách địa chỉ.');
        if (active) {
          setAddresses(result);
          setAddressesLoaded(true);
        }
      })
      .catch(error => {
        if (active) setAddressError(error);
      })
      .finally(() => {
        if (active) setAddressesLoading(false);
      });
    return () => { active = false; };
  }, [activeTab, api, reload]);

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

  return <section className="account-page">
    <header className="page-heading">
      <div>
        <span className="eyebrow">KHÔNG GIAN CỦA BẠN</span>
        <h1>Tài khoản của bạn</h1>
        <p>Quản lý thông tin cá nhân và địa chỉ nhận hàng.</p>
      </div>
    </header>
    <div className="account-tabs" role="tablist" aria-label="Khu vực tài khoản">
      <button id="account-profile-tab" type="button" role="tab" aria-selected={activeTab === 'profile'} aria-controls="account-profile-panel" className={`button ${activeTab === 'profile' ? '' : 'secondary'}`} onClick={() => setActiveTab('profile')}>Hồ sơ</button>
      <button id="account-addresses-tab" type="button" role="tab" aria-selected={activeTab === 'addresses'} aria-controls="account-addresses-panel" className={`button ${activeTab === 'addresses' ? '' : 'secondary'}`} onClick={() => setActiveTab('addresses')}>Sổ địa chỉ</button>
    </div>
    {activeTab === 'profile'
      ? <section id="account-profile-panel" className="panel account-panel" role="tabpanel" aria-labelledby="account-profile-tab">
        <div className="panel-heading"><h2>Thông tin hồ sơ</h2></div>
        <div className="account-panel-body account-profile-grid">
          <div><span className="eyebrow">HỌ VÀ TÊN</span><strong>{user.fullName || user.username}</strong></div>
          <div><span className="eyebrow">TÊN ĐĂNG NHẬP</span><strong>{user.username}</strong></div>
          <div><span className="eyebrow">VAI TRÒ</span><strong>{roleNames}</strong></div>
        </div>
      </section>
      : <section id="account-addresses-panel" className="panel account-panel" role="tabpanel" aria-labelledby="account-addresses-tab">
        <div className="panel-heading"><h2>Sổ địa chỉ</h2></div>
        <div className="account-panel-body">
          <ErrorNotice error={addressError} onRetry={() => setReload(value => value + 1)} />
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
            </article>)}
          </div>}
        </div>
      </section>}
  </section>;
}
