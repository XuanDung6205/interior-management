package org.example.eshop.commerce.inventory;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record OrderDispatchRequest(
        @NotNull @Positive Long warehouseId,
        @NotBlank @Size(max = 100) String carrier,
        @NotBlank @Size(max = 150) String trackingNumber,
        @Size(max = 1000) String note
) {}
