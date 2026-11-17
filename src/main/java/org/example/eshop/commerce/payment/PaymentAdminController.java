package org.example.eshop.commerce.payment;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/admin/payments")
public class PaymentAdminController {
    private final PaymentQueryService queries;private final PaymentService service;
    public PaymentAdminController(PaymentQueryService queries,PaymentService service){this.queries=queries;this.service=service;}
    @GetMapping public Object list(@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="10")int size,@RequestParam(defaultValue="")String status,@RequestParam(defaultValue="")String method,@RequestParam(defaultValue="")String query){return queries.list(page,size,status,method,query);}
    @GetMapping("/{id}") public Object detail(@PathVariable long id){return queries.staff(id);}
    public record ConfirmRequest(String reference,String note,Boolean acknowledged){}
    @PostMapping("/{id}/confirm") public Object confirm(@PathVariable long id,@RequestBody ConfirmRequest request){return service.confirm(id,request.reference(),request.note(),request.acknowledged());}
}
