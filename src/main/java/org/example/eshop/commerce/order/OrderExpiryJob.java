package org.example.eshop.commerce.order;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.Map;
@Service
public class OrderExpiryJob {
    private final OrderStore store;private final OrderExpiryService expiry;private final Clock clock;
    public OrderExpiryJob(OrderStore store,OrderExpiryService expiry,Clock clock){this.store=store;this.expiry=expiry;this.clock=clock;}
    public Map<String,Integer> runBatch(){var now=LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);var ids=store.jdbc().queryForList("""
        SELECT o.id FROM orders o WHERE o.status='PENDING_CONFIRMATION' AND o.grand_total>0
          AND o.confirmation_expires_at IS NOT NULL AND o.confirmation_expires_at<=?
          AND (o.expiry_retry_after IS NULL OR o.expiry_retry_after<=?)
          AND NOT EXISTS(SELECT 1 FROM payments p WHERE p.order_id=o.id AND (p.method<>'COD' OR p.provider<>'COD' OR p.status='SUCCEEDED'))
        ORDER BY o.confirmation_expires_at,o.id LIMIT 100
        """,Long.class,now,now);int expired=0,skipped=0,failed=0;for(long id:ids){try{if(expiry.expire(id))expired++;else skipped++;}catch(RuntimeException error){failed++;try{expiry.failed(id);}catch(RuntimeException unavailable){/* Due row and business hold survive until DB recovery. */}}}return Map.of("scanned",ids.size(),"expired",expired,"skipped",skipped,"failed",failed);}
}
