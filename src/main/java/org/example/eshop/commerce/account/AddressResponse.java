package org.example.eshop.commerce.account;

public record AddressResponse(
        long id,
        String label,
        String recipientName,
        String phone,
        String addressLine1,
        String addressLine2,
        String wardName,
        String districtName,
        String provinceName,
        String postalCode,
        String countryCode,
        boolean isDefault
) {}
