import { useEffect, useMemo, useState } from 'react';
import { ErrorNotice, Icon, Loading, Pager } from '../../components/ui';
import { useCommerce } from '../../lib/session';
import { userAdminApi } from './userAdminApi';
import './user-admin.css';

const STATUS_LABELS = { ACTIVE: 'Đang hoạt động', LOCKED: 'Đã khóa', DISABLED: 'Vô hiệu hóa' };
const ROLE_LABELS = { ADMIN: 'ADMIN', STAFF: 'STAFF', CUSTOMER: 'CUSTOMER' };

export default function UserAdminPage() {
  const { api, session } = useCommerce();
  const client = useMemo(() => userAdminApi(api), [api]);
  const [accounts, setAccounts] = useState(null);
  const [searchText, setSearchText] = useState('');
  const [query, setQuery] = useState('');
  const [role, setRole] = useState('');
  const [status, setStatus] = useState('');
  const [page, setPage] = useState(0);
  const [revision, setRevision] = useState(0);
  const [loading, setLoading] = useState(true);
  const [busyId, setBusyId] = useState(null);
  const [confirming, setConfirming] = useState(null);
  const [roleConfirming, setRoleConfirming] = useState(null);
  const [error, setError] = useState(null);
  const [notice, setNotice] = useState('');

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError(null);
    client.list({ query, role, status, page, signal: controller.signal })
      .then(result => {
        setAccounts(result);
        if (!result.items.length && page > 0) setPage(page - 1);
      })
      .catch(value => { if (value.name !== 'AbortError') setError(value); })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [client, query, role, status, page, revision]);

  async function changeStatus(account) {
    const newStatus = account.status === 'ACTIVE' ? 'LOCKED' : 'ACTIVE';
    setBusyId(account.id);
    setError(null);
    setNotice('');
    try {
      await client.setStatus(account.id, newStatus);
      setNotice(newStatus === 'LOCKED'
        ? `Đã khóa tài khoản ${account.username}.`
        : `Đã mở khóa tài khoản ${account.username}.`);
      setConfirming(null);
      setRevision(value => value + 1);
    } catch (value) {
      setError(value);
    } finally {
      setBusyId(null);
    }
  }

  async function changeRole(account) {
    const roleCode = account.roles.includes('STAFF') ? 'CUSTOMER' : 'STAFF';
    setBusyId(account.id);
    setError(null);
    setNotice('');
    try {
      await client.setRole(account.id, roleCode);
      setNotice(`Đã chuyển tài khoản ${account.username} thành ${ROLE_LABELS[roleCode]}.`);
      setRoleConfirming(null);
      setRevision(value => value + 1);
    } catch (value) {
      setError(value);
    } finally {
      setBusyId(null);
    }
  }

  return <>
    <div className="breadcrumb"><span>Quản trị</span><span>/</span><span>Tài khoản</span></div>
    <div className="page-heading">
      <div>
        <span className="eyebrow">CHỈ DÀNH CHO ADMIN</span>
        <h1>Quản lý tài khoản<span className="heading-dot">.</span></h1>
        <p>Khóa/mở tài khoản và chuyển vai trò STAFF ↔ CUSTOMER. Không thể thay đổi vai trò ADMIN.</p>
      </div>
    </div>
    <ErrorNotice error={error} onRetry={() => setRevision(value => value + 1)} />
    {notice && <div className="notice success" role="status">{notice}</div>}
    <section className="panel user-admin-panel" aria-label="Danh sách tài khoản">
      <div className="user-admin-toolbar">
        <div>
          <h2>Tài khoản <span className="count-badge">{accounts?.totalElements ?? '—'}</span></h2>
          <p className="muted small">Khóa tài khoản sẽ kết thúc phiên đăng nhập đang hoạt động.</p>
        </div>
        <form className="user-admin-filters" onSubmit={event => {
          event.preventDefault();
          setPage(0);
          setQuery(searchText.trim());
        }}>
          <label className="sr-only" htmlFor="account-search">Tìm tài khoản</label>
          <input id="account-search" type="search" value={searchText}
            onChange={event => setSearchText(event.target.value)} placeholder="Tên, email…" maxLength={150} />
          <label className="sr-only" htmlFor="account-role">Lọc theo vai trò</label>
          <select id="account-role" value={role} onChange={event => { setRole(event.target.value); setPage(0); }}>
            <option value="">Tất cả vai trò</option>
            <option value="ADMIN">ADMIN</option><option value="STAFF">STAFF</option><option value="CUSTOMER">CUSTOMER</option>
          </select>
          <label className="sr-only" htmlFor="account-status">Lọc theo trạng thái</label>
          <select id="account-status" value={status} onChange={event => { setStatus(event.target.value); setPage(0); }}>
            <option value="">Tất cả trạng thái</option>
            <option value="ACTIVE">Đang hoạt động</option><option value="LOCKED">Đã khóa</option><option value="DISABLED">Vô hiệu hóa</option>
          </select>
          <button className="button secondary" disabled={loading}>Tìm kiếm<Icon name="search" size={16} /></button>
        </form>
      </div>
      {loading ? <Loading text="Đang tải tài khoản…" /> : accounts?.items.length ? (
        <div className="tv5-table-wrap">
          <table className="tv5-table user-admin-table">
            <thead><tr><th>Tài khoản</th><th>Email / Số điện thoại</th><th>Vai trò</th><th>Trạng thái</th><th>Thao tác</th></tr></thead>
            <tbody>{accounts.items.map(account => {
              const isSelf = account.id === session.user?.id;
              const lock = account.status === 'ACTIVE';
              return <tr key={account.id}>
                <td><strong>{account.fullName || account.username}</strong><small>{account.username}</small></td>
                <td>{account.email}<small>{account.phone || 'Chưa có số điện thoại'}</small></td>
                <td><div className="user-admin-roles">{account.roles.map(value => <span key={value}>{ROLE_LABELS[value] || value}</span>)}</div></td>
                <td><span className={`status-pill ${account.status === 'ACTIVE' ? 'active' : 'inactive'}`}><span />{STATUS_LABELS[account.status] || account.status}</span></td>
                <td><div className="user-admin-actions">
                  {isSelf
                    ? <span className="muted small">Tài khoản của bạn</span>
                    : account.status === 'ACTIVE' || account.status === 'LOCKED'
                      ? <button className={`text-button ${lock ? 'destructive' : ''}`} disabled={busyId !== null}
                          onClick={() => { setRoleConfirming(null); setConfirming(account); }}>
                          {lock ? 'Khóa' : 'Mở khóa'}
                        </button>
                      : <span className="muted small">Không thể đổi trạng thái</span>}
                  {!account.roles.includes('ADMIN')
                    && account.roles.some(value => value === 'STAFF' || value === 'CUSTOMER')
                    && <button className="text-button" disabled={busyId !== null}
                      onClick={() => { setConfirming(null); setRoleConfirming(account); }}>
                      Chuyển thành {account.roles.includes('STAFF') ? 'CUSTOMER' : 'STAFF'}
                    </button>}
                </div></td>
            </tr>;
            })}</tbody>
          </table>
        </div>
      ) : !error && <div className="empty-state"><Icon name="user" size={38} /><h3>Không tìm thấy tài khoản</h3><p>Thử thay đổi từ khóa hoặc bộ lọc.</p></div>}
      <Pager page={accounts} busy={loading || busyId !== null} onChange={setPage} />
    </section>
    {confirming && <div className="notice warning user-admin-confirm" role="alert">
      <div><strong>{confirming.status === 'ACTIVE' ? 'Khóa' : 'Mở khóa'} tài khoản {confirming.username}?</strong>
        <p>{confirming.status === 'ACTIVE'
          ? 'Tài khoản sẽ không thể đăng nhập; phiên đang hoạt động cũng sẽ bị kết thúc.'
          : 'Tài khoản sẽ được phép đăng nhập trở lại.'}</p>
      </div>
      <button className="button secondary" disabled={busyId !== null} onClick={() => setConfirming(null)}>Hủy</button>
      <button className={`button ${confirming.status === 'ACTIVE' ? 'danger' : ''}`} disabled={busyId !== null}
        onClick={() => changeStatus(confirming)}>{busyId === confirming.id ? 'Đang cập nhật…' : 'Xác nhận'}</button>
    </div>}
    {roleConfirming && <div className="notice warning user-admin-confirm" role="alert">
      <div><strong>Chuyển vai trò tài khoản {roleConfirming.username}?</strong>
        <p>{roleConfirming.roles.includes('STAFF') ? 'STAFF' : 'CUSTOMER'} sẽ được chuyển thành {roleConfirming.roles.includes('STAFF') ? 'CUSTOMER' : 'STAFF'}. Thay đổi được ghi vào nhật ký thao tác.</p>
      </div>
      <button className="button secondary" disabled={busyId !== null} onClick={() => setRoleConfirming(null)}>Hủy</button>
      <button className="button" disabled={busyId !== null}
        onClick={() => changeRole(roleConfirming)}>{busyId === roleConfirming.id ? 'Đang cập nhật…' : 'Xác nhận đổi vai trò'}</button>
    </div>}
  </>;
}
