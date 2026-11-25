package org.example.eshop.commerce.payment;

import org.example.eshop.commerce.common.CommerceException;
import org.example.eshop.commerce.order.*;
import org.example.eshop.commerce.port.OrderAuditPort;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Persist an immutable staff-approved intent, release locks, then contact Sandbox. */
@Service
public class SePayRefundService {
    private final OrderStore store;private final OrderAdminAccess access;private final RefundQueryService queries;
    private final RefundPolicy policy;private final OrderAuditPort audit;private final SePayApiClient api;
    private final SePayRefundSettings settings;private final ObjectMapper mapper;private final Clock clock;
    private final TransactionTemplate tx;
    private record Intent(long id,long order,long actor,String transaction,String bank,String reference,long amount,String key,String refundId,String lease,int attempt){}
    public SePayRefundService(OrderStore store,OrderAdminAccess access,RefundQueryService queries,RefundPolicy policy,OrderAuditPort audit,SePayApiClient api,SePayRefundSettings settings,ObjectMapper mapper,Clock clock,PlatformTransactionManager manager){
        this.store=store;this.access=access;this.queries=queries;this.policy=policy;this.audit=audit;this.api=api;this.settings=settings;this.mapper=mapper;this.clock=clock;
        tx=new TransactionTemplate(manager);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public Map<String,Object> submit(long id,Boolean acknowledged){
        long actor=access.requireStaff();requireConfigured();if(!Boolean.TRUE.equals(acknowledged))throw CommerceException.invalid(Map.of("acknowledged","Cần xác nhận đã đối chiếu khoản hoàn và muốn gửi lệnh SePay Sandbox."));
        execute(tx.execute(status->claim(id,actor,true,true)));return queries.detail(id);
    }
    public Map<String,Object> sync(long id){long actor=access.requireStaff();requireConfigured();execute(tx.execute(status->claim(id,actor,false,true)));return queries.detail(id);}
    private void requireConfigured(){if(!settings.enabled())throw CommerceException.conflict("SEPAY_REFUND_NOT_CONFIGURED","Chưa bật hoàn qua API SePay Sandbox hoặc chưa cấu hình API Token.");}

    /** The scheduler never discovers unsubmitted refunds and never creates fresh intents. */
    public Map<String,Integer> runDue(){
        var counts=new LinkedHashMap<String,Integer>();for(String key:List.of("scanned","succeeded","pending","unknown","failed"))counts.put(key,0);
        if(!settings.enabled())return counts;
        var rows=store.rows("SELECT id,sepay_requested_by AS actor FROM refunds WHERE provider=? AND status='PENDING' AND sepay_request_key IS NOT NULL AND sepay_execution_state IN ('UNKNOWN','PENDING') AND (sepay_retry_at IS NULL OR sepay_retry_at<=?) AND (sepay_lease_until IS NULL OR sepay_lease_until<=?) ORDER BY sepay_retry_at,id LIMIT 5",SePaySettings.PROVIDER,now(),now());
        for(var row:rows){counts.compute("scanned",(k,v)->v+1);try{
            if(!(row.get("actor") instanceof Number actor))throw new IllegalStateException("Missing refund approver");
            var intent=tx.execute(status->claim(((Number)row.get("id")).longValue(),actor.longValue(),false,false));String outcome=execute(intent);
            String bucket=switch(outcome){case "SUCCEEDED"->"succeeded";case "FAILED","REJECTED"->"failed";case "PENDING","SKIPPED"->"pending";default->"unknown";};counts.compute(bucket,(k,v)->v+1);
        }catch(RuntimeException error){counts.compute("failed",(k,v)->v+1);}}
        return counts;
    }
    private Intent claim(long id,long actor,boolean allowNew,boolean force){
        long orderId=queries.orderId(id);var order=access.lock(orderId,actor);var payments=queries.payments(orderId,true);
        var hint=store.rows("SELECT return_request_id AS returned FROM refunds WHERE id=?",id).get(0);Long returned=hint.get("returned")==null?null:((Number)hint.get("returned")).longValue();lockReturn(orderId,returned);
        var refunds=queries.refunds(orderId,true);var refund=refunds.stream().filter(r->number(r.get("id"))==id).findFirst().orElseThrow(()->CommerceException.notFound("Không tìm thấy yêu cầu hoàn tiền."));
        var payment=payments.stream().filter(p->number(p.get("id"))==number(refund.get("paymentId"))).findFirst().orElseThrow(()->RefundPolicy.conflict("PAYMENT_NOT_REFUNDABLE"));
        if(!SePaySettings.PROVIDER.equals(refund.get("provider"))||!SePaySettings.PROVIDER.equals(payment.get("provider")))throw RefundPolicy.conflict("PAYMENT_NOT_REFUNDABLE");
        if(!SePayRefundSettings.claimed(refund)){
            if(!allowNew)throw CommerceException.conflict("SEPAY_REFUND_NOT_SUBMITTED","Khoản hoàn chưa được nhân viên gửi qua SePay Sandbox.");
            if(!"PENDING".equals(refund.get("status"))||!RefundPolicy.manual(payment)||!Objects.equals(refund.get("currency"),payment.get("currency")))throw RefundPolicy.conflict("PAYMENT_NOT_REFUNDABLE");
            var amount=RefundPolicy.money(refund.get("amount"));if(!"VND".equals(refund.get("currency"))||amount.stripTrailingZeros().scale()>0||amount.signum()<=0||amount.compareTo(new BigDecimal("499000000"))>0)throw CommerceException.conflict("SEPAY_REFUND_AMOUNT_UNSUPPORTED","Hoàn qua SePay Sandbox cần số VND nguyên dương, tối đa 499.000.000.");
            if(policy.held(refunds,number(payment.get("id")),null).compareTo(RefundPolicy.money(payment.get("amount")))>0||policy.held(refunds,null,null).compareTo(collected(payments))>0)throw RefundPolicy.conflict("REFUND_RECONCILIATION_REQUIRED");
            policy.validateReturn(order,refunds.stream().filter(r->number(r.get("id"))!=id).toList(),returned,amount);
            if(!SePayRefundSettings.uuid(payment.get("sepayTransactionId"))||!SePayRefundSettings.uuid(payment.get("sepayBankAccountId")))throw CommerceException.conflict("SEPAY_REFUND_TRANSACTION_REQUIRED","Cần đối soát mã thanh toán để xác minh UUID giao dịch và tài khoản SePay trước khi hoàn.");
            String reference=originalReference(number(payment.get("id"))),key="interior-refund-"+UUID.randomUUID();
            store.jdbc().update("UPDATE refunds SET sepay_execution_state='UNKNOWN',sepay_request_key=?,sepay_transaction_id=?,sepay_bank_account_id=?,sepay_original_reference=?,sepay_requested_by=?,sepay_requested_at=?,sepay_retry_at=?,updated_at=? WHERE id=?",key,payment.get("sepayTransactionId"),payment.get("sepayBankAccountId"),reference,actor,now(),now(),now(),id);
            audit.append(actor,orderId,"SEPAY_REFUND_REQUESTED",Map.of("refundId",id,"status","PENDING"),Map.of("refundId",id,"executionState","UNKNOWN","amount",refund.get("amount")));
        }else if(!"PENDING".equals(refund.get("status")))return null;
        String lease=UUID.randomUUID().toString();var now=now();
        int changed=store.jdbc().update("UPDATE refunds SET sepay_lease_token=?,sepay_lease_until=?,sepay_attempts=sepay_attempts+1 WHERE id=? AND status='PENDING' AND sepay_request_key IS NOT NULL AND (sepay_lease_until IS NULL OR sepay_lease_until<=?) AND (? OR sepay_retry_at IS NULL OR sepay_retry_at<=?)",lease,now.plusSeconds(60),id,now,force,now);
        if(changed!=1)return null;
        var r=store.jdbc().queryForMap("SELECT * FROM refunds WHERE id=?",id);
        return new Intent(id,orderId,number(r.get("sepay_requested_by")),text(r,"sepay_transaction_id"),text(r,"sepay_bank_account_id"),text(r,"sepay_original_reference"),((BigDecimal)r.get("amount")).longValueExact(),text(r,"sepay_request_key"),(String)r.get("sepay_refund_id"),lease,((Number)r.get("sepay_attempts")).intValue());
    }
    private String execute(Intent intent){
        if(intent==null)return "SKIPPED";
        SePayRefundResponse.Result result;
        try{var response=intent.refundId()==null?api.refundRequest(intent.transaction(),intent.amount(),intent.key()):api.refundDetails(intent.bank(),intent.refundId());result=SePayRefundResponse.classify(response.status(),response.body(),intent.amount(),intent.reference(),intent.refundId(),intent.transaction());}
        catch(RuntimeException unavailable){result=new SePayRefundResponse.Result("UNKNOWN",null,"PROVIDER_UNAVAILABLE");}
        final var outcome=result;return tx.execute(status->finish(intent,outcome));
    }
    private String finish(Intent intent,SePayRefundResponse.Result result){
        access.lock(intent.order(),intent.actor());queries.payments(intent.order(),true);var rows=queries.refunds(intent.order(),true);var row=rows.stream().filter(r->number(r.get("id"))==intent.id()).findFirst().orElseThrow();
        String token=store.jdbc().queryForObject("SELECT sepay_lease_token FROM refunds WHERE id=?",String.class,intent.id());
        if(!"PENDING".equals(row.get("status"))||!Objects.equals(intent.key(),row.get("sepayRequestKey"))||!Objects.equals(intent.lease(),token))return "SKIPPED";
        String state=result.state(),providerId=result.refundId()==null?(String)row.get("sepayRefundId"):result.refundId();var now=now();
        try{
            if("SUCCEEDED".equals(state))store.jdbc().update("UPDATE refunds SET status='SUCCEEDED',provider_refund_id=?,processed_by=?,completed_at=?,sepay_refund_id=?,sepay_execution_state='SUCCEEDED',sepay_error_code=NULL,sepay_checked_at=?,sepay_retry_at=NULL,sepay_lease_token=NULL,sepay_lease_until=NULL,updated_at=? WHERE id=?",providerId,intent.actor(),now,providerId,now,now,intent.id());
            else if(result.terminal())store.jdbc().update("UPDATE refunds SET status='FAILED',processed_by=?,completed_at=?,sepay_refund_id=?,sepay_execution_state=?,sepay_error_code=?,sepay_checked_at=?,sepay_retry_at=NULL,sepay_lease_token=NULL,sepay_lease_until=NULL,updated_at=? WHERE id=?",intent.actor(),now,providerId,state,result.error(),now,now,intent.id());
            else pending(intent,state,providerId,result.error(),now);
        }catch(DuplicateKeyException conflict){state="UNKNOWN";pending(intent,state,(String)row.get("sepayRefundId"),"REFUND_ID_CONFLICT",now);}
        if(Set.of("SUCCEEDED","FAILED","REJECTED").contains(state))audit.appendSystem(intent.order(),"SEPAY_REFUND_"+state,Map.of("refundId",intent.id(),"status","PENDING"),Map.of("refundId",intent.id(),"status","SUCCEEDED".equals(state)?"SUCCEEDED":"FAILED","executionState",state,"amount",Long.toString(intent.amount())));
        return state;
    }
    private void pending(Intent intent,String state,String providerId,String error,LocalDateTime now){
        long backoff=Math.min(3600,30L*(1L<<Math.min(7,Math.max(0,intent.attempt()-1))));
        store.jdbc().update("UPDATE refunds SET sepay_execution_state=?,sepay_refund_id=?,sepay_error_code=?,sepay_checked_at=?,sepay_retry_at=?,sepay_lease_token=NULL,sepay_lease_until=NULL,updated_at=? WHERE id=?",state,providerId,error,now,now.plusSeconds(backoff),now,intent.id());
    }
    private String originalReference(long payment){
        String expected=store.jdbc().queryForObject("SELECT provider_transaction_id FROM payments WHERE id=?",String.class,payment);var references=new HashSet<String>();
        for(var row:store.rows("SELECT payload FROM payment_events WHERE payment_id=? AND provider=? AND processing_status='PROCESSED'",payment,SePaySettings.PROVIDER)){
            var n=mapper.readTree(row.get("payload").toString());String reference=n.path("reference").asText();if(reference.isBlank()||reference.length()>150)continue;
            var transfer=new SePayTransfer("refund-proof",n.path("bank").asText(),n.path("account").asText(),n.path("subAccount").asText(),n.path("code").asText(),n.path("content").asText(),n.path("direction").asText(),BigDecimal.ZERO,reference,n.path("transactionDate").asText());
            if(SePayEventService.collectionReference(transfer).equals(expected))references.add(reference);
        }
        if(references.size()!=1)throw CommerceException.conflict("SEPAY_REFUND_REFERENCE_REQUIRED","Chưa xác minh được duy nhất chứng từ ngân hàng của khoản thu. Cần đối soát SePay trước khi hoàn.");return references.iterator().next();
    }
    private void lockReturn(long order,Long id){if(id==null)return;var found=store.jdbc().queryForList("SELECT id FROM return_requests WHERE id=? AND order_id=? FOR UPDATE",Long.class,id,order);if(found.isEmpty())throw RefundPolicy.conflict("RETURN_NOT_REFUNDABLE");store.jdbc().queryForList("SELECT id FROM return_items WHERE return_request_id=? ORDER BY id FOR UPDATE",Long.class,id);}
    private static BigDecimal collected(List<Map<String,Object>> payments){return payments.stream().filter(p->"SUCCEEDED".equals(p.get("status"))).map(p->RefundPolicy.money(p.get("amount"))).reduce(BigDecimal.ZERO,BigDecimal::add);}
    private static long number(Object value){return ((Number)value).longValue();}
    private static String text(Map<String,Object> row,String key){return Objects.toString(row.get(key),"");}
    private LocalDateTime now(){return LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);}
}
