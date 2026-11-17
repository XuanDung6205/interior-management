package org.example.eshop.commerce.payment;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
public class RefundAdminController {
    private final RefundQueryService queries;private final RefundService service;
    public RefundAdminController(RefundQueryService queries,RefundService service){this.queries=queries;this.service=service;}
    @GetMapping("/api/admin/refunds") public Object list(@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="10")int size,@RequestParam(defaultValue="")String status,@RequestParam(defaultValue="")String query){return queries.list(page,size,status,query);}
    @GetMapping("/api/admin/refunds/{id}") public Object detail(@PathVariable long id){return queries.detail(id);}
    @GetMapping("/api/admin/orders/{id}/refunds") public Object order(@PathVariable long id){return queries.order(id);}
    public record Request(long paymentId,String amount,String reason,Long returnRequestId){}
    @PostMapping("/api/admin/orders/{id}/refunds") @ResponseStatus(HttpStatus.CREATED) public Object create(@PathVariable long id,@RequestBody Request r,@RequestHeader(value="Idempotency-Key",required=false)String key){return service.create(id,r.paymentId(),r.amount(),r.reason(),r.returnRequestId(),key);}
    public record Proof(String reference,String note,Boolean acknowledged){}
    @PostMapping("/api/admin/refunds/{id}/confirm") public Object confirm(@PathVariable long id,@RequestBody Proof r){return service.confirm(id,r.reference(),r.note(),r.acknowledged());}
    public record Cancellation(String reason,Boolean acknowledged){}
    @PostMapping("/api/admin/refunds/{id}/cancel") public Object cancel(@PathVariable long id,@RequestBody Cancellation r){return service.cancel(id,r.reason(),r.acknowledged());}
}
