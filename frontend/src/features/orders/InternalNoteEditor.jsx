import { useEffect, useRef, useState } from 'react';
import { ErrorNotice } from '../../components/ui';
import { formatDate } from '../../lib/format';

export default function InternalNoteEditor({ order, client, busy, perform, onUpdated }) {
  const [draft, setDraft] = useState(order.internalNote ?? '');
  const [expected, setExpected] = useState(order.internalNote ?? null);
  const [error, setError] = useState(null);
  const [conflict, setConflict] = useState(false);
  const [latest, setLatest] = useState(null);
  const [saved, setSaved] = useState(false);
  const errorRef = useRef(null);
  const value = draft.trim() ? draft : null;
  const mustReview = conflict || (order.internalNote ?? null) !== expected;
  useEffect(() => { if (error) errorRef.current?.focus(); }, [error]);
  async function save(event) {
    event.preventDefault();
    if (busy || mustReview || value === expected || draft.length > 5000) return;
    await perform(async () => {
      setError(null); setSaved(false);
      try {
        const updated = await client.note(order.id, value, expected);
        setDraft(updated.internalNote ?? ''); setExpected(updated.internalNote ?? null); setConflict(false); setLatest(null); setSaved(true); onUpdated(updated);
      } catch (failure) { setError(failure); if (failure.code === 'INTERNAL_NOTE_CHANGED') setConflict(true); }
    });
  }
  async function loadLatest() {
    await perform(async () => {
      setError(null); setSaved(false);
      try { const updated = await client.detail(order.id); setLatest(updated); onUpdated(updated); }
      catch (failure) { setError(failure); }
    });
  }
  function acceptLatest(keepDraft) {
    if (!latest) return;
    setExpected(latest.internalNote ?? null);
    if (!keepDraft) setDraft(latest.internalNote ?? '');
    setLatest(null); setConflict(false); setError(null); setSaved(false);
  }
  return <section className="panel internal-note-panel" aria-label="Trình soạn ghi chú nội bộ"><div className="panel-heading"><div><h2>Ghi chú nội bộ</h2><p className="muted small">Chỉ dành cho nhân viên. Không xuất hiện trong lịch sử của khách.</p></div></div><form onSubmit={save} className="order-section-body"><div ref={errorRef} tabIndex="-1"><ErrorNotice error={error} /></div>{saved && <div className="notice success" role="status">Đã lưu ghi chú nội bộ.</div>}<label htmlFor="internal-note">Ghi chú nội bộ</label><textarea id="internal-note" value={draft} maxLength={5000} rows={5} disabled={busy} onChange={event => { setDraft(event.target.value); setSaved(false); }} aria-describedby={`internal-note-hint${error?.fieldErrors?.internalNote ? ' internal-note-error' : ''}`} aria-invalid={!!error?.fieldErrors?.internalNote} /><p id="internal-note-hint" className="muted small">{draft.length} / 5.000 ký tự. Để trống để xóa ghi chú.</p>{error?.fieldErrors?.internalNote && <span className="field-error" id="internal-note-error">{error.fieldErrors.internalNote}</span>}{mustReview && <div className="notice warning"><strong>Có thay đổi từ một phiên làm việc khác.</strong><p>Bản nháp của bạn vẫn được giữ lại. Tải ghi chú hiện tại và đối chiếu trước khi lưu.</p><button type="button" className="text-button" disabled={busy} onClick={loadLatest}>Tải ghi chú mới nhất</button></div>}{latest && <div className="note-comparison"><h3>Ghi chú mới nhất trên đơn</h3><p className="latest-note">{latest.internalNote || 'Chưa có ghi chú nội bộ.'}</p>{latest.updatedAt && <p className="muted small">Cập nhật {formatDate(latest.updatedAt)}</p>}<p className="small muted">Chọn bản bạn muốn tiếp tục. Giữ bản nháp chỉ chuẩn bị để lưu; chưa thay đổi dữ liệu trên máy chủ.</p><div className="actions"><button type="button" className="button secondary" disabled={busy} onClick={() => acceptLatest(false)}>Dùng ghi chú mới nhất</button><button type="button" className="button secondary" disabled={busy} onClick={() => acceptLatest(true)}>Tiếp tục với bản nháp</button></div></div>}<button className="button" disabled={busy || mustReview || value === expected || draft.length > 5000}>{busy ? 'Đang xử lý…' : 'Lưu ghi chú nội bộ'}</button></form></section>;
}
