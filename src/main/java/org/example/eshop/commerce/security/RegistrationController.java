package org.example.eshop.commerce.security;

import org.example.eshop.commerce.common.CommerceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RegistrationController {
    private final RegistrationService registration;

    public RegistrationController(RegistrationService registration) { this.registration = registration; }

    @PostMapping("/api/session/register")
    public ResponseEntity<RegistrationService.Result> register(@RequestBody RegistrationService.Request request,
                                                               Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof CommercePrincipal)
            throw CommerceException.forbidden("Đăng xuất trước khi tạo tài khoản mới.");
        return ResponseEntity.status(HttpStatus.CREATED).body(registration.register(request));
    }
}
