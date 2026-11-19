package org.example.eshop.commerce.order;

import org.example.eshop.commerce.common.CommerceException;
import org.example.eshop.commerce.port.InventoryPort;
import org.example.eshop.commerce.port.OrderAuditPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@Service
public class OrderCancellationService {
    private final OrderStore store;private final CheckoutQuoteService quotes;private final OrderQueryService orders;private final InventoryPort inventory;private final Clock clock;private final OrderAuditPort audit;
    public OrderCancellationService(OrderStore store,CheckoutQuoteService quotes,OrderQueryService orders,InventoryPort inventory,Clock clock,OrderAuditPort audit){this.store=store;this.quotes=quotes;this.orders=orders;this.inventory=inventory;this.clock=clock;this.audit=audit;}
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> cancel(long id,String reason){
        long user=quotes.actorId();
        if(reason==null||reason.isBlank()||reason.length()>1000)throw CommerceException.invalid(Map.of("reason","Nhập lý do hủy từ1 đến1000 ký tự."));
        // Match checkout's user -> order -> coupon order. The history actor FK
        // must not acquire a late user lock while another checkout holds it.
        store.jdbc().queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE",Long.class,user);
        store.owned(id,user,true);
        cancelLocked(id,user,reason,false);
        return orders.detailFor(id,user);
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void cancelLocked(long id,long actor,String reason,boolean staff){
        cancelCore(id,actor,reason,staff,false);
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void cancelExpiredLocked(long id,String reason){cancelCore(id,null,reason,false,true);}
    private void cancelCore(long id,Long actor,String reason,boolean staff,boolean expired){
        if(reason==null||reason.isBlank()||reason.length()>1000)throw CommerceException.invalid(Map.of("reason","Nhập lý do hủy từ1 đến1000 ký tự."));
        var order=store.find(id,true);String before=order.get("status").toString();
        if("CANCELLED".equals(before))return;
        if(!(Set.of("PENDING_CONFIRMATION","CONFIRMED").contains(before)||(staff&&"PROCESSING".equals(before)))||store.dispatched(id))throw CommerceException.conflict("ORDER_NOT_CANCELLABLE","Đơn đã xử lý hoặc xuất giao. Vui lòng liên hệ cửa hàng.");
        // All lifecycle writers must lock the order first, then coupon, payment rows and inventory.
        var couponIds=store.jdbc().query("SELECT coupon_id FROM orders WHERE id=? AND coupon_id IS NOT NULL",(rs,n)->rs.getLong(1),id);
        if(!couponIds.isEmpty())store.jdbc().queryForObject("SELECT id FROM coupons WHERE id=? FOR UPDATE",Long.class,couponIds.get(0));
        var payments=store.rows("SELECT id,status,amount,currency,provider FROM payments WHERE order_id=? ORDER BY id FOR UPDATE",id);
        inventory.releaseOrder(id);
        var now=LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);
        for(var p:payments){
            long payment=((Number)p.get("id")).longValue();
            if("SUCCEEDED".equals(p.get("status"))){
                BigDecimal held=store.jdbc().queryForObject("SELECT COALESCE(SUM(amount),0) FROM refunds WHERE payment_id=? AND status IN ('PENDING','SUCCEEDED')",BigDecimal.class,payment);
                BigDecimal remainder=new BigDecimal(p.get("amount").toString()).subtract(held);
                if(remainder.signum()>0)store.insert("INSERT INTO refunds(order_id,payment_id,provider,idempotency_key,amount,currency,status,reason,created_at,updated_at) VALUES(?,?,?, ?,?,?,'PENDING',?,?,?)",id,payment,p.get("provider"),"cancel:"+payment,remainder,p.get("currency"),"Hủy đơn: "+reason.trim().substring(0,Math.min(reason.trim().length(),990)),now,now);
            }
        }
        store.jdbc().update("UPDATE payments SET status=?,updated_at=? WHERE order_id=? AND status='PENDING'",expired?"EXPIRED":"CANCELLED",now,id);
        store.jdbc().update("UPDATE shipments SET status='CANCELLED',installation_status=CASE WHEN installation_required=1 AND installation_status IN ('PENDING','SCHEDULED') THEN 'CANCELLED' ELSE installation_status END WHERE order_id=? AND status IN ('PENDING','READY')",id);
        store.jdbc().update("UPDATE coupon_redemptions SET status='RELEASED',expires_at=NULL WHERE order_id=? AND status IN ('RESERVED','APPLIED')",id);
        store.jdbc().update("UPDATE orders SET status='CANCELLED',cancelled_at=?,cancel_reason=?,updated_at=? WHERE id=?",now,reason.trim(),now,id);
        store.insert("INSERT INTO order_status_history(order_id,from_status,to_status,changed_by,note,changed_at) VALUES(?,?,'CANCELLED',?,?,?)",id,before,actor,reason.trim(),now);
        if(expired)audit.appendSystem(id,"ORDER_EXPIRED",Map.of("status",before),Map.of("status","CANCELLED"));else audit.append(actor,id,"ORDER_STATUS_CHANGED",Map.of("status",before),Map.of("status","CANCELLED"));
    }
}
