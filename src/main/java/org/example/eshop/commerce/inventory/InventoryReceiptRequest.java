package org.example.eshop.commerce.inventory;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record InventoryReceiptRequest(
        @NotNull @Positive Long warehouseId,
        @NotNull @Positive Long variantId,
        @NotNull @Positive Integer quantity,
        @NotBlank @Size(max = 100) String referenceCode,
        @NotBlank @Size(max = 1000) String reason
) {}
