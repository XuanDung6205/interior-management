import { useEffect, useMemo, useState } from 'react';
import { useCommerce } from '../../lib/session';
import { ErrorNotice, Icon, Loading, Pager } from '../../components/ui';
import { formatDate } from '../../lib/format';
import { inventoryApi } from './inventoryApi';

const emptyForm = () => ({ sourceWarehouseId: '', destinationWarehouseId: '', variantId: '', quantity: '',
  direction: 'decrease', referenceCode: '', reason: '' });
const definitiveErrors = new Set(['INVALID_ADJUSTMENT', 'INVALID_TRANSFER', 'INVALID_STOCK_OPERATION',
  'VALIDATION_ERROR', 'STOCK_RESERVED', 'STOCK_UNAVAILABLE', 'STOCK_LIMIT', 'STOCK_CHANGED',
  'WAREHOUSE_INACTIVE', 'NOT_FOUND', 'IDEMPOTENCY_CONFLICT']);

export default function StockOperationsPage() {
  const { api, session } = useCommerce();
  const client = useMemo(() => inventoryApi(api), [api]);
  const storageKey = `tv4.stock.pending.${session.user.id}`;
  const [pending, setPending] = useState(() => {
    try { return JSON.parse(sessionStorage.getItem(storageKey)); } catch { return null; }
  });
  const [mode, setMode] = useState('adjustment');
  const [form, setForm] = useState(emptyForm);
  const [warehouses, setWarehouses] = useState([]);
  const [variants, setVariants] = useState([]);
  const [variantQuery, setVariantQuery] = useState('');
  const [balances, setBalances] = useState([]);
  const [history, setHistory] = useState(null);
  const [page, setPage] = useState(0);
  const [revision, setRevision] = useState(0);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const [notice, setNotice] = useState(null);

  useEffect(() => {
    let active = true;
    Promise.all([client.warehouses(), client.variants('')]).then(([locations, catalog]) => {
      if (active) { setWarehouses(locations); setVariants(catalog.items); }
    }).catch(value => { if (active) setError(value); });
    return () => { active = false; };
  }, [client]);
  useEffect(() => {
    let active = true;
    setLoading(true);
    client.movements(page).then(value => { if (active) setHistory(value); })
      .catch(value => { if (active) setError(value); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [client, page, revision]);
  useEffect(() => {
    let active = true;
    if (!form.variantId || !form.sourceWarehouseId) { setBalances([]); return; }
    const ids = mode === 'transfer' && form.destinationWarehouseId
      ? [form.sourceWarehouseId, form.destinationWarehouseId] : [form.sourceWarehouseId];
    Promise.all(ids.map(id => client.balance(id, form.variantId))).then(values => {
      if (active) setBalances(values);
    }).catch(value => { if (active) setError(value); });
    return () => { active = false; };
  }, [client, form.sourceWarehouseId, form.destinationWarehouseId, form.variantId, mode, revision]);

  function change(field, value) { setForm(current => ({ ...current, [field]: value })); }
  async function searchVariants(event) {
    event.preventDefault(); setError(null);
    try { setVariants((await client.variants(variantQuery.trim())).items); change('variantId', ''); }
    catch (value) { setError(value); }
  }
  async function send(attempt) {
    setBusy(true); setError(null); setNotice(null);
    try {
      const result = await (attempt.kind === 'transfer'
        ? client.transfer(attempt.body, attempt.key) : client.adjust(attempt.body, attempt.key));
      sessionStorage.removeItem(storageKey);
      setPending(null);
      setNotice({ kind: attempt.kind, result });
      setForm(emptyForm());
      setPage(0);
      setRevision(value => value + 1);
    } catch (value) {
      setError(value);
      if (definitiveErrors.has(value.code)) {
        sessionStorage.removeItem(storageKey);
        setPending(null);
      }
    } finally { setBusy(false); }
  }
  function submit(event) {
    event.preventDefault();
    if (pending || busy) return;
    const common = { variantId: Number(form.variantId), referenceCode: form.referenceCode.trim(), reason: form.reason.trim() };
    const body = mode === 'transfer'
      ? { ...common, sourceWarehouseId: Number(form.sourceWarehouseId),
        destinationWarehouseId: Number(form.destinationWarehouseId), quantity: Number(form.quantity) }
      : { ...common, warehouseId: Number(form.sourceWarehouseId),
        quantityDelta: Number(form.quantity) * (form.direction === 'increase' ? 1 : -1) };
    const attempt = { kind: mode, body, key: crypto.randomUUID() };
    sessionStorage.setItem(storageKey, JSON.stringify(attempt));
    setPending(attempt);
    send(attempt);
  }

  return <>
    <div className="breadcrumb"><span>Quản trị</span><span>/</span><span>Kho</span><span>/</span><span>Biến động tồn</span></div>
    <div className="page-heading"><div><span className="eyebrow">VẬN HÀNH KHO</span><h1>Điều chỉnh & chuyển kho<span className="heading-dot">.</span></h1><p>Kiểm kê chênh lệch hoặc chuyển SKU giữa hai kho; mọi thay đổi đều có chứng từ và lịch sử.</p></div></div>
    <ErrorNotice error={error} onRetry={() => pending ? send(pending) : setRevision(value => value + 1)} />
    {error?.fieldErrors && <ul className="field-error inventory-field-errors">{Object.entries(error.fieldErrors).map(([field, message]) => <li key={field}>{message}</li>)}</ul>}
    {pending && <div className="notice warning" role="status"><strong>Thao tác đang chờ kết quả.</strong><p>{pending.kind === 'transfer' ? 'Chuyển kho' : 'Điều chỉnh tồn'} SKU #{pending.body.variantId}, chứng từ {pending.body.referenceCode}. Gửi lại đúng nội dung và mã yêu cầu để tránh ghi trùng khi mất kết nối.</p><button className="button secondary" disabled={busy} onClick={() => send(pending)}>{busy ? 'Đang kiểm tra…' : 'Kiểm tra và gửi lại'}</button></div>}
    {notice && <div className="notice success" role="status">{notice.result.replayed ? 'Thao tác đã được ghi nhận trước đó.' : 'Đã ghi nhận biến động kho.'} {notice.kind === 'transfer'
      ? `Kho nguồn còn ${notice.result.source.availableQuantity} khả dụng; kho đích có ${notice.result.destination.availableQuantity} khả dụng.`
      : `Tồn tại kho ${notice.result.balance.warehouseCode}: ${notice.result.balance.onHandQuantity}; khả dụng: ${notice.result.balance.availableQuantity}.`}</div>}
    <section className="panel inventory-receipt-panel" aria-label="Tạo biến động kho">
      <div className="panel-heading"><div><span className="eyebrow">CHỨNG TỪ KHO</span><h2>{mode === 'transfer' ? 'Chuyển hàng giữa các kho' : 'Điều chỉnh sau kiểm kê'}</h2></div></div>
      <form className="form-grid" onSubmit={submit}>
        <div className="form-field wide"><label htmlFor="stock-mode">Loại thao tác</label><select id="stock-mode" value={mode} disabled={busy || !!pending} onChange={event => { setMode(event.target.value); setBalances([]); }}><option value="adjustment">Điều chỉnh tồn</option><option value="transfer">Chuyển kho</option></select></div>
        <div className="form-field"><label htmlFor="stock-source">{mode === 'transfer' ? 'Kho nguồn' : 'Kho kiểm kê'}</label><select id="stock-source" required value={form.sourceWarehouseId} disabled={busy || !!pending} onChange={event => change('sourceWarehouseId', event.target.value)}><option value="">Chọn kho</option>{warehouses.map(item => <option key={item.id} value={item.id}>{item.code} — {item.name}</option>)}</select></div>
        {mode === 'transfer' && <div className="form-field"><label htmlFor="stock-destination">Kho đích</label><select id="stock-destination" required value={form.destinationWarehouseId} disabled={busy || !!pending} onChange={event => change('destinationWarehouseId', event.target.value)}><option value="">Chọn kho</option>{warehouses.filter(item => String(item.id) !== form.sourceWarehouseId).map(item => <option key={item.id} value={item.id}>{item.code} — {item.name}</option>)}</select></div>}
        <div className="form-field"><label htmlFor="stock-variant">SKU</label><select id="stock-variant" required value={form.variantId} disabled={busy || !!pending} onChange={event => change('variantId', event.target.value)}><option value="">Chọn SKU</option>{variants.map(item => <option key={item.id} value={item.id}>{item.sku} — {item.productName} ({item.variantName})</option>)}</select></div>
        <div className="form-field"><label htmlFor="stock-search">Tìm SKU khác</label><div className="search-form"><input id="stock-search" type="search" value={variantQuery} disabled={busy || !!pending} onChange={event => setVariantQuery(event.target.value)} placeholder="Mã SKU hoặc tên sản phẩm" /><button type="button" className="button secondary" disabled={busy || !!pending} onClick={searchVariants}>Tìm</button></div></div>
        {balances.length > 0 && <div className="wide stock-balances" role="status">{balances.map(item => <div key={item.warehouseId}><strong>{item.warehouseCode}</strong><span>Tồn: {item.onHandQuantity} · Đã giữ: {item.reservedQuantity} · Khả dụng: {item.availableQuantity}</span></div>)}</div>}
        {mode === 'adjustment' && <div className="form-field"><label htmlFor="stock-direction">Hướng điều chỉnh</label><select id="stock-direction" value={form.direction} disabled={busy || !!pending} onChange={event => change('direction', event.target.value)}><option value="decrease">Giảm tồn</option><option value="increase">Tăng tồn</option></select></div>}
        <div className="form-field"><label htmlFor="stock-quantity">Số lượng</label><input id="stock-quantity" type="number" min="1" max="2147483647" step="1" required value={form.quantity} disabled={busy || !!pending} onChange={event => change('quantity', event.target.value)} /></div>
        <div className="form-field"><label htmlFor="stock-reference">Mã chứng từ</label><input id="stock-reference" required maxLength="100" value={form.referenceCode} disabled={busy || !!pending} onChange={event => change('referenceCode', event.target.value)} placeholder="VD: KK-001 hoặc CK-001" /></div>
        <div className="form-field wide"><label htmlFor="stock-reason">Lý do</label><textarea id="stock-reason" rows="3" required maxLength="1000" value={form.reason} disabled={busy || !!pending} onChange={event => change('reason', event.target.value)} placeholder="Ghi rõ lý do kiểm kê hoặc chuyển kho" /></div>
        <div className="editor-actions wide"><button className="button" disabled={busy || !!pending || !warehouses.length || !variants.length}><Icon name="arrow" size={18} />{busy ? 'Đang lưu…' : 'Xác nhận biến động'}</button></div>
      </form>
    </section>
    <section className="panel inventory-history" aria-label="Lịch sử biến động kho"><div className="panel-heading"><div><span className="eyebrow">NHẬT KÝ KHO</span><h2>Biến động gần đây</h2></div></div>
      {loading ? <Loading text="Đang tải lịch sử kho…" /> : history?.items.length ? <div className="inventory-receipt-list">{history.items.map(item => <article key={item.id} className="inventory-receipt-row"><div><strong>{item.sku}</strong><span>{item.warehouseCode} · {formatDate(item.createdAt)}</span></div><div><strong>{item.quantityDelta > 0 ? '+' : ''}{item.quantityDelta}</strong><span>{item.type} · {item.referenceCode}</span></div><p>{item.reason}</p></article>)}</div> : !error && <p className="muted">Chưa có biến động kho nào.</p>}
      <Pager page={history} busy={loading || busy} onChange={setPage} />
    </section>
  </>;
}
