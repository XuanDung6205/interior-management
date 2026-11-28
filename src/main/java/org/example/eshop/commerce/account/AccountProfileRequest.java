package org.example.eshop.commerce.account;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AccountProfileRequest(
        @NotBlank @Size(min = 2, max = 150) String fullName,
        @NotBlank @Email @Size(max = 254) String email,
        @Size(max = 30) @Pattern(regexp = "[+0-9 .()\\-]*") String phone
) {}
