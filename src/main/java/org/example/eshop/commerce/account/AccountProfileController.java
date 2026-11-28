package org.example.eshop.commerce.account;

import jakarta.validation.Valid;
import org.example.eshop.commerce.common.CommerceException;
import org.example.eshop.commerce.port.CurrentActorPort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/account/profile")
public class AccountProfileController {
    private final CurrentActorPort currentActor;
    private final AccountProfileService profiles;

    public AccountProfileController(CurrentActorPort currentActor, AccountProfileService profiles) {
        this.currentActor = currentActor;
        this.profiles = profiles;
    }

    @GetMapping
    public AccountProfileResponse get() {
        return profiles.get(currentUserId());
    }

    @PutMapping
    public AccountProfileResponse update(@Valid @RequestBody AccountProfileRequest request) {
        return profiles.update(currentUserId(), request);
    }

    private long currentUserId() {
        Long userId = currentActor.currentActor().userId();
        if (userId == null) throw CommerceException.forbidden("A signed-in account is required.");
        return userId;
    }
}
