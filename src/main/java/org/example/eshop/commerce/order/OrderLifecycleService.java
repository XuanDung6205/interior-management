package org.example.eshop.commerce.order;

import org.example.eshop.commerce.common.CommerceException;
import org.example.eshop.commerce.port.OrderAuditPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.util.*;

@Service
public class OrderLifecycleService {
    private final OrderStore store;private final OrderAdminAccess access;private final OrderLifecyclePolicy policy;private final AdminOrderQueryService queries;private final OrderCancellationService cancellation;private final OrderAuditPort audit;private final Clock clock;
    public OrderLifecycleService(OrderStore store,OrderAdminAccess access,OrderLifecyclePolicy policy,AdminOrderQueryService queries,OrderCancellationService cancellation,OrderAuditPort audit,Clock clock){this.store=store;this.access=access;this.policy=policy;this.queries=queries;this.cancellation=cancellation;this.audit=audit;this.clock=clock;}
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> transition(long id,String from,String to,String note){
        long actor=access.requireStaff();policy.validateEdge(from,to);
        note=note==null?null:note.trim();if(note!=null&&note.length()>1000)throw CommerceException.invalid(Map.of("note","Thông báo tối đa1000 ký tự."));
        if("CANCELLED".equals(to)&&(note==null||note.isBlank()))throw CommerceException.invalid(Map.of("note","Nhập lý do hủy đơn."));
        var order=access.lock(id,actor);String current=order.get("status").toString();
        if(current.equals(to)){
            int applied=store.jdbc().queryForObject("SELECT COUNT(*) FROM order_status_history WHERE order_id=? AND from_status=? AND to_status=?",Integer.class,id,from,to);
            if(applied>0)return queries.detail(id);
        }
        if(!current.equals(from))throw CommerceException.conflict("ORDER_STATE_CHANGED","Trạng thái đã được người khác thay đổi. Vui lòng tải lại đơn.");
        var reasons=policy.reasons(id,to);
        if(!reasons.isEmpty())throw CommerceException.conflict("COMPLETED".equals(to)?"ORDER_NOT_READY":"PROCESSING".equals(to)?"ORDER_STOCK_NOT_READY":"ORDER_NOT_CANCELLABLE",String.join(" ",reasons));
        if("CANCELLED".equals(to)){cancellation.cancelLocked(id,actor,note,true);return queries.detail(id);}
        var couponIds=store.jdbc().query("SELECT coupon_id FROM orders WHERE id=? AND coupon_id IS NOT NULL",(rs,n)->rs.getLong(1),id);
        if(!couponIds.isEmpty())store.jdbc().queryForObject("SELECT id FROM coupons WHERE id=? FOR UPDATE",Long.class,couponIds.get(0));
        var now=LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);
        if("COMPLETED".equals(to)&&!couponIds.isEmpty()){
            int redeemed=store.jdbc().update("UPDATE coupon_redemptions SET status='APPLIED',redeemed_at=COALESCE(redeemed_at,?) WHERE order_id=? AND status IN ('RESERVED','APPLIED')",now,id);
            if(redeemed!=1)throw CommerceException.conflict("COUPON_RESERVATION_CHANGED","Lượt ưu đãi của đơn không còn hợp lệ. Vui lòng kiểm tra lại.");
        }
        store.jdbc().update("UPDATE orders SET status=?,confirmed_at=CASE WHEN ?='CONFIRMED' THEN COALESCE(confirmed_at,?) ELSE confirmed_at END,completed_at=CASE WHEN ?='COMPLETED' THEN COALESCE(completed_at,?) ELSE completed_at END,updated_at=? WHERE id=?",to,to,now,to,now,now,id);
        if("CONFIRMED".equals(to))store.jdbc().update("UPDATE orders SET confirmation_expires_at=NULL,expiry_retry_after=NULL WHERE id=?",id);
        store.insert("INSERT INTO order_status_history(order_id,from_status,to_status,changed_by,note,changed_at) VALUES(?,?,?,?,?,?)",id,from,to,actor,note,now);
        audit.append(actor,id,"ORDER_STATUS_CHANGED",Map.of("status",from),Map.of("status",to));
        return queries.detail(id);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> internalNote(long id,String desired,String expected){
        long actor=access.requireStaff();desired=normalize(desired);
        if((desired!=null&&desired.length()>5000)||(expected!=null&&expected.length()>5000))throw CommerceException.invalid(Map.of("internalNote","Ghi chú tối đa5000 ký tự."));
        access.lock(id,actor);String current=store.jdbc().queryForObject("SELECT internal_note FROM orders WHERE id=?",String.class,id);
        if(Objects.equals(current,desired))return queries.detail(id);
        if(!Objects.equals(current,expected))throw CommerceException.conflict("INTERNAL_NOTE_CHANGED","Ghi chú đã được nhân viên khác cập nhật. Hãy tải lại trước khi lưu.");
        store.jdbc().update("UPDATE orders SET internal_note=?,updated_at=? WHERE id=?",desired,LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC),id);
        var before=new LinkedHashMap<String,Object>();before.put("internalNote",current);var after=new LinkedHashMap<String,Object>();after.put("internalNote",desired);
        audit.append(actor,id,"ORDER_INTERNAL_NOTE_UPDATED",before,after);return queries.detail(id);
    }
    private static String normalize(String value){return value==null||value.isBlank()?null:value.trim();}
}
