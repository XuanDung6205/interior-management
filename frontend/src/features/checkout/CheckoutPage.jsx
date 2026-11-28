import { useEffect, useMemo, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { ErrorNotice, Icon, Loading } from '../../components/ui';
import { useCommerce } from '../../lib/session';
import { trackEvent } from '../../lib/tracking';
import { AddressSnapshot, MoneyBreakdown, OrderItems, OrderNote, paymentMethods } from '../orders/OrderParts';
import { checkoutApi, clearAttempt, readAttempt, saveAttempt } from './checkoutApi';
import CheckoutSteps from '../../components/CheckoutSteps';

const emptyAddress = () => ({ recipientName: '', phone: '', email: '', addressLine1: '', addressLine2: '', wardName: '', districtName: '', provinceName: '', postalCode: '', countryCode: 'VN', companyName: '', taxCode: '' });
// These typed business failures are emitted only after the server checked the
// original key inside its transaction. Middleware errors cannot resolve it.
const uncommittedCodes = new Set(['CART_UNAVAILABLE', 'EMPTY_CART', 'SKU_UNAVAILABLE', 'STOCK_UNAVAILABLE', 'COUPON_UNAVAILABLE', 'COUPON_MINIMUM_NOT_MET', 'COUPON_LIMIT_REACHED', 'ORDER_AMOUNT_TOO_LARGE', 'NOT_FOUND', 'SEPAY_NOT_CONFIGURED', 'SEPAY_AMOUNT_UNSUPPORTED', 'SEPAY_CODE_EXHAUSTED']);
export default function CheckoutPage() {
  const { api, session } = useCommerce();
  const userId = session.user.id;
  const client = useMemo(() => checkoutApi(api), [api]);
  const [pending, setPending] = useState(() => readAttempt(userId));
  const [quote, setQuote] = useState(() => readAttempt(userId)?.quote ?? null);
  const [quotedRequest, setQuotedRequest] = useState(null);
  const [cart, setCart] = useState(null);
  const [addresses, setAddresses] = useState([]);
  const [addressMode, setAddressMode] = useState('manual');
  const [addressId, setAddressId] = useState('');
  const [address, setAddress] = useState(emptyAddress);
  const [coupon, setCoupon] = useState('');
  const [note, setNote] = useState('');
  const [sepayEnabled, setSepayEnabled] = useState(false);
  const [payment, setPayment] = useState('COD');
  const [assembly, setAssembly] = useState(false);
  const [accepted, setAccepted] = useState(false);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const [revision, setRevision] = useState(0);
  const [changed, setChanged] = useState(false);
  const placing = useRef(false);
  useEffect(() => {
    // Ghi nhận khách vừa vào trang thanh toán / đặt hàng
    trackEvent(api, { eventType: 'CHECKOUT_START', pagePath: '/checkout' });
  }, [api]);
  const errorRef = useRef(null);
  const quoteRef = useRef(null);
  const navigate = useNavigate();
  useEffect(() => {
    const controller = new AbortController(); setLoading(true); setError(null);
    Promise.all([api.request('/cart', { signal: controller.signal }), client.addresses(controller.signal)]).then(([currentCart, data]) => {
      if (controller.signal.aborted) return;
      setCart(currentCart); setAddresses(data.items);
      if (data.items.length) { setAddressMode('saved'); setAddressId(String(data.items[0].id)); }
    }).catch(value => { if (value.name !== 'AbortError') setError(value); }).finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [api, client, revision]);
  useEffect(() => { const controller = new AbortController(); api.request('/payments/sepay/config', { signal: controller.signal }).then(value => { if (!controller.signal.aborted) setSepayEnabled(value.enabled === true); }).catch(() => {}); return () => controller.abort(); }, [api]);
  useEffect(() => { if (error) errorRef.current?.focus(); }, [error]);
  function invalidate() { setQuote(null); setQuotedRequest(null); setAccepted(false); setChanged(false); setError(null); clearAttempt(userId); }
  function edit(setter, value) { invalidate(); setter(value); }
  const fieldError = name => error?.fieldErrors?.[`shippingAddress.${name}`] || error?.fieldErrors?.[name];
  function addressField(name, label, required = false, type = 'text', autoComplete) {
    return <div className={`form-field ${name === 'addressLine1' || name === 'addressLine2' ? 'wide' : ''}`}><label htmlFor={`checkout-${name}`}>{label}</label><input id={`checkout-${name}`} value={address[name]} type={type} required={required} maxLength={name === 'addressLine1' || name === 'addressLine2' ? 255 : 150} autoComplete={autoComplete} onChange={event => { invalidate(); setAddress(current => ({ ...current, [name]: event.target.value })); }} aria-invalid={!!fieldError(name)} aria-describedby={fieldError(name) ? `checkout-error-${name}` : undefined} />{fieldError(name) && <span className="field-error" id={`checkout-error-${name}`}>{fieldError(name)}</span>}</div>;
  }
  function makeRequest() {
    const shippingAddress = Object.fromEntries(Object.entries(address).map(([key, value]) => [key, value.trim() || null]));
    return { cartId: cart.id, addressId: addressMode === 'saved' ? Number(addressId) : null, shippingAddress: addressMode === 'manual' ? shippingAddress : null, couponCode: coupon.trim().toUpperCase() || null, paymentMethod: payment, assemblyRequested: assembly, customerNote: note.trim() || null };
  }
  async function preview(event) {
    event.preventDefault(); if (busy) return;
    setBusy(true); setError(null); setAccepted(false); setQuote(null); setChanged(false);
    try { const body = makeRequest(); const result = await client.quote(body); setQuotedRequest(body); setQuote(result); requestAnimationFrame(() => quoteRef.current?.focus()); }
    catch (value) { setError(value); }
    finally { setBusy(false); }
  }
  async function place() {
    if (placing.current || (!pending && (!accepted || !quote || !quotedRequest))) return;
    placing.current = true; setBusy(true); setError(null);
    let attempt = pending;
    try {
      if (!attempt) {
        attempt = { key: crypto.randomUUID(), body: { ...quotedRequest, quoteFingerprint: quote.quoteFingerprint }, quote };
        saveAttempt(userId, attempt); setPending(attempt);
      }
      const result = await client.place(attempt);
      clearAttempt(userId); setPending(null);
      navigate(attempt.body.paymentMethod === 'SEPAY' ? `/orders/${result.id}/payments` : `/orders/${result.id}`, { replace: true, state: { placed: true } });
    } catch (value) {
      setError(value); setAccepted(false);
      if (value.code === 'QUOTE_CHANGED' && value.quote) {
        clearAttempt(userId); setPending(null); setQuote(value.quote); setChanged(true);
        restoreRequest(attempt.body);
        const { quoteFingerprint, ...body } = attempt.body; setQuotedRequest(body);
      } else if ([404, 409].includes(value.status) && uncommittedCodes.has(value.code)) {
        restoreRequest(attempt.body);
        setQuote(null); setQuotedRequest(null); setChanged(false);
        clearAttempt(userId); setPending(null);
      }
    } finally { setBusy(false); placing.current = false; }
  }
  function restoreRequest(body) {
    setAddressMode(body.addressId != null ? 'saved' : 'manual'); setAddressId(body.addressId == null ? '' : String(body.addressId));
    setAddress(Object.fromEntries(Object.entries({ ...emptyAddress(), ...body.shippingAddress }).map(([key, value]) => [key, value ?? ''])));
    setCoupon(body.couponCode ?? ''); setNote(body.customerNote ?? ''); setPayment(body.paymentMethod); setAssembly(!!body.assemblyRequested);
  }
  return <>
    <div className="breadcrumb"><Link to="/cart">Giỏ hàng</Link><span>/</span><span>Đặt hàng</span></div>
    <div className="page-heading"><div><span className="eyebrow">THÊM MỘT BƯỚC ĐẾN TỔ ẤM</span><h1>Hoàn thiện đơn hàng<span className="heading-dot">.</span></h1><p>Xem lại địa chỉ, lựa chọn dịch vụ và tổng tiền trước khi đặt.</p></div><Link to="/cart" className="inline-link">Trở về giỏ hàng</Link></div>
    <CheckoutSteps current={1} />
    <div ref={errorRef} tabIndex="-1" className="error-summary"><ErrorNotice error={error} onRetry={loading || !cart ? () => setRevision(value => value + 1) : undefined} />{!!Object.keys(error?.fieldErrors || {}).length && <ul>{Object.entries(error.fieldErrors).map(([key, value]) => <li key={key}><a href={`#checkout-${key.replace('shippingAddress.', '')}`}>{value}</a></li>)}</ul>}{error?.status === 401 && <Link to="/login" state={{ returnTo: '/checkout' }} className="inline-link">Đăng nhập lại</Link>}</div>
    {pending && <div className="notice warning" role="status"><strong>Đang chờ xác nhận kết quả đặt hàng</strong><p>Thông tin yêu cầu được giữ trong phiên trình duyệt. Hãy thử lại đúng yêu cầu này để nhận kết quả; hệ thống sẽ trả cùng đơn nếu đã tạo. Bạn cũng có thể <Link to="/orders" className="inline-link">kiểm tra đơn hàng</Link>. Chưa thay đổi thông tin khi kết quả còn chưa rõ.</p></div>}
    {loading ? <Loading text="Đang chuẩn bị thông tin đặt hàng…" /> : !pending && cart?.items.length === 0 ? <section className="panel empty-state"><Icon name="bag" size={38} /><h2>Giỏ hàng đang trống</h2><p>Chọn sản phẩm trước khi xem trước đơn hàng.</p><Link to="/cart" className="button">Khám phá sản phẩm</Link></section> : (cart || pending) && <div className="checkout-layout">
      {pending ? <section className="panel pending-request"><div className="panel-heading"><h2>Yêu cầu đã gửi</h2></div><div className="checkout-form-body"><AddressSnapshot address={pending.quote.shippingAddress} /><dl className="pending-request-options"><div><dt>Thanh toán</dt><dd>{paymentMethods[pending.body.paymentMethod] || pending.body.paymentMethod}</dd></div><div><dt>Lắp đặt</dt><dd>{pending.body.assemblyRequested ? 'Có yêu cầu' : 'Không yêu cầu'}</dd></div>{pending.body.couponCode && <div><dt>Mã ưu đãi</dt><dd>{pending.body.couponCode}</dd></div>}</dl>{pending.body.customerNote && <><h3>Ghi chú đã gửi</h3><p className="customer-note">{pending.body.customerNote}</p></>}<OrderNote>Thông tin được giữ nguyên để thử lại. Không gửi một yêu cầu đặt hàng mới khi chưa rõ kết quả của yêu cầu này.</OrderNote></div></section> : <form className="panel checkout-form" onSubmit={preview} aria-label="Thông tin đặt hàng"><fieldset disabled={busy}><div className="panel-heading"><h2><span className="section-number">01</span>Thông tin giao hàng</h2></div><div className="checkout-form-body">
        <div className="address-mode">{addresses.length > 0 && <label className="radio-label"><input type="radio" name="address-mode" checked={addressMode === 'saved'} onChange={() => edit(setAddressMode, 'saved')} />Địa chỉ đã lưu</label>}<label className="radio-label"><input type="radio" name="address-mode" checked={addressMode === 'manual'} onChange={() => edit(setAddressMode, 'manual')} />Nhập địa chỉ mới</label></div>
        {addressMode === 'saved' ? <><label htmlFor="checkout-addressId">Chọn địa chỉ giao hàng</label><select id="checkout-addressId" value={addressId} onChange={event => edit(setAddressId, event.target.value)} required>{addresses.map(item => <option key={item.id} value={item.id}>{item.label || item.recipientName} — {item.addressLine1}</option>)}</select><AddressSnapshot address={addresses.find(item => String(item.id) === addressId)} />{fieldError('addressId') && <span className="field-error">{fieldError('addressId')}</span>}</> : <><div className="form-grid">{addressField('recipientName', 'Họ tên người nhận', true, 'text', 'shipping name')}{addressField('phone', 'Số điện thoại', true, 'tel', 'shipping tel')}{addressField('email', 'Email nhận thông tin', false, 'email', 'shipping email')}{addressField('provinceName', 'Tỉnh / thành phố', true, 'text', 'shipping address-level1')}{addressField('districtName', 'Quận / huyện', true, 'text', 'shipping address-level2')}{addressField('wardName', 'Phường / xã', true, 'text', 'shipping address-level3')}{addressField('addressLine1', 'Địa chỉ', true, 'text', 'shipping address-line1')}{addressField('addressLine2', 'Chi tiết địa chỉ bổ sung', false, 'text', 'shipping address-line2')}{addressField('postalCode', 'Mã bưu chính', false, 'text', 'shipping postal-code')}{addressField('companyName', 'Tên công ty (nếu có)')}{addressField('taxCode', 'Mã số thuế (nếu có)')}</div><p className="form-hint">Giao hàng tại Việt Nam. Địa chỉ nhập ở đây chỉ dùng cho đơn này.</p></>}
      </div><div className="panel-heading"><h2><span className="section-number">02</span>Dịch vụ & thanh toán</h2></div><div className="checkout-form-body"><fieldset className="payment-options"><legend>Phương thức thanh toán</legend>{Object.entries(paymentMethods).filter(([value]) => value !== 'SEPAY' || sepayEnabled).map(([value, label]) => <label className={`payment-choice ${payment === value ? 'selected' : ''}`} key={value}><input type="radio" name="payment-method" value={value} checked={payment === value} onChange={() => edit(setPayment, value)} /><span>{label}</span></label>)}</fieldset><p className="form-hint">{payment === 'SEPAY' ? 'SePay Test mode: mô phỏng chuyển khoản, không dùng tiền thật. Mở thanh toán của đơn để xem QR sau khi đặt hàng.' : 'Chuyển khoản thủ công chỉ được ghi nhận đã thu sau khi cửa hàng xác minh.'}</p><label className="checkbox-label assembly-choice"><input type="checkbox" checked={assembly} onChange={event => edit(setAssembly, event.target.checked)} />Yêu cầu lắp đặt</label><p className="muted small">Phí được tính theo sản phẩm cần lắp đặt trong bước xem trước.</p><div className="form-field coupon-input"><label htmlFor="checkout-couponCode">Mã ưu đãi</label><input id="checkout-couponCode" value={coupon} maxLength={50} onChange={event => edit(setCoupon, event.target.value)} aria-describedby={fieldError('couponCode') ? 'checkout-error-coupon' : undefined} />{fieldError('couponCode') && <span id="checkout-error-coupon" className="field-error">{fieldError('couponCode')}</span>}</div><div className="form-field"><label htmlFor="checkout-customerNote">Ghi chú cho cửa hàng</label><textarea id="checkout-customerNote" value={note} maxLength={1000} rows={3} onChange={event => edit(setNote, event.target.value)} /></div><button className="button full" disabled={busy || !cart?.items.length}>{busy ? 'Đang xử lý…' : 'Xem trước đơn hàng'}<Icon name="arrow" size={18} /></button></div></fieldset></form>}
      <aside className="checkout-review" ref={quoteRef} tabIndex="-1">{quote ? <section className="panel quote-panel" aria-label="Xem trước đơn hàng"><div className="panel-heading"><h2><span className="section-number">03</span>Kiểm tra & xác nhận</h2></div><div className="quote-body">{changed && <div className="notice warning">Thông tin vừa thay đổi. Vui lòng xem lại báo giá mới và xác nhận một lần nữa.</div>}<AddressSnapshot address={quote.shippingAddress} /><OrderItems items={quote.items} /><MoneyBreakdown value={quote} testId="checkout-total" /><p className="quote-payment">{paymentMethods[quote.paymentMethod] || quote.paymentMethod}{quote.assemblyRequested ? ' · Có yêu cầu lắp đặt' : ''}</p>{!!quote.warnings?.length && <ul className="notice warning">{quote.warnings.map((warning, index) => <li key={index}>{typeof warning === 'string' ? warning : warning.message}</li>)}</ul>}{pending ? <button type="button" className="button full" disabled={busy} onClick={place}>{busy ? 'Đang kiểm tra kết quả…' : 'Thử lại yêu cầu đặt hàng'}</button> : <><label className="checkbox-label checkout-consent"><input type="checkbox" checked={accepted} onChange={event => setAccepted(event.target.checked)} disabled={busy} />Tôi đã kiểm tra thông tin và tổng tiền.</label><button type="button" className="button full" disabled={busy || !accepted} onClick={place}>{busy ? 'Đang tạo đơn…' : 'Đặt hàng'}<Icon name="arrow" size={18} /></button></>}<OrderNote>Báo giá chưa giữ tồn kho hoặc lượt ưu đãi. Hệ thống kiểm tra lại khi đặt hàng.</OrderNote></div></section> : <section className="panel quote-placeholder"><Icon name="bag" size={40} /><h2>Chọn xong, rồi xem lại</h2><p>Điền thông tin và chọn “Xem trước đơn hàng” để nhận các khoản tiền do cửa hàng tính.</p><OrderNote>Chưa tạo đơn hoặc thu tiền ở bước xem trước.</OrderNote></section>}</aside>
    </div>}
  </>;
}
