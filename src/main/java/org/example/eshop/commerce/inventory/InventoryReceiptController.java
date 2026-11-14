package org.example.eshop.commerce.inventory;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/inventory")
public class InventoryReceiptController {
    private final InventoryReceiptService service;

    public InventoryReceiptController(InventoryReceiptService service) {
        this.service = service;
    }

    @GetMapping("/warehouses")
    public List<InventoryReceiptService.WarehouseView> warehouses() {
        return service.activeWarehouses();
    }

    @GetMapping("/receipts")
    public InventoryReceiptService.ReceiptPage receipts(
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.receipts(warehouseId, page, size);
    }

    @PostMapping("/receipts")
    public InventoryReceiptService.ReceiptView receive(
            @Valid @RequestBody InventoryReceiptRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return service.receive(request, idempotencyKey);
    }
}
