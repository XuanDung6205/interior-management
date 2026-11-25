import { lazy, Suspense, useEffect, useRef, useState } from 'react';
import { ErrorNotice, Loading } from '../../components/ui';
import { formatDate, formatMoney } from '../../lib/format';
import { clearPaymentRequest, readPaymentRequest, savePaymentRequest } from './paymentApi';
const EventReviewPanel = lazy(() => import('./SePayOperations').then(module => ({ default: module.SePayEventReview })));
const JobsPanel = lazy(() => import('./SePayOperations').then(module => ({ default: module.SePayJobs })));
export function SePayEventReview(props) { return <Suspense fallback={<Loading text="Đang tải công cụ kiểm tra SePay…"/>}><EventReviewPanel {...props}/></Suspense>; }
export function SePayJobs(props) { return <Suspense fallback={<Loading text="Đang tải công cụ đối soát SePay…"/>}><JobsPanel {...props}/></Suspense>; }

export function SePayGuidance({ summary }) {
  const payment = summary.items.find(item => item.provider === 'SEPAY_SANDBOX' && item.status === 'PENDING' && !item.isExpired && item.sepay?.qrUrl);
  if (!payment) return null;
  const details = payment.sepay;
  return <section className="panel sepay-guidance" aria-label="Thanh toán SePay Sandbox"><div className="panel-heading"><h2>SePay Sandbox · Thanh toán thử nghiệm</h2></div><div className="order-section-body">
    <p className="notice warning">Dùng chức năng mô phỏng chuyển khoản trong SePay Test mode. Không chuyển tiền bằng app ngân hàng thật.</p>
    <div className="sepay-qr-layout"><img src={details.qrUrl} alt={`QR thanh toán SePay cho khoản ${payment.id}`} width="240" height="240" referrerPolicy="no-referrer" />
      <dl className="payment-receipt-meta"><div><dt>Số tiền thử nghiệm</dt><dd>{formatMoney(payment.amount)}</dd></div><div><dt>Ngân hàng Test</dt><dd>{details.bank}</dd></div><div><dt>Số tài khoản Test</dt><dd>{details.accountNumber}</dd></div><div><dt>Chủ tài khoản Test</dt><dd>{details.accountHolder}</dd></div><div><dt>Nội dung chuyển khoản</dt><dd className="bank-account">{details.transferContent}</dd></div></dl>
    </div><p>Giữ nguyên số tiền và nội dung. Trạng thái cập nhật sau khi hệ thống xác minh giao dịch từ SePay.</p>
    {payment.expiresAt && <><p className="notice">Hạn thanh toán thử nghiệm: {formatDate(payment.expiresAt)}.</p><p className="muted small">Hệ thống chỉ hủy đơn khi xác minh không có giao dịch phù hợp và đơn vẫn đủ điều kiện hủy. Khi chưa đối soát được, hãy làm mới để xem trạng thái từ máy chủ.</p></>}
  </div></section>;
}

