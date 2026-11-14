package org.example.eshop.commerce.inventory;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/inventory")
public class StockOperationsController {
    private final StockOperationsService service;

    public StockOperationsController(StockOperationsService service) { this.service = service; }

    @GetMapping("/balances")
    public StockOperationsService.StockBalanceView balance(@RequestParam long warehouseId, @RequestParam long variantId) {
        return service.balance(warehouseId, variantId);
    }

    @GetMapping("/movements")
    public StockOperationsService.MovementPage movements(@RequestParam(required = false) Long warehouseId,
                                                         @RequestParam(defaultValue = "0") int page,
                                                         @RequestParam(defaultValue = "20") int size) {
        return service.movements(warehouseId, page, size);
    }

    @PostMapping("/adjustments")
    public StockOperationsService.AdjustmentView adjust(@Valid @RequestBody StockAdjustmentRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return service.adjust(request, key);
    }

    @PostMapping("/transfers")
    public StockOperationsService.TransferView transfer(@Valid @RequestBody StockTransferRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return service.transfer(request, key);
    }
}
