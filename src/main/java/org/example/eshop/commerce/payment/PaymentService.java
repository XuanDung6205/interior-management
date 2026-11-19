package org.example.eshop.commerce.payment;

import org.example.eshop.commerce.common.CommerceException;
import org.example.eshop.commerce.order.*;
import org.example.eshop.commerce.port.OrderAuditPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@Service
public class PaymentService {
    private final OrderStore store;private final PaymentQueryService queries;private final CheckoutQuoteService actors;private final OrderAdminAccess access;private final BankTransferSettings bank;private final OrderAuditPort audit;private final OrderCompletionPolicy completion;private final OrderLifecycleService lifecycle;private final Clock clock;
    public PaymentService(OrderStore store,PaymentQueryService queries,CheckoutQuoteService actors,OrderAdminAccess access,BankTransferSettings bank,OrderAuditPort audit,OrderCompletionPolicy completion,OrderLifecycleService lifecycle,Clock clock){this.store=store;this.queries=queries;this.actors=actors;this.access=access;this.bank=bank;this.audit=audit;this.completion=completion;this.lifecycle=lifecycle;this.clock=clock;}
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> create(long id,String method,String key){
        long actor=actors.actorId();if(method==null||!Set.of("COD","BANK_TRANSFER").contains(method))throw CommerceException.invalid(Map.of("method","Chọn COD hoặc BANK_TRANSFER."));if(key==null||!key.matches("[A-Za-z0-9_-]{8,64}"))throw CommerceException.badRequest("INVALID_IDEMPOTENCY_KEY","Idempotency-Key phải có8–64 ký tự chữ, số, _ hoặc -.");
        store.jdbc().queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE",Long.class,actor);var order=store.owned(id,actor,true);String scoped="manual:"+actors.hash(List.of(actor,key));
        var prior=store.rows("SELECT order_id AS orderId,method FROM payments WHERE idempotency_key=?",scoped);if(!prior.isEmpty()){var p=prior.get(0);if(((Number)p.get("orderId")).longValue()!=id||!method.equals(p.get("method")))throw CommerceException.conflict("IDEMPOTENCY_CONFLICT","Khóa thanh toán đã được dùng cho yêu cầu khác.");return queries.customer(id);}
        var totals=queries.totals(id);String block=queries.createBlock(order,totals,queries.payments(id,true));if(block!=null)throw CommerceException.conflict(block,PaymentQueryService.message(block));
        if("BANK_TRANSFER".equals(method)&&!Boolean.TRUE.equals(bank.forOrder(order.get("orderNumber").toString()).get("configured")))throw CommerceException.conflict("PAYMENT_BANK_NOT_CONFIGURED",PaymentQueryService.message("PAYMENT_BANK_NOT_CONFIGURED"));
        var now=LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);BigDecimal amount=PaymentQueryService.decimal(order.get("grandTotal")).subtract(totals.collected());
        store.insert("INSERT INTO payments(order_id,method,provider,idempotency_key,amount,currency,status,created_at,updated_at) VALUES(?,?,?,?,?,?,'PENDING',?,?)",id,method,"COD".equals(method)?"COD":"MANUAL_BANK",scoped,amount,order.get("currency"),now,now);
        if("BANK_TRANSFER".equals(method))store.jdbc().update("UPDATE orders SET confirmation_expires_at=NULL,expiry_retry_after=NULL WHERE id=?",id);
        return queries.customer(id);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> confirm(long payment,String reference,String note,Boolean acknowledged){
        long actor=access.requireStaff();reference=reference==null?null:reference.trim();note=note==null||note.isBlank()?null:note.trim();
        if(!Boolean.TRUE.equals(acknowledged))throw CommerceException.invalid(Map.of("acknowledged","Xác nhận đã đối chiếu khoản thu thực tế."));if(reference==null||!reference.matches("[A-Za-z0-9._:/-]{6,150}"))throw CommerceException.invalid(Map.of("reference","Mã biên nhận gồm6–150 ký tự chữ, số, . _ : / - ."));if(note!=null&&note.length()>1000)throw CommerceException.invalid(Map.of("note","Ghi chú tối đa1000 ký tự."));
        long id=queries.orderId(payment);var order=access.lock(id,actor);var coupons=store.jdbc().query("SELECT coupon_id FROM orders WHERE id=? AND coupon_id IS NOT NULL",(rs,n)->rs.getLong(1),id);if(!coupons.isEmpty())store.jdbc().queryForObject("SELECT id FROM coupons WHERE id=? FOR UPDATE",Long.class,coupons.get(0));
        var p=queries.payments(id,true).stream().filter(row->((Number)row.get("id")).longValue()==payment).findFirst().orElseThrow(()->CommerceException.notFound("Không tìm thấy khoản thanh toán."));
        if("SUCCEEDED".equals(p.get("status"))){if(PaymentQueryService.manual(p)&&Objects.equals(reference,p.get("reference"))&&Objects.equals(note,p.get("confirmationNote")))return queries.staff(payment);throw CommerceException.conflict("PAYMENT_ALREADY_CONFIRMED","Khoản thu đã được xác nhận với bằng chứng khác. Vui lòng tải lại.");}
        var totals=queries.totals(id);String blocked=queries.confirmationBlock(order,totals,p);if(blocked!=null)throw CommerceException.conflict(blocked,PaymentQueryService.message(blocked));
        var now=LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);
        try{store.jdbc().update("UPDATE payments SET status='SUCCEEDED',provider_transaction_id=?,confirmation_note=?,verified_by=?,paid_at=?,updated_at=? WHERE id=?",reference,note,actor,now,now,payment);}catch(DataIntegrityViolationException exception){throw CommerceException.conflict("PAYMENT_REFERENCE_IN_USE","Mã biên nhận đã được gắn với khoản thu khác.");}
        boolean late="CANCELLED".equals(order.get("status"));
        if(late)store.insert("INSERT INTO refunds(order_id,payment_id,provider,idempotency_key,amount,currency,status,reason,created_at,updated_at) VALUES(?,?,?,?,?,?,'PENDING',?,?,?)",id,payment,p.get("provider"),"cancel:"+payment,PaymentQueryService.decimal(p.get("amount")),p.get("currency"),"Khoản thu đến sau khi hủy đơn; chờ đối soát hoàn tiền.",now,now);
        else if(!coupons.isEmpty()&&totals.collected().add(PaymentQueryService.decimal(p.get("amount"))).compareTo(PaymentQueryService.decimal(order.get("grandTotal")))>=0){int redeemed=store.jdbc().update("UPDATE coupon_redemptions SET status='APPLIED',redeemed_at=COALESCE(redeemed_at,?) WHERE order_id=? AND status IN ('RESERVED','APPLIED')",now,id);if(redeemed!=1)throw CommerceException.conflict("COUPON_RESERVATION_CHANGED","Lượt ưu đãi không còn hợp lệ. Vui lòng kiểm tra lại.");}
        audit.append(actor,id,"PAYMENT_CONFIRMED",Map.of("paymentId",payment,"status",p.get("status")),Map.of("paymentId",payment,"status","SUCCEEDED","amount",p.get("amount"),"reference",reference,"lateCollection",late));
        if(!late&&"PROCESSING".equals(order.get("status"))&&completion.evaluate(id).eligible())lifecycle.transition(id,"PROCESSING","COMPLETED","Hoàn tất sau khi xác minh khoản thu.");
        return queries.staff(payment);
    }
}
