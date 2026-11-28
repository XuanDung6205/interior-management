package org.example.eshop.commerce.security;

import org.example.eshop.commerce.port.CurrentActorPort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/users")
public class AdminAccountController {
    private final AdminAccountService accounts;
    private final CurrentActorPort currentActor;

    public AdminAccountController(AdminAccountService accounts, CurrentActorPort currentActor) {
        this.accounts = accounts;
        this.currentActor = currentActor;
    }

    public record StatusRequest(String status) {}

    @GetMapping
    public AdminAccountService.AccountPage list(
            @RequestParam(defaultValue = "") String query,
            @RequestParam(defaultValue = "") String role,
            @RequestParam(defaultValue = "") String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return accounts.list(query, role, status, page, size);
    }

    @PatchMapping("/{id}/status")
    public AdminAccountService.Account changeStatus(@PathVariable long id,
                                                     @RequestBody StatusRequest request) {
        Long actorId = currentActor.currentActor().userId();
        if (actorId == null) throw new IllegalStateException("Authenticated administrator id is unavailable.");
        return accounts.changeStatus(id, request == null ? null : request.status(), actorId);
    }
}
