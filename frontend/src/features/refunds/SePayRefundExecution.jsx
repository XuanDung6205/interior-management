import { useRef, useState } from 'react';
import { ErrorNotice } from '../../components/ui';
import { formatMoney } from '../../lib/format';
import { clearRefundRequest, friendlyRefundError, readRefundRequest, saveRefundRequest, sepayExecutionLabel } from './refundApi';

export const sepayClaimed = execution => !!execution?.state && execution.state !== 'NOT_STARTED';
const preclaimRejections = new Set(['SEPAY_REFUND_NOT_CONFIGURED', 'SEPAY_REFUND_TRANSACTION_REQUIRED', 'SEPAY_REFUND_REFERENCE_REQUIRED', 'SEPAY_REFUND_AMOUNT_UNSUPPORTED']);

export default function SePayRefundExecution({ id, actorId, selected, client, onUpdated, onIntent, manualBlocked }) {
  const [pending, setPending] = useState(() => readRefundRequest('sepay', actorId, id));
  const [ack, setAck] = useState(false), [busy, setBusy] = useState(false), [error, setError] = useState(null), [notice, setNotice] = useState('');
  const lock = useRef(false); const execution = selected.sepayExecution;
  if (selected.provider !== 'SEPAY_SANDBOX' || !execution) return null;
  const claimed = sepayClaimed(execution);
  async function perform(sync = false) {
    if (lock.current || manualBlocked || (!sync && !pending && (!execution.canSubmit || !ack)) || sync && !execution.canSync) return;
    lock.current = true; setBusy(true); setError(null); setNotice('');
    try {
      let request = pending;
      if (!sync) { request ||= { acknowledged: true }; saveRefundRequest('sepay', actorId, id, request); setPending(request); onIntent(true); }
      const updated = sync ? await client.sepaySync(id) : await client.sepaySubmit(id, request);
      onUpdated(updated); clearRefundRequest('sepay', actorId, id); setPending(null); setAck(false);
      const result = updated.items.find(item => String(item.id) === String(id));
      // Only the server's verified local success means the refund was completed.
      setNotice(result?.status === 'SUCCEEDED' ? 'SePay đã xác nhận hoàn tiền thành công.' : 'Đã kiểm tra yêu cầu với SePay. Khoản đang chờ hoặc chưa rõ kết quả vẫn giữ ngân sách.');
    } catch (failure) {
      setError(friendlyRefundError(failure));
      if (!sync && [400, 409].includes(failure.status) && preclaimRejections.has(failure.code)) {
        try {
          const updated = await client.detail(id);
          const current = updated.items.find(item => String(item.id) === String(id));
          onUpdated(updated);
          // A rejection alone cannot release an uncertain send: a fresh server
          // read must also prove that this refund has no provider claim.
          if (current?.provider === 'SEPAY_SANDBOX' && current.sepayExecution?.state === 'NOT_STARTED' && !current.sepayExecution.refundId) {
            clearRefundRequest('sepay', actorId, id); setPending(null); setAck(false); onIntent(false);
          }
        } catch { /* Keep the exact send intent while the authoritative state is unknown. */ }
      }
    }
    finally { lock.current = false; setBusy(false); }
  }
  return <section className="sepay-refund-execution" aria-label="Hoàn tiền qua SePay Sandbox"><h3>Hoàn qua SePay Sandbox</h3><p className="notice warning">Chỉ gửi giao dịch thử nghiệm trong SePay Test mode. Số tiền yêu cầu: {formatMoney(selected.amount)}.</p>
    <p><strong>{sepayExecutionLabel(execution.state)}</strong></p>{execution.message && <p>{execution.message}</p>}{execution.refundId && <p className="receipt-reference">Mã hoàn SePay: {execution.refundId}</p>}
    <ErrorNotice error={error} />{notice && <p className="notice" role="status">{notice}</p>}
    {(claimed || pending) && <p className="notice warning">Yêu cầu đã được giữ cho SePay. Không xác nhận chứng từ hoặc hủy thủ công khi chưa rõ kết quả. Gửi lại sẽ dùng cùng yêu cầu trên máy chủ.</p>}
    {manualBlocked && <p className="notice">Hãy hoàn tất đối chiếu thao tác thủ công đã gửi trước khi dùng SePay.</p>}
    {!claimed && !pending && execution.canSubmit && <><label className="checkbox-label receipt-attestation"><input type="checkbox" checked={ack} disabled={busy || manualBlocked} onChange={event => setAck(event.target.checked)} />Tôi đồng ý gửi toàn bộ yêu cầu hoàn này qua SePay Sandbox.</label><button className="button full" disabled={busy || manualBlocked || !ack} onClick={() => perform()}>{busy ? 'Đang gửi SePay…' : 'Gửi yêu cầu hoàn qua SePay Sandbox'}</button></>}
    {pending && <button className="button full" disabled={busy || manualBlocked} onClick={() => perform()}>{busy ? 'Đang kiểm tra…' : 'Thử lại yêu cầu SePay đã gửi'}</button>}
    {execution.canSync && <button className="button secondary full" disabled={busy || manualBlocked} onClick={() => perform(true)}>{busy ? 'Đang kiểm tra…' : 'Kiểm tra kết quả hoàn SePay'}</button>}
  </section>;
}
