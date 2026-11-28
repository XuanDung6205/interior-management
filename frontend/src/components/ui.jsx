export function Icon({ name, size = 20, ...props }) {
  const paths = {
    bag: <><path d="M5 7h14l1 14H4L5 7Z" /><path d="M8 8V6a4 4 0 0 1 8 0v2" /></>,
    cart: <><path d="M2.5 4H5l2.3 13h12.5l2-10H6" /><circle cx="9" cy="21" r="1" /><circle cx="19" cy="21" r="1" /></>,
    menu: <><path d="M3 6h18M3 12h18M3 18h18" /></>,
    chevron: <path d="m5 9 7 7 7-7" />,
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
    // Furniture and service icons share the same 24px grid and 1.5px stroke.
    sofa: <><path d="M5 11V8a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2v3" /><path d="M3 12a2 2 0 0 1 4 0v2h10v-2a2 2 0 0 1 4 0v5H3v-5Z" /><path d="M5 17v2M19 17v2" /></>,
    armchair: <><path d="M7 11V6a2 2 0 0 1 2-2h6a2 2 0 0 1 2 2v5" /><path d="M4 11h3v3h10v-3h3v6H4v-6Z" /><path d="M6 17v3M18 17v3" /></>,
    'coffee-table': <><path d="M3 10h18M5 10l-1 9M19 10l1 9M7 14h10" /><path d="M9 7.5c0-1 1.3-2 3-2s3 1 3 2" /></>,
    'dining-table': <><path d="M2 9h20M5 9v11M19 9v11M5 14h14" /><path d="M9 6h6" /></>,
    'dining-chair': <><path d="M7 3v18M7 12h10v9M7 12V3h8v9" /><path d="M9 6h4M9 9h4" /></>,
    bed: <><path d="M3 20V5M3 13h18v7M21 13v-2a3 3 0 0 0-3-3h-7v5" /><circle cx="7" cy="10" r="2" /></>,
    wardrobe: <><rect x="5" y="3" width="14" height="16" rx="1" /><path d="M12 3v16M10 10v2M14 10v2M7 19v2M17 19v2" /></>,
    lamp: <><path d="M8 3h8l3 7H5l3-7Z" /><path d="M12 10v9M8 21h8" /></>,
    plant: <><path d="M7 15h10l-1.5 6h-7L7 15Z" /><path d="M12 15V8M12 9c-3 0-5-2-5-5 3 0 5 2 5 5ZM12 11c0-3 2-5 5-5 0 3-2 5-5 5Z" /></>,
    truck: <><path d="M2 6h11v10H2zM13 9h4l4 4v3h-8" /><circle cx="6" cy="18" r="2" /><circle cx="17" cy="18" r="2" /></>,
    tools: <><path d="M14.5 6.5a4 4 0 0 1 5-5l-2.5 2.5.5 2 2 .5 2.5-2.5a4 4 0 0 1-5 5L8 18a2 2 0 0 1-3-3l9.5-8.5Z" /></>,
    returns: <><path d="M4 9h11a5 5 0 0 1 0 10H8" /><path d="m8 5-4 4 4 4" /></>,
    check: <path d="m5 12.5 4.5 4.5L19 7.5" />,
    eye: <><path d="M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12Z" /><circle cx="12" cy="12" r="3" /></>,
    'eye-off': <><path d="M10.6 5.1A10.8 10.8 0 0 1 12 5c6.4 0 10 7 10 7a17.6 17.6 0 0 1-2.9 3.8M6.6 6.6C3.7 8.4 2 12 2 12s3.6 7 10 7a9.7 9.7 0 0 0 5.4-1.6" /><path d="M9.9 9.9a3 3 0 0 0 4.2 4.2M3 3l18 18" /></>,
    'map-pin': <><path d="M12 21s7-6.2 7-12a7 7 0 0 0-14 0c0 5.8 7 12 7 12Z" /><circle cx="12" cy="9" r="2.5" /></>,
    bell: <><path d="M6 16V11a6 6 0 0 1 12 0v5l2 2H4l2-2Z" /><path d="M10 21h4" /></>,
    heart: <path d="M12 20s-8-4.6-8-10.5A4.5 4.5 0 0 1 12 7a4.5 4.5 0 0 1 8 2.5C20 15.4 12 20 12 20Z" />,
    headset:<><path d="M4 14v-2a8 8 0 0 1 16 0v2" /><rect x="3" y="14" width="4" height="6" rx="1.5" /><rect x="17" y="14" width="4" height="6" rx="1.5" /><path d="M19 20c0 1-2 2-5 2" /></>,
  };
  return <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" {...props}>{paths[name] || paths.arrow}</svg>;
}
// Order numbers are long generated codes: render them compact and wrappable without changing their text.
export function OrderCode({ value }) { return <span className="order-code">{value}</span>; }
export function ErrorNotice({ error, onRetry }) {
  if (!error) return null;
  return <div className="notice error" role="alert"><strong>{error.status === 401 && error.code !== 'INVALID_CREDENTIALS' ? 'Phiên đăng nhập đã kết thúc. ' : ''}{error.message || error}</strong>{error.status === 403 && <p>Vui lòng kiểm tra quyền truy cập hoặc thử lại để làm mới phiên bảo mật.</p>}{onRetry && <button className="text-button" onClick={onRetry}>Thử lại</button>}</div>;
}
export function Loading({ text = 'Đang tải…' }) { return <div className="loading" role="status"><span className="spinner" />{text}</div>; }
export function ProductImage({ src, name, thumbnail = false, priority = false }) {
  const small = thumbnail && /^\/images\/catalog\/[^/]+\.jpg$/.test(src || '') ? src.replace('/catalog/', '/catalog/thumbs/') : src;
  return <div className="product-image">{src ? <img key={small} src={small} alt={name} width="600" height="600" decoding="async" loading={priority ? 'eager' : 'lazy'} fetchPriority={priority ? 'high' : undefined} onError={event => { if (event.currentTarget.getAttribute('src') !== src) event.currentTarget.src = src; else event.currentTarget.hidden = true; }} /> : null}<span className="image-fallback" aria-hidden="true"><Icon name="chair" size={40} /><span>Ảnh sản phẩm</span></span></div>;
}
export function Pager({ page, busy, onChange }) {
  if (!page || page.totalPages < 2) return null;
  return <nav className="pager" aria-label="Phân trang"><button className="button secondary" disabled={busy || page.page === 0} onClick={() => onChange(page.page - 1)}>Trang trước</button><span>Trang {page.page + 1} / {page.totalPages}</span><button className="button secondary" disabled={busy || page.page + 1 >= page.totalPages} onClick={() => onChange(page.page + 1)}>Trang sau</button></nav>;
}