export function SePayReconcile({ selected, client, userId, onUpdated }) {
  const id=selected.id;
  const [pending,setPending]=useState(()=>readPaymentRequest('reconcile',userId,id));
  const [transactionId,setTransactionId]=useState(()=>readPaymentRequest('reconcile',userId,id)?.transactionId || '');
  const [busy,setBusy]=useState(false),[error,setError]=useState(null),[notice,setNotice]=useState('');
  const lock=useRef(false);
  const valid=/^[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}$/.test(transactionId);
  async function submit(event){
    event.preventDefault();if(lock.current||!valid||!selected.sepay?.canReconcile)return;
    lock.current=true;
    setBusy(true);setError(null);setNotice('');
    const request=pending||{transactionId};
    try{
      savePaymentRequest('reconcile',userId,id,request);setPending(request);
      const updated=await client.reconcile(id,request);
      clearPaymentRequest('reconcile',userId,id);setPending(null);onUpdated(updated);
      setNotice(updated.reconciliation?.processingStatus==='PROCESSED'?'Đã đối soát giao dịch với SePay.':'Đã tra cứu SePay. Giao dịch cần kiểm tra thêm; chưa ghi nhận thêm khoản thu.');
    }catch(failure){setError(failure);if(failure.status===400||failure.status===404&&failure.code==='SEPAY_TRANSACTION_NOT_FOUND'||failure.status===409&&failure.code==='SEPAY_TRANSACTION_MISMATCH'){clearPaymentRequest('reconcile',userId,id);setPending(null);}}
    finally{lock.current=false;setBusy(false);}
  }
  async function byCode(){
    if(lock.current||pending||!selected.sepay?.canReconcile)return;
    lock.current=true;setBusy(true);setError(null);setNotice('');
    try{
      const updated=await client.reconcileCode(id);onUpdated(updated);
      const result=updated.reconciliationSummary;
      setNotice(result?.complete ? `Đã tra cứu ${result.checked} giao dịch; khớp ${result.matched}. Trạng thái khoản thu đã được máy chủ đối chiếu.` : 'Kết quả chưa đầy đủ. Chưa thể kết luận không có giao dịch; hãy kiểm tra lại sau.');
    }catch(failure){setError(failure);}finally{lock.current=false;setBusy(false);}
  }
  return <section className="panel"><div className="panel-heading"><h2>Đối soát SePay Sandbox</h2></div><div className="order-section-body">
    <p>Tra cứu mã thanh toán cố định từ máy chủ. Giao dịch được kiểm tra lại trước khi ghi nhận tiền.</p>
    {selected.sepay?.code&&<button className="button secondary" disabled={busy||!!pending||!selected.sepay?.canReconcile} onClick={byCode}>Tra cứu theo mã {selected.sepay.code}</button>}
    <form onSubmit={submit}>
    <p>Tra cứu bằng UUID giao dịch trong SePay API v2. Hệ thống tự đối chiếu với khoản #{id}; không nhập số tiền để xác nhận thủ công.</p>
    <ErrorNotice error={error}/>{notice&&<p role="status" className="notice">{notice}</p>}
    {!selected.sepay?.canReconcile&&<p className="notice warning">Chưa cấu hình API Token SePay trên server.</p>}
    <div className="form-field"><label htmlFor="sepay-transaction-id">UUID giao dịch SePay</label><input id="sepay-transaction-id" value={transactionId} maxLength={36} disabled={busy||!!pending} onChange={event=>setTransactionId(event.target.value.trim())} required autoComplete="off"/><p className="muted small">UUID API v2 khác với mã tham chiếu ngân hàng và ID số trong Webhook.</p></div>
    {pending&&<p className="notice warning">Giữ nguyên UUID đã gửi để kiểm tra lại kết quả sau khi mất phản hồi.</p>}
    <button className="button" disabled={busy||!valid||!selected.sepay?.canReconcile}>{busy?'Đang đối soát…':pending?'Thử lại đối soát':'Đối soát với SePay'}</button>
  </form></div></section>;
}

/** Bounded, non-overlapping status reads; paused when the tab is hidden. */
export function useSePayPolling({ active, client, id, lock, onSummary }) {
  const [warning,setWarning]=useState('');
  useEffect(()=>{
    if(!active){setWarning('');return;}
    let stopped=false,timer,controller;
    const deadline=Date.now()+15*60*1000;
    function schedule(){clearTimeout(timer);if(!stopped&&!document.hidden)timer=setTimeout(tick,5000);}
    async function tick(){
      if(stopped||document.hidden)return;
      if(Date.now()>=deadline){setWarning('Đã tạm dừng cập nhật tự động. Bấm “Làm mới thanh toán” để kiểm tra tiếp.');return;}
      if(lock.current){schedule();return;}
      lock.current=true;controller=new AbortController();let timeout,abort;
      const signal=controller.signal;
      const limit=new Promise((_resolve,reject)=>{
        abort=()=>reject(new DOMException('Aborted','AbortError'));signal.addEventListener('abort',abort,{once:true});
        timeout=setTimeout(()=>{reject(new DOMException('Status read timed out','TimeoutError'));controller.abort();},Math.min(10000,deadline-Date.now()));
      });
      try{const value=await Promise.race([client.summary(id,signal),limit]);if(!stopped&&!signal.aborted){onSummary(value);setWarning('');}}
      catch(failure){if(!stopped&&failure.name!=='AbortError'){setWarning('Chưa cập nhật được trạng thái. Bạn có thể làm mới để kiểm tra.');if([401,403].includes(failure.status))stopped=true;}}
      finally{clearTimeout(timeout);signal.removeEventListener('abort',abort);lock.current=false;schedule();}
    }
    function visibility(){clearTimeout(timer);if(document.hidden)controller?.abort();else schedule();}
    document.addEventListener('visibilitychange',visibility);schedule();
    return()=>{stopped=true;clearTimeout(timer);controller?.abort();document.removeEventListener('visibilitychange',visibility);};
  },[active,client,id,lock,onSummary]);
  return warning;
}
