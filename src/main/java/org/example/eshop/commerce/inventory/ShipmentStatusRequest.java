package org.example.eshop.commerce.inventory;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ShipmentStatusRequest(
        @NotBlank String targetStatus,
        @NotBlank @Size(max = 1000) String reason
) {}
