package org.example.eshop.commerce.inventory;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/inventory/orders")
public class OrderDispatchController {
    private final OrderDispatchService service;
    public OrderDispatchController(OrderDispatchService service) { this.service = service; }

    @GetMapping
    public OrderDispatchService.QueuePage queue(@RequestParam(defaultValue = "0") int page,
                                                 @RequestParam(defaultValue = "20") int size) {
        return service.queue(page, size);
    }

    @GetMapping("/{orderId}/holds")
    public OrderDispatchService.DispatchPreview holds(@PathVariable long orderId) { return service.preview(orderId); }

    @PostMapping("/{orderId}/dispatch")
    public OrderDispatchService.DispatchResult dispatch(@PathVariable long orderId,
            @Valid @RequestBody OrderDispatchRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return service.dispatch(orderId, request, key);
    }
}
