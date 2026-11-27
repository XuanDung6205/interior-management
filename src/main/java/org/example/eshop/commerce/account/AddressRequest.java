package org.example.eshop.commerce.account;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AddressRequest(
        @Size(max = 80) String label,
        @NotBlank @Size(max = 150) String recipientName,
        @NotBlank @Size(max = 30) @Pattern(regexp = "[+0-9 .()\\-]+") String phone,
        @NotBlank @Size(max = 255) String addressLine1,
        @Size(max = 255) String addressLine2,
        @Size(max = 120) String wardName,
        @Size(max = 120) String districtName,
        @Size(max = 120) String provinceName,
        @Size(max = 20) String postalCode,
        @Pattern(regexp = "[A-Za-z]{2}") String countryCode
) {}
