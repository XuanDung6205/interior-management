import { useEffect, useMemo, useState } from 'react';
import { useCommerce } from '../../lib/session';
import { ErrorNotice, Icon, Loading, Pager } from '../../components/ui';
import { formatDate } from '../../lib/format';
import { inventoryApi } from './inventoryApi';

const emptyForm = () => ({ warehouseId: '', variantId: '', quantity: '', referenceCode: '', reason: '' });

export default function InventoryReceiptPage() {
  const { api, session } = useCommerce();
  const client = useMemo(() => inventoryApi(api), [api]);
  const storageKey = `tv4.receipt.pending.${session.user.id}`;
  const [pending, setPending] = useState(() => {
    try { return JSON.parse(sessionStorage.getItem(storageKey)); } catch { return null; }
  });
  const [form, setForm] = useState(emptyForm);
  const [warehouses, setWarehouses] = useState([]);
  const [variants, setVariants] = useState([]);
  const [variantQuery, setVariantQuery] = useState('');
  const [page, setPage] = useState(0);
  const [history, setHistory] = useState(null);
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
  }, [client, revision]);
  useEffect(() => {
    let active = true;
    setLoading(true);
    client.receipts(page).then(value => { if (active) setHistory(value); })
      .catch(value => { if (active) setError(value); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [client, page, revision]);

  function change(field, value) { setForm(current => ({ ...current, [field]: value })); }
  async function searchVariants(event) {
    event.preventDefault(); setError(null);
    try { setVariants((await client.variants(variantQuery.trim())).items); change('variantId', ''); }
    catch (value) { setError(value); }
  }
  async function sendReceipt(attempt) {
    setBusy(true); setError(null); setNotice(null);
    try {
      const result = await client.receive(attempt.body, attempt.key);
      sessionStorage.removeItem(storageKey);
      setPending(null);
      setForm(emptyForm());
      setNotice(result);
      setPage(0);
      setRevision(value => value + 1);
    } catch (value) {
      setError(value);
      if (['INVALID_RECEIPT', 'VALIDATION_ERROR', 'WAREHOUSE_INACTIVE', 'SKU_INACTIVE',
        'STOCK_LIMIT', 'STOCK_CHANGED', 'NOT_FOUND'].includes(value.code)) {
        sessionStorage.removeItem(storageKey);
        setPending(null);
        setForm(Object.fromEntries(Object.entries(attempt.body).map(([key, entry]) => [key, String(entry)])));
      }
    }
    finally { setBusy(false); }
  }
  function submit(event) {
    event.preventDefault();
    if (pending || busy) return;
    const attempt = { key: crypto.randomUUID(), body: {
      warehouseId: Number(form.warehouseId), variantId: Number(form.variantId),
      quantity: Number(form.quantity), referenceCode: form.referenceCode.trim(), reason: form.reason.trim(),
    } };
    sessionStorage.setItem(storageKey, JSON.stringify(attempt));
    setPending(attempt);
    sendReceipt(attempt);
  }

  return <>
    <div className="breadcrumb"><span>Quản trị</span><span>/</span><span>Kho</span><span>/</span><span>Nhập hàng</span></div>
    <div className="page-heading"><div><span className="eyebrow">VẬN HÀNH KHO</span><h1>Nhập hàng<span className="heading-dot">.</span></h1><p>Ghi nhận hàng theo SKU và kho. Mỗi phiếu nhập được lưu vào lịch sử biến động.</p></div></div>
    <ErrorNotice error={error} onRetry={() => pending ? sendReceipt(pending) : setRevision(value => value + 1)} />
    {error?.fieldErrors && Object.keys(error.fieldErrors).length > 0 && <ul className="field-error inventory-field-errors">{Object.entries(error.fieldErrors).map(([field, message]) => <li key={field}>{message}</li>)}</ul>}
    {pending && <div className="notice warning" role="status"><strong>Phiếu nhập đang chờ kết quả.</strong><p>Kho #{pending.body?.warehouseId}, SKU #{pending.body?.variantId}, số lượng {pending.body?.quantity}, chứng từ {pending.body?.referenceCode}. Giữ nguyên nội dung và mã yêu cầu để tránh nhập hàng hai lần nếu kết nối bị gián đoạn.</p><button className="button secondary" disabled={busy} onClick={() => sendReceipt(pending)}>{busy ? 'Đang kiểm tra…' : 'Kiểm tra và gửi lại'}</button></div>}
    {notice && <div className="notice success" role="status">{notice.replayed ? 'Phiếu đã được ghi nhận trước đó.' : 'Đã nhập hàng.'} SKU {notice.sku}: +{notice.receivedQuantity}. Tồn tại kho {notice.warehouseCode}: {notice.onHandQuantity}; khả dụng: {notice.availableQuantity}.</div>}
    <section className="panel inventory-receipt-panel" aria-label="Tạo phiếu nhập kho">
      <div className="panel-heading"><div><span className="eyebrow">PHIẾU NHẬP</span><h2>Ghi nhận hàng về kho</h2></div></div>
      <form onSubmit={submit} className="form-grid">
        <div className="form-field"><label htmlFor="receipt-warehouse">Kho nhận hàng</label><select id="receipt-warehouse" required value={form.warehouseId} onChange={event => change('warehouseId', event.target.value)} disabled={busy || !!pending}><option value="">Chọn kho</option>{warehouses.map(warehouse => <option key={warehouse.id} value={warehouse.id}>{warehouse.code} — {warehouse.name}</option>)}</select></div>
        <div className="form-field"><label htmlFor="receipt-variant">SKU</label><select id="receipt-variant" required value={form.variantId} onChange={event => change('variantId', event.target.value)} disabled={busy || !!pending}><option value="">Chọn SKU</option>{variants.map(variant => <option key={variant.id} value={variant.id}>{variant.sku} — {variant.productName} ({variant.variantName})</option>)}</select></div>
        <div className="form-field wide"><label htmlFor="receipt-search">Tìm SKU khác</label><div className="search-form"><input id="receipt-search" type="search" value={variantQuery} onChange={event => setVariantQuery(event.target.value)} disabled={busy || !!pending} placeholder="Mã SKU hoặc tên sản phẩm" /><button type="button" onClick={searchVariants} className="button secondary" disabled={busy || !!pending}>Tìm</button></div></div>
        <div className="form-field"><label htmlFor="receipt-quantity">Số lượng nhập</label><input id="receipt-quantity" type="number" min="1" max="2147483647" step="1" required value={form.quantity} onChange={event => change('quantity', event.target.value)} disabled={busy || !!pending} /></div>
        <div className="form-field"><label htmlFor="receipt-reference">Mã chứng từ</label><input id="receipt-reference" required maxLength="100" value={form.referenceCode} onChange={event => change('referenceCode', event.target.value)} disabled={busy || !!pending} placeholder="VD: PN-2026-001" /></div>
        <div className="form-field wide"><label htmlFor="receipt-reason">Ghi chú nguồn hàng</label><textarea id="receipt-reason" rows="3" required maxLength="1000" value={form.reason} onChange={event => change('reason', event.target.value)} disabled={busy || !!pending} placeholder="VD: Nhập từ nhà cung cấp" /></div>
        <div className="editor-actions wide"><button className="button" disabled={busy || !!pending || !warehouses.length || !variants.length}><Icon name="plus" size={18} />{busy ? 'Đang nhập…' : 'Xác nhận nhập kho'}</button></div>
      </form>
    </section>
    <section className="panel inventory-history" aria-label="Lịch sử nhập kho"><div className="panel-heading"><div><span className="eyebrow">BIẾN ĐỘNG KHO</span><h2>Phiếu nhập gần đây</h2></div></div>
      {loading ? <Loading text="Đang tải lịch sử nhập…" /> : history?.items.length ? <div className="inventory-receipt-list">{history.items.map(item => <article key={item.id} className="inventory-receipt-row"><div><strong>{item.sku}</strong><span>{item.warehouseCode} · {formatDate(item.createdAt)}</span></div><div><strong>+{item.quantity}</strong><span>{item.referenceCode}</span></div><p>{item.reason}</p></article>)}</div> : !error && <p className="muted">Chưa có phiếu nhập nào.</p>}
      <Pager page={history} busy={loading || busy} onChange={setPage} />
    </section>
  </>;
}
