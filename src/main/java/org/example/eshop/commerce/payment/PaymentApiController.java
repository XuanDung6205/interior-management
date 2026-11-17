package org.example.eshop.commerce.payment;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/orders/{id}/payments")
public class PaymentApiController {
    private final PaymentQueryService queries;private final PaymentService service;
    public PaymentApiController(PaymentQueryService queries,PaymentService service){this.queries=queries;this.service=service;}
    @GetMapping public Object get(@PathVariable long id){return queries.customer(id);}
    public record CreateRequest(String method){}
    @PostMapping @ResponseStatus(HttpStatus.CREATED) public Object create(@PathVariable long id,@RequestBody CreateRequest request,@RequestHeader(value="Idempotency-Key",required=false)String key){return service.create(id,request.method(),key);}
}
