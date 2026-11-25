package org.example.eshop.commerce.payment;

import org.example.eshop.commerce.common.CommerceException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.util.*;

@RestController
public class SePayQrController {
    private final PaymentQueryService queries;
    public SePayQrController(PaymentQueryService queries){this.queries=queries;}
    @GetMapping(value="/api/orders/{order}/payments/{id}/sepay/qr",produces="image/png")
    public ResponseEntity<byte[]> customer(@PathVariable long order,@PathVariable long id){return image(queries.customer(order),id);}
    @GetMapping(value="/api/admin/payments/{id}/sepay/qr",produces="image/png")
    public ResponseEntity<byte[]> staff(@PathVariable long id){return image(queries.staff(id),id);}
    private ResponseEntity<byte[]> image(Map<String,Object> summary,long id){
        var payment=((List<?>)summary.get("items")).stream().map(p->(Map<?,?>)p).filter(p->((Number)p.get("id")).longValue()==id).findFirst().orElseThrow(()->CommerceException.notFound("Không tìm thấy khoản thanh toán."));
        if(!(payment.get("sepay") instanceof Map<?,?> details)||!details.containsKey("qrUrl"))throw CommerceException.conflict("SEPAY_QR_UNAVAILABLE","Khoản thanh toán không còn chờ hoặc chưa bật SePay.");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.IMAGE_PNG)
                .body(SePayQrCode.png(details.get("accountNumber").toString(),new BigDecimal(payment.get("amount").toString()),details.get("transferContent").toString()));
    }
}
