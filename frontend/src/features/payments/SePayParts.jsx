import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { ErrorNotice } from '../../components/ui';
import { formatMoney } from '../../lib/format';
import { clearPaymentRequest, readPaymentRequest, savePaymentRequest } from './paymentApi';

export function SePayGuidance({ summary }) {
  const payment = summary.items.find(item => item.provider === 'SEPAY_SANDBOX' && item.status === 'PENDING' && !item.isExpired && item.sepay?.qrUrl);
  if (!payment) return null;
  const details = payment.sepay;
  return <section className="panel sepay-guidance" aria-label="Thanh toán SePay Sandbox"><div className="panel-heading"><h2>SePay Sandbox · Thanh toán thử nghiệm</h2></div><div className="order-section-body">
    <p className="notice warning">Dùng chức năng mô phỏng chuyển khoản trong SePay Test mode. Không chuyển tiền bằng app ngân hàng thật.</p>
    <div className="sepay-qr-layout"><img src={details.qrUrl} alt={`QR thanh toán SePay cho khoản ${payment.id}`} width="240" height="240" referrerPolicy="no-referrer" />
      <dl className="payment-receipt-meta"><div><dt>Số tiền thử nghiệm</dt><dd>{formatMoney(payment.amount)}</dd></div><div><dt>Ngân hàng Test</dt><dd>{details.bank}</dd></div><div><dt>Số tài khoản Test</dt><dd>{details.accountNumber}</dd></div><div><dt>Chủ tài khoản Test</dt><dd>{details.accountHolder}</dd></div><div><dt>Nội dung chuyển khoản</dt><dd className="bank-account">{details.transferContent}</dd></div></dl>
    </div><p>Giữ nguyên số tiền và nội dung. Trạng thái cập nhật sau khi hệ thống xác minh giao dịch từ SePay.</p>
  </div></section>;
}

export function SePayReconcile({ selected, client, userId, onUpdated }) {
  const id=selected.id;
  const [pending,setPending]=useState(()=>readPaymentRequest('reconcile',userId,id));
  const [transactionId,setTransactionId]=useState(()=>readPaymentRequest('reconcile',userId,id)?.transactionId || '');
  const [busy,setBusy]=useState(false),[error,setError]=useState(null),[notice,setNotice]=useState('');
  const valid=/^[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}$/.test(transactionId);
  async function submit(event){
    event.preventDefault();if(busy||!valid||!selected.sepay?.canReconcile)return;
    setBusy(true);setError(null);setNotice('');
    const request=pending||{transactionId};
    try{
      savePaymentRequest('reconcile',userId,id,request);setPending(request);
      const updated=await client.reconcile(id,request);
      clearPaymentRequest('reconcile',userId,id);setPending(null);onUpdated(updated);
      setNotice(updated.reconciliation?.processingStatus==='PROCESSED'?'Đã đối soát giao dịch với SePay.':'Đã tra cứu SePay. Giao dịch cần kiểm tra thêm; chưa ghi nhận thêm khoản thu.');
    }catch(failure){setError(failure);if(failure.status===400||failure.status===404&&failure.code==='SEPAY_TRANSACTION_NOT_FOUND'||failure.status===409&&failure.code==='SEPAY_TRANSACTION_MISMATCH'){clearPaymentRequest('reconcile',userId,id);setPending(null);}}
    finally{setBusy(false);}
  }
  return <section className="panel"><div className="panel-heading"><h2>Đối soát SePay Sandbox</h2></div><form className="order-section-body" onSubmit={submit}>
    <p>Tra cứu bằng UUID giao dịch trong SePay API v2. Hệ thống tự đối chiếu với khoản #{id}; không nhập số tiền để xác nhận thủ công.</p>
    <ErrorNotice error={error}/>{notice&&<p role="status" className="notice">{notice}</p>}
    {!selected.sepay?.canReconcile&&<p className="notice warning">Chưa cấu hình API Token SePay trên server.</p>}
    <div className="form-field"><label htmlFor="sepay-transaction-id">UUID giao dịch SePay</label><input id="sepay-transaction-id" value={transactionId} maxLength={36} disabled={busy||!!pending} onChange={event=>setTransactionId(event.target.value.trim())} required autoComplete="off"/><p className="muted small">UUID API v2 khác với mã tham chiếu ngân hàng và ID số trong Webhook.</p></div>
    {pending&&<p className="notice warning">Giữ nguyên UUID đã gửi để kiểm tra lại kết quả sau khi mất phản hồi.</p>}
    <button className="button" disabled={busy||!valid||!selected.sepay?.canReconcile}>{busy?'Đang đối soát…':pending?'Thử lại đối soát':'Đối soát với SePay'}</button>
  </form></section>;
}

export function SePayEventReview({ client }) {
  const [items,setItems]=useState(null),[error,setError]=useState(null),[status,setStatus]=useState('FAILED'),[page,setPage]=useState(0),[busy,setBusy]=useState(false);
  // Lazy load: opening other payment screens never starts a provider/API request.
  async function load(nextStatus=status,nextPage=page){setBusy(true);setError(null);try{const data=await client.events(nextStatus,nextPage);setItems(data.items);setStatus(nextStatus);setPage(nextPage);}catch(failure){setError(failure);}finally{setBusy(false);}}
  return <section className="panel sepay-event-review"><div className="panel-heading"><h2>Giao dịch SePay cần kiểm tra</h2><button className="button secondary" disabled={busy} onClick={()=>load()}>{busy?'Đang tải…':'Tải sự kiện SePay'}</button></div><div className="order-section-body"><ErrorNotice error={error}/>
    {items&&<><label htmlFor="sepay-event-filter">Loại sự kiện</label><select id="sepay-event-filter" value={status} disabled={busy} onChange={event=>load(event.target.value,0)}><option value="FAILED">Không khớp / cần đối soát</option><option value="IGNORED">Không tìm thấy mã thanh toán</option><option value="PROCESSED">Đã xử lý</option></select>
      {items.length?items.map(item=><article key={item.id} className="payment-history-record"><strong>{item.code||'Không có mã'} · {formatMoney(item.amount)}</strong><p>{item.reason||'Đã xử lý'} · {item.reference}</p>{item.paymentId&&<Link className="inline-link" to={`/admin/payments/${item.paymentId}`}>Xem khoản #{item.paymentId}</Link>}</article>):<p>Không có sự kiện trong trang này.</p>}
      <div className="actions"><button className="button secondary" disabled={busy||page===0} onClick={()=>load(status,page-1)}>Trang trước</button><span>Trang {page+1}</span><button className="button secondary" disabled={busy||items.length<25} onClick={()=>load(status,page+1)}>Trang sau</button></div></>}
  </div></section>;
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
