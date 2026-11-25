package org.example.eshop.commerce.payment;

import org.example.eshop.commerce.common.CommerceException;
import org.example.eshop.commerce.order.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
public class SePayReconciliationController {
    private final OrderAdminAccess access;
    private final OrderStore store;
    private final SePaySettings settings;
    private final SePayApiClient client;
    private final SePayEventService events;
    private final PaymentQueryService queries;
    public SePayReconciliationController(OrderAdminAccess access,OrderStore store,SePaySettings settings,SePayApiClient client,SePayEventService events,PaymentQueryService queries){this.access=access;this.store=store;this.settings=settings;this.client=client;this.events=events;this.queries=queries;}
    public record Request(String transactionId){}
    @GetMapping("/api/payments/sepay/config") public Object config(){return settings.publicView();}
    @PostMapping("/api/admin/payments/{id}/sepay/reconcile")
    public Object reconcile(@PathVariable long id,@RequestBody Request request){
        access.requireStaff();settings.requireEnabled();
        var summary=queries.staff(id);
        var selected=((List<?>)summary.get("items")).stream().map(v->(Map<?,?>)v).filter(p->((Number)p.get("id")).longValue()==id).findFirst().orElseThrow();
        if(!SePaySettings.PROVIDER.equals(selected.get("provider")))throw CommerceException.conflict("SEPAY_PAYMENT_REQUIRED","Khoản thanh toán không thuộc SePay Sandbox.");
        // HTTP completes before the processor opens its database transaction.
        var result=events.process(client.transaction(request.transactionId()),id);
        var updated=new LinkedHashMap<>(queries.staff(id));updated.put("reconciliation",result);return updated;
    }
    @GetMapping("/api/admin/payments/sepay/events")
    public Object recent(@RequestParam(defaultValue="FAILED")String status,@RequestParam(defaultValue="0")int page){
        access.requireStaff();
        if(page<0||page>10000||!Set.of("FAILED","IGNORED","PROCESSED").contains(status))throw CommerceException.badRequest("INVALID_FILTER","Bộ lọc sự kiện không hợp lệ.");
        return Map.of("items",store.rows("SELECT e.id,e.payment_id AS paymentId,p.order_id AS orderId,e.provider_event_id AS providerEventId,e.processing_status AS status,e.error_message AS reason,e.received_at AS receivedAt,JSON_UNQUOTE(JSON_EXTRACT(e.payload,'$.code')) AS code,JSON_UNQUOTE(JSON_EXTRACT(e.payload,'$.reference')) AS reference,JSON_UNQUOTE(JSON_EXTRACT(e.payload,'$.amount')) AS amount FROM payment_events e LEFT JOIN payments p ON p.id=e.payment_id WHERE e.provider=? AND e.processing_status=? ORDER BY e.id DESC LIMIT 25 OFFSET ?",SePaySettings.PROVIDER,status,(long)page*25),"page",page,"size",25);
    }
}
