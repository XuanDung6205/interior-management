package org.example.eshop.commerce.payment;

import org.example.eshop.commerce.common.CommerceException;
import org.example.eshop.commerce.order.OrderStore;
import org.example.eshop.commerce.port.OrderAuditPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

@Service
public class SePayEventService {
    private final OrderStore store;
    private final OrderAuditPort audit;
    private final ObjectMapper mapper;
    private final Clock clock;
    public SePayEventService(OrderStore store,OrderAuditPort audit,ObjectMapper mapper,Clock clock){this.store=store;this.audit=audit;this.mapper=mapper;this.clock=clock;}

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> process(SePayTransfer t,Long expectedPayment){
        String payload=mapper.writeValueAsString(t.facts());
        var candidates=store.rows("SELECT id,order_id AS orderId FROM payments WHERE provider=? AND sepay_code=?",SePaySettings.PROVIDER,t.code());
        Long payment=candidates.isEmpty()?null:((Number)candidates.get(0).get("id")).longValue();
        if(expectedPayment!=null&&!Objects.equals(expectedPayment,payment))throw CommerceException.conflict("SEPAY_TRANSACTION_MISMATCH","Giao dịch không khớp khoản thanh toán đang đối soát.");
        // Claim the provider event before monetary locks; retries serialize on this unique key.
        store.jdbc().update("INSERT INTO payment_events(provider,provider_event_id,event_type,payload) VALUES(?,?,'TRANSFER',?) ON DUPLICATE KEY UPDATE provider_event_id=VALUES(provider_event_id)",SePaySettings.PROVIDER,t.eventId(),payload);
        var event=store.jdbc().queryForMap("SELECT id,payload,processing_status FROM payment_events WHERE provider=? AND provider_event_id=? FOR UPDATE",SePaySettings.PROVIDER,t.eventId());
        long eventId=((Number)event.get("id")).longValue();
        if(!mapper.readTree(event.get("payload").toString()).equals(mapper.readTree(payload)))throw CommerceException.conflict("SEPAY_EVENT_CONFLICT","Mã giao dịch SePay đã tồn tại với dữ liệu khác.");
        if(!"RECEIVED".equals(event.get("processing_status")))return result(eventId,event.get("processing_status").toString(),true);
        if(payment==null)return finish(eventId,"IGNORED","UNKNOWN_PAYMENT_CODE");
        long orderId=((Number)candidates.get(0).get("orderId")).longValue();
        var owners=store.jdbc().query("SELECT user_id FROM orders WHERE id=?",(rs,n)->(Long)rs.getObject(1),orderId);
        if(!owners.isEmpty()&&owners.get(0)!=null)store.jdbc().queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE",Long.class,owners.get(0));
        var order=store.find(orderId,true);
        var coupon=store.jdbc().query("SELECT coupon_id FROM orders WHERE id=? AND coupon_id IS NOT NULL",(rs,n)->rs.getLong(1),orderId);
        if(!coupon.isEmpty())store.jdbc().queryForObject("SELECT id FROM coupons WHERE id=? FOR UPDATE",Long.class,coupon.get(0));
        var p=store.jdbc().queryForMap("SELECT * FROM payments WHERE id=? FOR UPDATE",payment);
        store.jdbc().update("UPDATE payment_events SET payment_id=? WHERE id=?",payment,eventId);
        boolean match="in".equals(t.direction()) && t.validCode() && t.containsSnapshot((String)p.get("sepay_content")) && t.subAccount().isEmpty()
                && t.bank().equalsIgnoreCase(Objects.toString(p.get("sepay_bank"),""))
                && t.account().equals(p.get("sepay_account_number"))
                && "VND".equals(p.get("currency")) && t.amount().compareTo((BigDecimal)p.get("amount"))==0;
        if(!match)return finish(eventId,"FAILED","TRANSFER_MISMATCH");
        if(t.reference().isBlank())return finish(eventId,"FAILED","MISSING_BANK_REFERENCE");
        String reference=collectionReference(t);
        if("SUCCEEDED".equals(p.get("status"))){
            var prior=store.rows("SELECT provider_event_id AS eventId,payload FROM payment_events WHERE payment_id=? AND provider=? AND processing_status='PROCESSED'",payment,SePaySettings.PROVIDER);
            String source=t.eventId().substring(0,t.eventId().indexOf(':')+1);
            boolean sameSource=prior.stream().anyMatch(e->e.get("eventId").toString().startsWith(source));
            boolean equivalent=prior.stream().anyMatch(e->mapper.readTree(e.get("payload").toString()).equals(mapper.readTree(payload)));
            boolean duplicate=!sameSource&&reference.equals(p.get("provider_transaction_id"))&&equivalent;
            return finish(eventId,duplicate?"PROCESSED":"FAILED",duplicate?null:"ADDITIONAL_TRANSFER_REQUIRES_REVIEW");
        }
        boolean late="CANCELLED".equals(order.get("status"));
        if(!(late?Set.of("PENDING","CANCELLED","EXPIRED"):Set.of("PENDING")).contains(p.get("status")))return finish(eventId,"FAILED","PAYMENT_STATE_REQUIRES_REVIEW");
        BigDecimal collected=store.jdbc().queryForObject("SELECT COALESCE(SUM(amount),0) FROM payments WHERE order_id=? AND status='SUCCEEDED'",BigDecimal.class,orderId);
        if(t.amount().compareTo(new BigDecimal(order.get("grandTotal").toString()).subtract(collected))>0)return finish(eventId,"FAILED","PAYMENT_EXCEEDS_BALANCE");
        int existing=store.jdbc().queryForObject("SELECT COUNT(*) FROM payments WHERE provider=? AND provider_transaction_id=?",Integer.class,SePaySettings.PROVIDER,reference);
        if(existing>0)return finish(eventId,"FAILED","REFERENCE_ALREADY_USED");
        LocalDateTime now=LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);
        store.jdbc().update("UPDATE payments SET status='SUCCEEDED',provider_transaction_id=?,paid_at=?,updated_at=? WHERE id=?",reference,now,now,payment);
        store.jdbc().update("UPDATE orders SET confirmation_expires_at=NULL,expiry_retry_after=NULL WHERE id=?",orderId);
        if(late)store.insert("INSERT INTO refunds(order_id,payment_id,provider,idempotency_key,amount,currency,status,reason,created_at,updated_at) VALUES(?,?,?,?,?,'VND','PENDING',?,?,?)",orderId,payment,SePaySettings.PROVIDER,"cancel:"+payment,t.amount(),"SePay Sandbox: khoản thu đến sau khi hủy đơn; chờ hoàn thủ công.",now,now);
        else if(!coupon.isEmpty()&&collected.add(t.amount()).compareTo(new BigDecimal(order.get("grandTotal").toString()))>=0){
            int changed=store.jdbc().update("UPDATE coupon_redemptions SET status='APPLIED',redeemed_at=COALESCE(redeemed_at,?) WHERE order_id=? AND status IN ('RESERVED','APPLIED')",now,orderId);
            if(changed!=1)throw CommerceException.conflict("COUPON_RESERVATION_CHANGED","Cần kiểm tra lượt ưu đãi của đơn.");
        }
        audit.appendSystem(orderId,"SEPAY_PAYMENT_CONFIRMED",Map.of("paymentId",payment,"status",p.get("status")),Map.of("paymentId",payment,"status","SUCCEEDED","amount",t.amount().toPlainString(),"lateCollection",late));
        return finish(eventId,"PROCESSED",null);
    }
    private static String collectionReference(SePayTransfer t){try{return "SEPAY-"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((t.bank().toUpperCase(Locale.ROOT)+"|"+t.account()+"|"+t.reference()).getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private Map<String,Object> finish(long id,String status,String reason){store.jdbc().update("UPDATE payment_events SET processing_status=?,error_message=?,processed_at=? WHERE id=?",status,reason,LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC),id);return result(id,status,false);}
    private static Map<String,Object> result(long id,String status,boolean duplicate){return Map.of("success",true,"eventId",id,"processingStatus",status,"duplicate",duplicate);}
}
