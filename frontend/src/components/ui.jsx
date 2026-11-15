export function Icon({ name, size = 20, ...props }) {
  const paths = {
    bag: <><path d="M5 7h14l1 14H4L5 7Z" /><path d="M8 8V6a4 4 0 0 1 8 0v2" /></>,
    arrow: <><path d="M4 12h16M14 6l6 6-6 6" /></>,
    search: <><circle cx="10.5" cy="10.5" r="6.5" /><path d="m16 16 5 5" /></>,
    leaf: <><path d="M20 3C9 1 2 8 5 15c5 8 17 0 15-12Z" /><path d="M4 21 16 8" /></>,
    plus: <path d="M12 5v14M5 12h14" />,
    minus: <path d="M5 12h14" />,
    close: <path d="m6 6 12 12M6 18 18 6" />,
    trash: <><path d="M3 6h18M9 6V3h6v3M6 6l1 15h10l1-15M10 10v7M14 10v7" /></>,
    chair: <><path d="M6 13V5a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2v8M4 12h16v5H4zM6 17v4M18 17v4" /></>,
    shield: <><path d="m12 3 8 3v6c0 5-8 9-8 9s-8-4-8-9V6l8-3Z" /><path d="m8 12 3 3 5-6" /></>,
    ticket: <><path d="M3 6h18v4a2 2 0 0 0 0 4v4H3v-4a2 2 0 0 0 0-4V6Z" /><path d="M15 6v3m0 2v2m0 2v3" /></>,
    user: <><circle cx="12" cy="7" r="4" /><path d="M4 21v-3a8 8 0 0 1 16 0v3" /></>,
    orders: <><path d="M7 3h10v4H7zM7 5H4v16h16V5h-3M8 12h8M8 16h5" /></>,
    box: <><path d="m3 7 9-4 9 4v10l-9 4-9-4V7Z" /><path d="m3 7 9 4 9-4M12 11v10" /></>,
  };
  return <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" {...props}>{paths[name] || paths.arrow}</svg>;
}
export function ErrorNotice({ error, onRetry }) {
  if (!error) return null;
  return <div className="notice error" role="alert"><strong>{error.status === 401 ? 'Phiên đăng nhập đã kết thúc. ' : ''}{error.message || error}</strong>{error.status === 403 && <p>Vui lòng kiểm tra quyền truy cập hoặc thử lại để làm mới phiên bảo mật.</p>}{onRetry && <button className="text-button" onClick={onRetry}>Thử lại</button>}</div>;
}
export function Loading({ text = 'Đang tải…' }) { return <div className="loading" role="status"><span className="spinner" />{text}</div>; }
export function ProductImage({ src, name }) {
  return <div className="product-image">{src ? <img src={src} alt={name} loading="lazy" onError={event => { event.currentTarget.hidden = true; }} /> : null}<span className="image-fallback" aria-hidden="true"><Icon name="chair" size={40} /><span>Ảnh sản phẩm</span></span></div>;
}
export function Pager({ page, busy, onChange }) {
  if (!page || page.totalPages < 2) return null;
  return <nav className="pager" aria-label="Phân trang"><button className="button secondary" disabled={busy || page.page === 0} onClick={() => onChange(page.page - 1)}>Trang trước</button><span>Trang {page.page + 1} / {page.totalPages}</span><button className="button secondary" disabled={busy || page.page + 1 >= page.totalPages} onClick={() => onChange(page.page + 1)}>Trang sau</button></nav>;
}
