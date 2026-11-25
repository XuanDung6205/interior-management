import { useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { ErrorNotice } from '../../components/ui';
import { formatDate, formatMoney } from '../../lib/format';

const reasons = {
  UNKNOWN_PAYMENT_CODE: 'Không tìm thấy mã thanh toán tương ứng.',
  TRANSFER_MISMATCH: 'Thông tin chuyển khoản không khớp khoản thanh toán.',
  MISSING_BANK_REFERENCE: 'Thiếu mã tham chiếu ngân hàng để chống ghi nhận trùng.',
  ADDITIONAL_TRANSFER_REQUIRES_REVIEW: 'Phát sinh giao dịch bổ sung sau khoản đã thu; cần kiểm tra riêng.',
  PAYMENT_STATE_REQUIRES_REVIEW: 'Trạng thái khoản thanh toán cần được kiểm tra.',
  PAYMENT_EXCEEDS_BALANCE: 'Số tiền chuyển vượt khoản phải thu còn lại.',
  REFERENCE_ALREADY_USED: 'Mã tham chiếu ngân hàng đã được ghi nhận ở khoản khác.',
};
export const sepayReason = reason => reasons[reason] || (reason ? 'Sự kiện cần được nhân viên đối chiếu.' : 'Giao dịch đã được xử lý.');
const reviewKey = (actor, id) => `nep-nha.sepay.review.${actor}.${id}`;
const controls = /[\u0000-\u0009\u000B-\u001F\u007F-\u009F]/;
function readReviewDraft(actor, id) {
  try { const value = JSON.parse(sessionStorage.getItem(`${reviewKey(actor, id)}.draft`)); return typeof value === 'string' && value.length <= 1000 ? value : ''; }
  catch { return ''; }
}
function readReview(actor, id) {
  try {
    const value = JSON.parse(sessionStorage.getItem(reviewKey(actor, id)));
    return value?.expectedReviewStatus === 'OPEN' && typeof value.note === 'string' && value.note.trim().length > 0 && value.note.length <= 1000 ? value : null;
  } catch { return null; }
}

function EventReviewRecord({ item, status, client, userId, onReviewed, onReload }) {
  const [pending, setPending] = useState(() => readReview(userId, item.id));
  const [note, setNote] = useState(() => readReview(userId, item.id)?.note || readReviewDraft(userId, item.id));
  const [busy, setBusy] = useState(false), [error, setError] = useState(null);
  const lock = useRef(false);
  const reviewed = item.reviewStatus === 'REVIEWED';
  async function submit(event) {
    event.preventDefault(); if (lock.current || reviewed || !pending && (!note.trim() || note.length > 1000 || controls.test(note))) return;
    lock.current = true; setBusy(true); setError(null);
    try {
      const request = pending || { note: note.trim(), expectedReviewStatus: 'OPEN' };
      try { sessionStorage.setItem(reviewKey(userId, item.id), JSON.stringify(request)); }
      catch { throw new Error('Cần cho phép bộ nhớ phiên để giữ nguyên ghi chú trước khi gửi.'); }
      setPending(request);
      const updated = await client.reviewEvent(item.id, request);
      if (updated?.id !== item.id || updated.reviewStatus !== 'REVIEWED' || updated.reviewNote !== request.note) throw new Error('Chưa đọc được kết quả lưu ghi chú. Giữ nguyên nội dung và thử lại.');
      try { sessionStorage.removeItem(reviewKey(userId, item.id)); sessionStorage.removeItem(`${reviewKey(userId, item.id)}.draft`); } catch { /* Exact replay stays safe. */ }
      setPending(null); onReviewed(updated);
    } catch (failure) {
      setError(failure);
      if (failure.status === 400 && failure.code === 'VALIDATION_ERROR' && Object.hasOwn(failure.fieldErrors || {}, 'note')) {
        try { sessionStorage.setItem(`${reviewKey(userId, item.id)}.draft`, JSON.stringify(note)); sessionStorage.removeItem(reviewKey(userId, item.id)); }
        catch { /* The rejected note remains editable for this mounted form. */ }
        setPending(null);
      }
    }
    finally { lock.current = false; setBusy(false); }
  }
  return <article className="payment-history-record">
    <strong>{item.code || 'Không có mã'} · {formatMoney(item.amount)}</strong>
    <p>{sepayReason(item.reason)}</p>{item.reference && <p className="muted receipt-reference">Tham chiếu: {item.reference}</p>}
    {item.paymentId && <Link className="inline-link" to={`/admin/payments/${item.paymentId}`}>Xem khoản #{item.paymentId}</Link>}
    {reviewed ? <div className="notice"><strong>Đã kiểm tra</strong><p className="refund-text">{item.reviewNote}</p><p className="muted small">Nhân viên #{item.reviewedBy}{item.reviewedAt ? ` · ${formatDate(item.reviewedAt)}` : ''}. Ghi chú đã lưu không thể sửa.</p></div> : status !== 'PROCESSED' && <form aria-label={`Kiểm tra sự kiện #${item.id}`} onSubmit={submit}>
      <div className="form-field"><label htmlFor={`sepay-review-${item.id}`}>Ghi chú kiểm tra sự kiện #{item.id}</label><textarea id={`sepay-review-${item.id}`} rows={3} required maxLength={1000} disabled={busy || !!pending} value={note} onChange={event => setNote(event.target.value)} aria-describedby={`sepay-review-help-${item.id}`} /><p className="muted small" id={`sepay-review-help-${item.id}`}>1–1000 ký tự. Ghi chú chỉ đánh dấu đã kiểm tra, không ghi nhận tiền và không thay đổi dữ liệu gốc.</p></div>
      <ErrorNotice error={error} />
      {controls.test(note) && <p className="field-error">Ghi chú không được chứa ký tự điều khiển; có thể xuống dòng.</p>}
      {pending && <p className="notice warning">Giữ nguyên ghi chú đã gửi khi chưa rõ kết quả.</p>}
      <button className="button secondary" disabled={busy || !pending && (!note.trim() || controls.test(note))}>{busy ? 'Đang lưu…' : pending ? 'Thử lại ghi chú đã gửi' : 'Lưu ghi chú kiểm tra'}</button>
      {error?.status === 409 && <button type="button" className="text-button" disabled={busy} onClick={onReload}>Tải lại sự kiện để đối chiếu</button>}
    </form>}
  </article>;
}

export function SePayEventReview({ client, userId }) {
  const [items, setItems] = useState(null), [error, setError] = useState(null);
  const [status, setStatus] = useState('FAILED'), [reviewStatus, setReviewStatus] = useState('ALL');
  const [page, setPage] = useState(0), [busy, setBusy] = useState(false); const lock = useRef(false);
  // An opened payment list does not itself make provider calls or load event evidence.
  async function load(nextStatus = status, nextPage = page, nextReviewStatus = reviewStatus) {
    if (lock.current) return; lock.current = true; setBusy(true); setError(null);
    try { const data = await client.events(nextStatus, nextPage, nextReviewStatus); setItems(data.items); setStatus(nextStatus); setPage(nextPage); setReviewStatus(nextReviewStatus); }
    catch (failure) { setError(failure); } finally { lock.current = false; setBusy(false); }
  }
  function reviewed(updated) { setItems(previous => previous.map(item => item.id === updated.id ? updated : item)); }
  return <section className="panel sepay-event-review"><div className="panel-heading"><h2>Giao dịch SePay cần kiểm tra</h2><button className="button secondary" disabled={busy} onClick={() => load()}>{busy ? 'Đang tải…' : 'Tải sự kiện SePay'}</button></div><div className="order-section-body"><ErrorNotice error={error} />
    {items && <><div className="payment-list-filters"><div className="form-field"><label htmlFor="sepay-event-filter">Loại sự kiện</label><select id="sepay-event-filter" value={status} disabled={busy} onChange={event => load(event.target.value, 0)}><option value="FAILED">Không khớp / cần đối soát</option><option value="IGNORED">Không tìm thấy mã thanh toán</option><option value="PROCESSED">Đã xử lý</option></select></div><div className="form-field"><label htmlFor="sepay-review-filter">Tình trạng kiểm tra</label><select id="sepay-review-filter" value={reviewStatus} disabled={busy} onChange={event => load(status, 0, event.target.value)}><option value="ALL">Tất cả</option><option value="OPEN">Chưa kiểm tra</option><option value="REVIEWED">Đã kiểm tra</option></select></div></div>
      {items.length ? items.map(item => <EventReviewRecord key={`${userId}-${item.id}`} item={item} status={status} client={client} userId={userId} onReviewed={reviewed} onReload={() => load()} />) : <p>Không có sự kiện trong trang này.</p>}
      <div className="actions"><button className="button secondary" disabled={busy || page === 0} onClick={() => load(status, page - 1)}>Trang trước</button><span>Trang {page + 1}</span><button className="button secondary" disabled={busy || items.length < 25} onClick={() => load(status, page + 1)}>Trang sau</button></div></>}
  </div></section>;
}

export function SePayJobs({ client }) {
  const [data, setData] = useState(null), [result, setResult] = useState(null), [error, setError] = useState(null), [busy, setBusy] = useState(false);
  const lock = useRef(false);
  async function perform(run = false) {
    if (lock.current) return; lock.current = true; setBusy(true); setError(null);
    try { if (run) setResult(await client.runJobs()); setData(await client.jobs()); }
    catch (failure) { setError(failure); } finally { lock.current = false; setBusy(false); }
  }
  return <section className="panel"><div className="panel-heading"><h2>Đối soát tự động SePay</h2><button className="button secondary" disabled={busy} onClick={() => perform()}>{busy ? 'Đang kiểm tra…' : 'Xem job SePay'}</button></div><div className="order-section-body"><ErrorNotice error={error} />
    <p>Job kiểm tra giao dịch và yêu cầu hết hạn theo dữ liệu máy chủ. Khi SePay chưa xác minh được, hệ thống hoãn hủy đơn.</p>
    {data && <><p>{data.enabled ? 'Đối soát tự động đang bật.' : 'Đối soát tự động đang tắt.'} Yêu cầu mới có thời hạn {Math.ceil(data.ttlSeconds / 60)} phút; chu kỳ {data.intervalSeconds} giây.</p><dl className="payment-receipt-meta"><div><dt>Đang chờ</dt><dd>{data.queue?.pending ?? '—'}</dd></div><div><dt>Đến lượt kiểm tra</dt><dd>{data.queue?.due ?? '—'}</dd></div><div><dt>Đang được xử lý</dt><dd>{data.queue?.leased ?? '—'}</dd></div></dl>{data.canRun !== true && <p className="notice warning">Chưa cấu hình API Token SePay trên server để chạy đối soát.</p>}<button className="button secondary" disabled={busy || data.canRun !== true} onClick={() => perform(true)}>Chạy một lượt đối soát</button></>}
    {result && <div className="notice" role="status"><p>Đã kiểm tra {result.scanned} khoản · đối soát {result.reconciled} · hết hạn {result.expired}.</p><p>Hoãn {result.deferred} khoản · lỗi {result.failed}. Khoản hoãn cần kiểm tra lại ở lượt sau.</p></div>}
  </div></section>;
}
