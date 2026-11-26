package org.example.eshop.commerce.integration;

import org.example.eshop.commerce.port.OrderAuditPort;
import org.example.eshop.commerce.cache.Tv5Cache;
import org.example.eshop.commerce.notification.OrderNotifications;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;

/** Append-only bridge to TV1's existing audit_logs, in the business transaction. */
@Repository
public class JdbcOrderAuditAdapter implements OrderAuditPort {
    private final JdbcTemplate db;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Tv5Cache cache;
    public JdbcOrderAuditAdapter(JdbcTemplate db,ObjectMapper mapper,Clock clock,Tv5Cache cache){this.db=db;this.mapper=mapper;this.clock=clock;this.cache=cache;}
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public void append(long actorId,long orderId,String action,Map<String,Object> before,Map<String,Object> after){
        String name=db.queryForObject("SELECT COALESCE(full_name,username) FROM users WHERE id=?",String.class,actorId);
        record(actorId,name,orderId,action,before,after);
    }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public void appendSystem(long orderId,String action,Map<String,Object> before,Map<String,Object> after){record(null,"Hệ thống",orderId,action,before,after);}
    private void record(Long actor,String name,long order,String action,Map<String,Object> before,Map<String,Object> after){String key=UUID.randomUUID().toString();db.update("INSERT INTO audit_logs(actor_user_id,actor_name_snapshot,action,entity_type,entity_id,before_data,after_data,request_id,created_at) VALUES(?,?,?,'ORDER',?,?,?,?,?)",actor,name,action,Long.toString(order),mapper.writeValueAsString(before),mapper.writeValueAsString(after),key,LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC));OrderNotifications.fromAudit(db,clock,order,"audit:"+key,action,after);cache.evictAfterCommit(Tv5Cache.DASHBOARD);}
}
