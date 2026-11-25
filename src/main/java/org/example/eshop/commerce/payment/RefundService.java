package org.example.eshop.commerce.payment;

import org.example.eshop.commerce.common.CommerceException;
import org.example.eshop.commerce.order.*;
import org.example.eshop.commerce.port.OrderAuditPort;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@Service
public class RefundService {
    private final OrderStore store;private final OrderAdminAccess access;private final RefundQueryService queries;private final RefundPolicy policy;private final CheckoutQuoteService hashes;private final OrderAuditPort audit;private final Clock clock;
    public RefundService(OrderStore store,OrderAdminAccess access,RefundQueryService queries,RefundPolicy policy,CheckoutQuoteService hashes,OrderAuditPort audit,Clock clock){this.store=store;this.access=access;this.queries=queries;this.policy=policy;this.hashes=hashes;this.audit=audit;this.clock=clock;}
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> create(long orderId,long paymentId,String rawAmount,String reason,Long returned,String key){
        long actor=access.requireStaff();BigDecimal amount=parse(rawAmount);reason=required(reason,"reason");if(key==null||!key.matches("[A-Za-z0-9_-]{8,64}"))throw CommerceException.badRequest("INVALID_IDEMPOTENCY_KEY","Idempotency-Key cần8–64 ký tự chữ, số, _ hoặc -.");
        var order=access.lock(orderId,actor);String scoped="manual-refund:"+hashes.hash(List.of(actor,key));
        var prior=store.rows("SELECT id,order_id AS orderId,payment_id AS paymentId,return_request_id AS returnRequestId,amount,reason FROM refunds WHERE idempotency_key=?",scoped);
        if(!prior.isEmpty()){var r=prior.get(0);if(((Number)r.get("orderId")).longValue()!=orderId||((Number)r.get("paymentId")).longValue()!=paymentId||RefundPolicy.money(r.get("amount")).compareTo(amount)!=0||!reason.equals(r.get("reason"))||!sameId(returned,r.get("returnRequestId")))throw CommerceException.conflict("IDEMPOTENCY_CONFLICT","Khóa yêu cầu đã được dùng với nội dung hoàn tiền khác.");return queries.detail(((Number)r.get("id")).longValue());}
        var payments=queries.payments(orderId,true);var payment=findPayment(payments,paymentId);if(!RefundPolicy.manual(payment))throw RefundPolicy.conflict("PAYMENT_NOT_REFUNDABLE");lockReturn(orderId,returned);
        var refunds=queries.refunds(orderId,true);BigDecimal paid=RefundPolicy.money(payment.get("amount"));BigDecimal total=collected(payments);
        if(amount.compareTo(paid.subtract(policy.held(refunds,paymentId,null)))>0||amount.compareTo(total.subtract(policy.held(refunds,null,null)))>0)throw RefundPolicy.conflict("REFUND_EXCEEDS_BALANCE");policy.validateReturn(order,refunds,returned,amount);
        var now=now();long refund=store.insert("INSERT INTO refunds(order_id,payment_id,return_request_id,provider,idempotency_key,amount,currency,status,reason,created_at,updated_at) VALUES(?,?,?,?,?,?,?,'PENDING',?,?,?)",orderId,paymentId,returned,payment.get("provider"),scoped,amount,payment.get("currency"),reason,now,now);
        audit.append(actor,orderId,"REFUND_REQUESTED",Map.of(),Map.of("refundId",refund,"paymentId",paymentId,"status","PENDING","amount",RefundPolicy.amount(amount)));return queries.detail(refund);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> confirm(long id,String reference,String note,Boolean acknowledged){
        long actor=access.requireStaff();attest(acknowledged);reference=reference==null?null:reference.trim();note=normalize(note);if(reference==null||!reference.matches("[A-Za-z0-9._:/-]{6,150}"))throw CommerceException.invalid(Map.of("reference","Mã chứng từ gồm6–150 ký tự chữ, số, . _ : / - ."));if(note!=null&&note.length()>1000)throw CommerceException.invalid(Map.of("note","Ghi chú tối đa1000 ký tự."));
        long orderId=queries.orderId(id);var order=access.lock(orderId,actor);var payments=queries.payments(orderId,true);var hint=store.rows("SELECT return_request_id AS returnRequestId FROM refunds WHERE id=?",id).get(0);Long returned=hint.get("returnRequestId")==null?null:((Number)hint.get("returnRequestId")).longValue();lockReturn(orderId,returned);var refunds=queries.refunds(orderId,true);var r=findRefund(refunds,id);var payment=findPayment(payments,((Number)r.get("paymentId")).longValue());
        if(SePayRefundSettings.claimed(r))throw CommerceException.conflict("SEPAY_REFUND_EXECUTION_CLAIMED","Khoản hoàn đã gửi qua SePay Sandbox; cần đối soát đúng lệnh đã lưu, không xác minh hoặc hủy thủ công.");
        if(!RefundPolicy.manual(payment)||!Objects.equals(r.get("provider"),payment.get("provider")))throw RefundPolicy.conflict("PAYMENT_NOT_REFUNDABLE");
        if("SUCCEEDED".equals(r.get("status"))){if(Objects.equals(reference,r.get("reference"))&&Objects.equals(note,r.get("confirmationNote")))return queries.detail(id);throw RefundPolicy.conflict("REFUND_ALREADY_CONFIRMED");}if(!"PENDING".equals(r.get("status")))throw RefundPolicy.conflict("REFUND_NOT_PENDING");
        long p=((Number)r.get("paymentId")).longValue();if(policy.held(refunds,p,null).compareTo(RefundPolicy.money(payment.get("amount")))>0||policy.held(refunds,null,null).compareTo(collected(payments))>0)throw RefundPolicy.conflict("REFUND_RECONCILIATION_REQUIRED");
        // Recheck return evidence and its held budget, excluding this request already reserved.
        var other=refunds.stream().filter(row->((Number)row.get("id")).longValue()!=id).toList();policy.validateReturn(order,other,returned,RefundPolicy.money(r.get("amount")));
        var now=now();try{store.jdbc().update("UPDATE refunds SET status='SUCCEEDED',provider_refund_id=?,confirmation_note=?,processed_by=?,completed_at=?,updated_at=? WHERE id=?",reference,note,actor,now,now,id);}catch(DuplicateKeyException e){throw RefundPolicy.conflict("REFUND_REFERENCE_IN_USE");}
        audit.append(actor,orderId,"REFUND_CONFIRMED",Map.of("refundId",id,"status","PENDING"),Map.of("refundId",id,"status","SUCCEEDED","amount",r.get("amount"),"reference",reference));return queries.detail(id);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> cancel(long id,String reason,Boolean acknowledged){
        long actor=access.requireStaff();attest(acknowledged);reason=required(reason,"reason");long orderId=queries.orderId(id);access.lock(orderId,actor);var payments=queries.payments(orderId,true);var refunds=queries.refunds(orderId,true);var r=findRefund(refunds,id);var payment=findPayment(payments,((Number)r.get("paymentId")).longValue());
        if(SePayRefundSettings.claimed(r))throw CommerceException.conflict("SEPAY_REFUND_EXECUTION_CLAIMED","Khoản hoàn đã gửi qua SePay Sandbox; cần đối soát đúng lệnh đã lưu, không xác minh hoặc hủy thủ công.");
        if(!RefundPolicy.manual(payment)||!Objects.equals(r.get("provider"),payment.get("provider")))throw RefundPolicy.conflict("PAYMENT_NOT_REFUNDABLE");
        if("CANCELLED".equals(r.get("status"))&&Objects.equals(reason,r.get("resolutionNote")))return queries.detail(id);if(!"PENDING".equals(r.get("status")))throw RefundPolicy.conflict("REFUND_ALREADY_RESOLVED");
        store.jdbc().update("UPDATE refunds SET status='CANCELLED',resolution_note=?,processed_by=?,updated_at=? WHERE id=?",reason,actor,now(),id);
        audit.append(actor,orderId,"REFUND_CANCELLED",Map.of("refundId",id,"status","PENDING"),Map.of("refundId",id,"status","CANCELLED"));return queries.detail(id);
    }
    private void lockReturn(long order,Long id){if(id==null)return;var found=store.jdbc().queryForList("SELECT id FROM return_requests WHERE id=? AND order_id=? FOR UPDATE",Long.class,id,order);if(found.isEmpty())throw RefundPolicy.conflict("RETURN_NOT_REFUNDABLE");store.jdbc().queryForList("SELECT id FROM return_items WHERE return_request_id=? ORDER BY id FOR UPDATE",Long.class,id);}
    private static Map<String,Object> findPayment(List<Map<String,Object>> rows,long id){return rows.stream().filter(p->((Number)p.get("id")).longValue()==id).findFirst().orElseThrow(()->RefundPolicy.conflict("PAYMENT_NOT_REFUNDABLE"));}
    private static Map<String,Object> findRefund(List<Map<String,Object>> rows,long id){return rows.stream().filter(p->((Number)p.get("id")).longValue()==id).findFirst().orElseThrow(()->CommerceException.notFound("Không tìm thấy yêu cầu hoàn tiền."));}
    private static BigDecimal collected(List<Map<String,Object>> payments){return payments.stream().filter(p->"SUCCEEDED".equals(p.get("status"))).map(p->RefundPolicy.money(p.get("amount"))).reduce(BigDecimal.ZERO,BigDecimal::add);}
    private static boolean sameId(Long value,Object other){return value==null?other==null:other instanceof Number n&&value.longValue()==n.longValue();}
    private static BigDecimal parse(String raw){if(raw==null||!raw.matches("[0-9]{1,13}(?:\\.[0-9]{1,2})?"))throw CommerceException.invalid(Map.of("amount","Số tiền phải là số thập phân dương, tối đa2 chữ số lẻ."));var amount=new BigDecimal(raw);if(amount.signum()<=0)throw CommerceException.invalid(Map.of("amount","Số tiền phải lớn hơn0."));return amount.setScale(2);}
    private static String normalize(String value){return value==null||value.isBlank()?null:value.trim();}
    private static String required(String value,String field){value=normalize(value);if(value==null||value.length()>1000)throw CommerceException.invalid(Map.of(field,"Nhập lý do từ1 đến1000 ký tự."));return value;}
    private static void attest(Boolean ack){if(!Boolean.TRUE.equals(ack))throw CommerceException.invalid(Map.of("acknowledged","Cần xác nhận đã đối chiếu trạng thái chuyển trả tiền."));}
    private LocalDateTime now(){return LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);}
}
