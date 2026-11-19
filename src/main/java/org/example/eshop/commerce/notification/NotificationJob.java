package org.example.eshop.commerce.notification;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.Map;

@Service
public class NotificationJob {
    private final JdbcTemplate db;private final Clock clock;private final NotificationDeliveryService delivery;
    public NotificationJob(JdbcTemplate db,Clock clock,NotificationDeliveryService delivery){this.db=db;this.clock=clock;this.delivery=delivery;}
    public Map<String,Integer> runBatch(){
        var ids=db.queryForList("SELECT id FROM commerce_notification_outbox WHERE state IN ('PENDING','RETRY') AND next_attempt_at<=? ORDER BY next_attempt_at,id LIMIT 100",Long.class,LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC));int sent=0,skipped=0,failed=0;
        for(long id:ids){try{if(delivery.deliver(id))sent++;else skipped++;}catch(RuntimeException error){failed++;try{delivery.failed(id);}catch(RuntimeException unavailable){/* Original pending row survives a DB outage and can be retried after recovery. */}}}
        return Map.of("scanned",ids.size(),"delivered",sent,"skipped",skipped,"failed",failed);
    }
}
