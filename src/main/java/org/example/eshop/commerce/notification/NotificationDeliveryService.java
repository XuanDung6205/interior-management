package org.example.eshop.commerce.notification;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;

@Service
public class NotificationDeliveryService {
    private final JdbcTemplate db;private final Clock clock;
    public NotificationDeliveryService(JdbcTemplate db,Clock clock){this.db=db;this.clock=clock;}
    private LocalDateTime now(){return LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);}
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public boolean deliver(long id){
        var now=now();var rows=db.queryForList("SELECT id FROM commerce_notification_outbox WHERE id=? AND state IN ('PENDING','RETRY') AND next_attempt_at<=? FOR UPDATE",Long.class,id,now);if(rows.isEmpty())return false;
        db.update("""
            INSERT INTO commerce_notifications(outbox_id,recipient_user_id,order_id,notification_type,title,body,created_at)
            SELECT id,recipient_user_id,order_id,notification_type,title,body,? FROM commerce_notification_outbox WHERE id=?
            """,now,id);
        db.update("UPDATE commerce_notification_outbox SET state='SENT',attempts=attempts+1,last_error=NULL,sent_at=?,updated_at=? WHERE id=?",now,now,id);return true;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED)
    public void failed(long id){
        var rows=db.queryForList("SELECT attempts FROM commerce_notification_outbox WHERE id=? AND state IN ('PENDING','RETRY') FOR UPDATE",Integer.class,id);if(rows.isEmpty())return;
        int attempts=rows.get(0)+1;var now=now();db.update("UPDATE commerce_notification_outbox SET state=?,attempts=?,next_attempt_at=?,last_error='DELIVERY_STORAGE_ERROR',updated_at=? WHERE id=?",attempts>=5?"DEAD":"RETRY",attempts,now.plusSeconds(Math.min(3600,30L*(1L<<Math.min(attempts,6)))),now,id);
    }
}
