package org.example.eshop.commerce.payment;

import org.example.eshop.commerce.common.CommerceException;
import org.example.eshop.commerce.order.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;

/** Claims and financial mutations are short transactions; provider IO happens between them. */
@Service
public class SePayAutomationService {
    private final OrderStore store;private final SePayApiClient client;private final SePayEventService events;
    private final SePaySettings settings;private final SePayAutomationSettings config;
    private final OrderCancellationService cancellation;private final Clock clock;private final TransactionTemplate tx;
    public SePayAutomationService(OrderStore store,SePayApiClient client,SePayEventService events,SePaySettings settings,SePayAutomationSettings config,OrderCancellationService cancellation,Clock clock,PlatformTransactionManager manager){
        this.store=store;this.client=client;this.events=events;this.settings=settings;this.config=config;this.cancellation=cancellation;this.clock=clock;tx=new TransactionTemplate(manager);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    private LocalDateTime now(){return LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);}
    public Map<String,Object> jobs(){var result=new LinkedHashMap<>(config.view(settings));result.put("batchSize",config.batchSize);result.put("canRun",settings.canReconcile());result.put("queue",store.jdbc().queryForMap("SELECT COUNT(*) AS pending,COALESCE(SUM(sepay_next_check_at IS NULL OR sepay_next_check_at<=?),0) AS due,COALESCE(SUM(sepay_lease_until>?),0) AS leased FROM payments WHERE provider=? AND status='PENDING'",now(),now(),SePaySettings.PROVIDER));return result;}
    public Map<String,Object> runBatch(){
        var stats=new LinkedHashMap<String,Object>();for(String key:List.of("scanned","reconciled","expired","deferred","failed"))stats.put(key,0);
        if(!settings.canReconcile())return stats;
        var at=now();var rows=store.rows("SELECT p.id FROM payments p JOIN orders o ON o.id=p.order_id WHERE p.provider=? AND (p.sepay_next_check_at IS NULL OR p.sepay_next_check_at<=?) AND (p.sepay_lease_until IS NULL OR p.sepay_lease_until<=?) AND ((p.status='PENDING' AND o.status<>'CANCELLED') OR (o.status='CANCELLED' AND p.status IN ('PENDING','CANCELLED','EXPIRED') AND o.cancelled_at>=?) OR (p.status='SUCCEEDED' AND (p.sepay_transaction_id IS NULL OR p.sepay_bank_account_id IS NULL))) ORDER BY COALESCE(p.sepay_next_check_at,p.created_at),p.id LIMIT ?",SePaySettings.PROVIDER,at,at,at.minusHours(config.lateHours),config.batchSize);
        for(var row:rows){increment(stats,"scanned");try{var result=reconcile(((Number)row.get("id")).longValue());if((Boolean)result.get("expired"))increment(stats,"expired");else if(((Number)result.get("matched")).intValue()>0)increment(stats,"reconciled");else increment(stats,"deferred");}catch(Exception e){increment(stats,"failed");}}
        return stats;
    }
    private static void increment(Map<String,Object> stats,String key){stats.put(key,((Number)stats.get(key)).intValue()+1);}
    public Map<String,Object> reconcile(long payment){
        settings.requireEnabled();String lease=UUID.randomUUID().toString();var at=now();
        var rows=store.rows("SELECT sepay_code AS code FROM payments WHERE id=? AND provider=?",payment,SePaySettings.PROVIDER);
        if(rows.isEmpty())throw CommerceException.conflict("SEPAY_PAYMENT_REQUIRED","Khoản thanh toán không thuộc SePay Sandbox.");
        boolean claimed=Boolean.TRUE.equals(tx.execute(s->store.jdbc().update("UPDATE payments SET sepay_lease_token=?,sepay_lease_until=? WHERE id=? AND provider=? AND (sepay_lease_until IS NULL OR sepay_lease_until<=?)",lease,at.plusMinutes(5),payment,SePaySettings.PROVIDER,at)==1));
        if(!claimed)throw CommerceException.conflict("SEPAY_CHECK_BUSY","Khoản thanh toán đang được đối soát. Hãy thử lại sau.");
        try{
            var found=client.searchCode(Objects.toString(rows.get(0).get("code"),""));int matched=0;boolean safe=found.complete();
            for(var t:found.transfers()){
                if(!Objects.equals(t.code(),rows.get(0).get("code"))){safe=false;continue;}
                var outcome=events.process(t,payment);if("PROCESSED".equals(outcome.get("processingStatus")))matched++;else safe=false;
            }
            boolean expire=safe&&found.transfers().isEmpty()&&Boolean.TRUE.equals(tx.execute(s->expireLocked(payment,lease)));
            finish(payment,lease,safe?"VERIFIED":"INCOMPLETE",!safe);
            return Map.of("checked",found.transfers().size(),"matched",matched,"complete",safe,"expired",expire);
        }catch(RuntimeException e){finish(payment,lease,"API_OR_PROCESSING_ERROR",true);throw e;}
    }
    private boolean expireLocked(long payment,String lease){
        var ids=store.rows("SELECT p.order_id AS orderId,o.user_id AS owner FROM payments p JOIN orders o ON o.id=p.order_id WHERE p.id=?",payment);if(ids.isEmpty()||ids.get(0).get("owner")==null)return false;
        long order=((Number)ids.get(0).get("orderId")).longValue();store.jdbc().queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE",Long.class,ids.get(0).get("owner"));var header=store.find(order,true);
        if(!"PENDING_CONFIRMATION".equals(header.get("status"))||store.dispatched(order)||store.jdbc().queryForObject("SELECT confirmed_at FROM orders WHERE id=?",java.sql.Timestamp.class,order)!=null)return false;
        var coupons=store.jdbc().queryForList("SELECT coupon_id FROM orders WHERE id=? AND coupon_id IS NOT NULL",Long.class,order);if(!coupons.isEmpty())store.jdbc().queryForObject("SELECT id FROM coupons WHERE id=? FOR UPDATE",Long.class,coupons.get(0));
        var payments=store.rows("SELECT id,provider,status,expires_at AS expiresAt,sepay_lease_token AS lease,sepay_lease_until AS leaseUntil FROM payments WHERE order_id=? ORDER BY id FOR UPDATE",order);var at=now();
        for(var p:payments){if(!SePaySettings.PROVIDER.equals(p.get("provider"))||!"PENDING".equals(p.get("status"))||p.get("expiresAt")==null||Instant.parse(p.get("expiresAt").toString()).isAfter(clock.instant()))return false;}
        // Only this single active attempt has a current negative proof. Other attempts require staff review.
        if(payments.size()!=1||!lease.equals(payments.get(0).get("lease"))||payments.get(0).get("leaseUntil")==null||!Instant.parse(payments.get(0).get("leaseUntil").toString()).isAfter(clock.instant()))return false;
        if(store.jdbc().queryForObject("SELECT COUNT(*) FROM refunds WHERE order_id=? AND status IN ('PENDING','SUCCEEDED')",Integer.class,order)>0||store.jdbc().queryForObject("SELECT COUNT(*) FROM payment_events WHERE payment_id=? AND processing_status IN ('FAILED','PROCESSED')",Integer.class,payment)>0)return false;
        cancellation.cancelExpiredLocked(order,"Đơn SePay Sandbox hết thời hạn thanh toán; đã đối soát không có giao dịch.");return true;
    }
    private void finish(long payment,String lease,String result,boolean failed){tx.executeWithoutResult(s->{
        var rows=store.jdbc().queryForList("SELECT sepay_check_attempts FROM payments WHERE id=? AND sepay_lease_token=? FOR UPDATE",Integer.class,payment,lease);if(rows.isEmpty())return;int attempts=failed?Math.min(1000000,rows.get(0)+1):0;long delay=failed?Math.min(3600,config.intervalSeconds*(1L<<Math.min(attempts,6))):config.intervalSeconds;
        store.jdbc().update("UPDATE payments SET sepay_lease_token=NULL,sepay_lease_until=NULL,sepay_last_check_at=?,sepay_last_check_result=?,sepay_check_attempts=?,sepay_next_check_at=? WHERE id=? AND sepay_lease_token=?",now(),result,attempts,now().plusSeconds(delay),payment,lease);
    });}
}
