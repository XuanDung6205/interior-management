package org.example.eshop.commerce.operations;
import org.example.eshop.commerce.order.OrderExpiryJob;
import org.example.eshop.commerce.notification.NotificationJob;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/admin/operations")
public class OperationsController {
    private final OperationsService service;private final OrderExpiryJob expiry;private final NotificationJob notifications;
    public OperationsController(OperationsService service,OrderExpiryJob expiry,NotificationJob notifications){this.service=service;this.expiry=expiry;this.notifications=notifications;}
    @GetMapping public Object snapshot(){return service.snapshot();}
    @PostMapping("/expiry/run")public Object expire(){service.staff();return expiry.runBatch();}
    @PostMapping("/notifications/run")public Object notifyUsers(){service.staff();return notifications.runBatch();}
    public record Retry(String reason){}
    @PostMapping("/notifications/{id}/retry")public Object retry(@PathVariable long id,@RequestBody Retry r){return service.retry(id,r.reason());}
}
