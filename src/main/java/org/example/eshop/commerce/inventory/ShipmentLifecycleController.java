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
@RequestMapping("/api/admin/inventory/shipments")
public class ShipmentLifecycleController {
    private final ShipmentLifecycleService service;
    public ShipmentLifecycleController(ShipmentLifecycleService service) { this.service = service; }

    @GetMapping
    public ShipmentLifecycleService.ShipmentPage list(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status) {
        return service.list(page, size, status);
    }

    @PostMapping("/{shipmentId}/status")
    public ShipmentLifecycleService.ShipmentView changeStatus(@PathVariable long shipmentId,
            @Valid @RequestBody ShipmentStatusRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return service.changeStatus(shipmentId, request, key);
    }
}
