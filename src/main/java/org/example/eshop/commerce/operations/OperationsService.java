package org.example.eshop.commerce.operations;

import com.zaxxer.hikari.HikariDataSource;
import org.example.eshop.commerce.common.CommerceException;
import org.example.eshop.commerce.order.*;
import org.example.eshop.commerce.port.OrderAuditPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import javax.sql.DataSource;
import java.time.*;
import java.util.*;

@Service
public class OperationsService {
    private final OrderStore store;private final OrderAdminAccess access;private final DataSource datasource;private final OrderAuditPort audit;private final Clock clock;private final boolean enabled,expiryEnabled,notificationEnabled;private final long ttl;
    public OperationsService(OrderStore store,OrderAdminAccess access,DataSource datasource,OrderAuditPort audit,Clock clock,@Value("${commerce.jobs.enabled:true}")boolean enabled,@Value("${commerce.jobs.expiry-enabled:true}")boolean expiryEnabled,@Value("${commerce.jobs.notification-enabled:true}")boolean notificationEnabled,@Value("${commerce.order.confirmation-ttl-seconds:86400}")long ttl){this.store=store;this.access=access;this.datasource=datasource;this.audit=audit;this.clock=clock;this.enabled=enabled;this.expiryEnabled=expiryEnabled;this.notificationEnabled=notificationEnabled;this.ttl=ttl;}
    public void staff(){access.requireStaff();}
    public Map<String,Object> snapshot(){
        access.requireStaff();var pool=new LinkedHashMap<String,Object>();for(String key:List.of("active","idle","total","awaiting","max","minIdle"))pool.put(key,null);pool.put("available",false);
        try{HikariDataSource h=datasource instanceof HikariDataSource current?current:datasource.unwrap(HikariDataSource.class);var bean=h.getHikariPoolMXBean();if(bean!=null){pool.put("active",bean.getActiveConnections());pool.put("idle",bean.getIdleConnections());pool.put("total",bean.getTotalConnections());pool.put("awaiting",bean.getThreadsAwaitingConnection());pool.put("max",h.getMaximumPoolSize());pool.put("minIdle",h.getMinimumIdle());pool.put("available",true);}}catch(Exception unsupported){/* Explicit unavailable snapshot instead of guessed pool data. */}
        var counts=new LinkedHashMap<String,Object>();for(String state:List.of("PENDING","RETRY","DEAD","SENT"))counts.put(state.toLowerCase(Locale.ROOT),0L);for(var row:store.rows("SELECT state,CAST(COUNT(*) AS SIGNED) AS count FROM commerce_notification_outbox GROUP BY state"))counts.put(row.get("state").toString().toLowerCase(Locale.ROOT),row.get("count"));
        var attention=store.rows("SELECT id AS orderId,order_number AS orderNumber,expiry_attempts AS attempts,expiry_retry_after AS retryAt FROM orders WHERE status='PENDING_CONFIRMATION' AND expiry_attempts>0 ORDER BY expiry_retry_after,id LIMIT 20");
        var dead=store.rows("SELECT e.id,e.order_id AS orderId,o.order_number AS orderNumber,e.notification_type AS type,e.attempts,e.last_error AS lastError,e.updated_at AS updatedAt FROM commerce_notification_outbox e JOIN orders o ON o.id=e.order_id WHERE e.state='DEAD' ORDER BY e.updated_at DESC,e.id DESC LIMIT 20");
        return Map.of("pool",pool,"jobs",Map.of("enabled",enabled,"expiryEnabled",expiryEnabled,"notificationEnabled",notificationEnabled,"confirmationTtlSeconds",ttl),"outbox",counts,"expiryAttention",attention,"deadEvents",dead);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> retry(long id,String reason){
        long actor=access.requireStaff();if(reason==null||reason.isBlank()||reason.trim().length()>1000)throw CommerceException.invalid(Map.of("reason","Nhập lý do thử lại từ1 đến1000 ký tự."));var orders=store.jdbc().queryForList("SELECT order_id FROM commerce_notification_outbox WHERE id=?",Long.class,id);if(orders.isEmpty())throw CommerceException.notFound("Không tìm thấy thông báo lỗi.");long order=orders.get(0);access.lock(order,actor);String state=store.jdbc().queryForObject("SELECT state FROM commerce_notification_outbox WHERE id=? FOR UPDATE",String.class,id);if(!"DEAD".equals(state))throw CommerceException.conflict("NOTIFICATION_NOT_DEAD","Thông báo không ở trạng thái cần đối soát và thử lại.");var now=LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);store.jdbc().update("UPDATE commerce_notification_outbox SET state='RETRY',attempts=0,next_attempt_at=?,last_error=NULL,updated_at=? WHERE id=?",now,now,id);audit.append(actor,order,"NOTIFICATION_RETRIED",Map.of("outboxId",id,"state","DEAD"),Map.of("outboxId",id,"state","RETRY","reason",reason.trim()));return snapshot();
    }
}
