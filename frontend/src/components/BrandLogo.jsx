// Nếp Nhà mark: a roof line sheltering a low sofa, drawn with the same 1.5px stroke language as Icon.
export function BrandMark({ size = 34, ...props }) {
  return <svg className="brand-mark" width={size} height={size} viewBox="0 0 40 40" fill="none" aria-hidden="true" {...props}>
    <rect x="1" y="1" width="38" height="38" rx="11" fill="currentColor" />
    <path d="M9 19.5 20 10l11 9.5" stroke="#fffefa" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
    <path d="M12.5 27.5v-3.2a2.3 2.3 0 0 1 2.3-2.3h10.4a2.3 2.3 0 0 1 2.3 2.3v3.2" stroke="#fffefa" strokeWidth="1.8" strokeLinecap="round" />
    <path d="M11 27.5h18M13.5 27.5v2.5M26.5 27.5v2.5" stroke="#e9d8bf" strokeWidth="1.8" strokeLinecap="round" />
  </svg>;
}

export function BrandLogo({ tagline = true, size = 34 }) {
  return <span className="brand-logo">
    <BrandMark size={size} />
    <span className="brand-logo-text"><span className="brand-logo-name">Nếp Nhà</span>{tagline && <span className="brand-logo-tagline">NỘI THẤT TỔ ẤM</span>}</span>
  </span>;
}
