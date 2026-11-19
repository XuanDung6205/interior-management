package org.example.eshop.commerce.order;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.math.BigDecimal;
import java.time.*;

@Service
public class OrderExpiryService {
    private final OrderStore store;private final OrderCancellationService cancellation;private final Clock clock;
    public OrderExpiryService(OrderStore store,OrderCancellationService cancellation,Clock clock){this.store=store;this.cancellation=cancellation;this.clock=clock;}
    private LocalDateTime now(){return LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);}
    private boolean lockOwner(long id){var owners=store.jdbc().queryForList("SELECT user_id FROM orders WHERE id=?",Long.class,id);if(owners.isEmpty()||owners.get(0)==null)return false;store.jdbc().queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE",Long.class,owners.get(0));store.find(id,true);return true;}
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public boolean expire(long id){
        if(!lockOwner(id))return false;var headers=store.jdbc().queryForMap("SELECT status,grand_total,confirmed_at,confirmation_expires_at,expiry_retry_after FROM orders WHERE id=?",id);var deadline=(java.sql.Timestamp)headers.get("confirmation_expires_at");var retry=(java.sql.Timestamp)headers.get("expiry_retry_after");var now=now();
        if(!"PENDING_CONFIRMATION".equals(headers.get("status"))||deadline==null||deadline.toLocalDateTime().isAfter(now)||retry!=null&&retry.toLocalDateTime().isAfter(now)||headers.get("confirmed_at")!=null||((BigDecimal)headers.get("grand_total")).signum()<=0||store.dispatched(id))return false;
        var coupons=store.jdbc().queryForList("SELECT coupon_id FROM orders WHERE id=? AND coupon_id IS NOT NULL",Long.class,id);if(!coupons.isEmpty())store.jdbc().queryForObject("SELECT id FROM coupons WHERE id=? FOR UPDATE",Long.class,coupons.get(0));
        var payments=store.rows("SELECT method,provider,status FROM payments WHERE order_id=? ORDER BY id FOR UPDATE",id);
        if(payments.isEmpty()||payments.stream().anyMatch(p->!"COD".equals(p.get("method"))||!"COD".equals(p.get("provider"))||"SUCCEEDED".equals(p.get("status"))))return false;
        if(store.jdbc().queryForObject("SELECT COUNT(*) FROM refunds WHERE order_id=? AND status IN ('PENDING','SUCCEEDED')",Integer.class,id)>0)return false;
        cancellation.cancelExpiredLocked(id,"Đơn COD hết thời hạn xác nhận của cửa hàng.");return true;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED)
    public void failed(long id){if(!lockOwner(id))return;var rows=store.jdbc().queryForList("SELECT expiry_attempts FROM orders WHERE id=? AND status='PENDING_CONFIRMATION' AND confirmation_expires_at IS NOT NULL",Integer.class,id);if(rows.isEmpty())return;int attempts=Math.min(rows.get(0)+1,1000000);store.jdbc().update("UPDATE orders SET expiry_attempts=?,expiry_retry_after=? WHERE id=?",attempts,now().plusSeconds(Math.min(3600,30L*(1L<<Math.min(attempts,6)))),id);}
}
