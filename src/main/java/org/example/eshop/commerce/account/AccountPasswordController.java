package org.example.eshop.commerce.account;

import org.example.eshop.commerce.common.CommerceException;
import org.example.eshop.commerce.port.CurrentActorPort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/account/password")
public class AccountPasswordController {
    private final CurrentActorPort currentActor;
    private final AccountPasswordService passwords;

    public AccountPasswordController(CurrentActorPort currentActor, AccountPasswordService passwords) {
        this.currentActor = currentActor;
        this.passwords = passwords;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void change(@RequestBody AccountPasswordRequest request) {
        Long userId = currentActor.currentActor().userId();
        if (userId == null) throw CommerceException.forbidden("A signed-in account is required.");
        passwords.change(userId, request);
    }
}
