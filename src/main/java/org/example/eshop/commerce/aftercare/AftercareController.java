package org.example.eshop.commerce.aftercare;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
public class AftercareController {
    private final AftercareService service;
    public AftercareController(AftercareService service){this.service=service;}

    public record RequestedItem(Long orderItemId,Integer quantity){}
    public record ReturnRequest(String reason,List<RequestedItem> items){}
    public record Decision(String decision,String reason){}
    public record ReceivedItem(Long returnItemId,Integer receivedQuantity,Integer restockQuantity,String conditionNote){}
    public record Receipt(Long warehouseId,List<ReceivedItem> items,Boolean acknowledged){}
    public record Installation(String reference,String note,Boolean acknowledged){}

    @GetMapping("/api/orders/{id}/returns")
    public Map<String,Object> customer(@PathVariable long id){return service.customer(id);}

    @PostMapping("/api/orders/{id}/returns") @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> request(@PathVariable long id,@RequestBody ReturnRequest body,
            @RequestHeader(value="Idempotency-Key",required=false) String key){return service.request(id,body,key);}

    @GetMapping("/api/admin/aftercare/orders/{id}")
    public Map<String,Object> staff(@PathVariable long id){return service.staff(id);}

    @PostMapping("/api/admin/aftercare/returns/{id}/decision")
    public Map<String,Object> decide(@PathVariable long id,@RequestBody Decision body){return service.decide(id,body);}

    @PostMapping("/api/admin/aftercare/returns/{id}/receive")
    public Map<String,Object> receive(@PathVariable long id,@RequestBody Receipt body){return service.receive(id,body);}

    @PostMapping("/api/admin/aftercare/shipments/{id}/installation")
    public Map<String,Object> install(@PathVariable long id,@RequestBody Installation body){return service.install(id,body);}
}
