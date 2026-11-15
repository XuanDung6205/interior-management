package org.example.eshop.commerce.integration;

import org.example.eshop.commerce.port.OrderAuditPort;
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
    public JdbcOrderAuditAdapter(JdbcTemplate db,ObjectMapper mapper,Clock clock){this.db=db;this.mapper=mapper;this.clock=clock;}
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public void append(long actorId,long orderId,String action,Map<String,Object> before,Map<String,Object> after){
        String name=db.queryForObject("SELECT COALESCE(full_name,username) FROM users WHERE id=?",String.class,actorId);
        db.update("INSERT INTO audit_logs(actor_user_id,actor_name_snapshot,action,entity_type,entity_id,before_data,after_data,request_id,created_at) VALUES(?,?,?,'ORDER',?,?,?,?,?)",actorId,name,action,Long.toString(orderId),mapper.writeValueAsString(before),mapper.writeValueAsString(after),UUID.randomUUID().toString(),LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC));
    }
}
