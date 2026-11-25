package org.example.eshop.commerce.payment;

import org.springframework.web.bind.annotation.*;

@RestController
public class SePayRefundController {
    private final SePayRefundService refunds;
    public SePayRefundController(SePayRefundService refunds){this.refunds=refunds;}
    public record Submit(Boolean acknowledged){}
    @PostMapping("/api/admin/refunds/{id}/sepay/submit") public Object submit(@PathVariable long id,@RequestBody Submit request){return refunds.submit(id,request.acknowledged());}
    @PostMapping("/api/admin/refunds/{id}/sepay/sync") public Object sync(@PathVariable long id){return refunds.sync(id);}
}
