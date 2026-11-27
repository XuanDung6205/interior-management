package org.example.eshop.commerce.account;

import org.example.eshop.commerce.common.CommerceException;
import org.example.eshop.commerce.port.CurrentActorPort;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/account/addresses")
public class AddressBookController {
    private final CurrentActorPort currentActor;
    private final AddressBookService addresses;

    public AddressBookController(CurrentActorPort currentActor, AddressBookService addresses) {
        this.currentActor = currentActor;
        this.addresses = addresses;
    }

    @GetMapping
    public List<AddressResponse> list() {
        return addresses.list(currentUserId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AddressResponse create(@Valid @RequestBody AddressRequest request) {
        return addresses.create(currentUserId(), request);
    }

    @PutMapping("/{id}")
    public AddressResponse update(@PathVariable long id, @Valid @RequestBody AddressRequest request) {
        return addresses.update(currentUserId(), id, request);
    }

    @PatchMapping("/{id}/default")
    public AddressResponse makeDefault(@PathVariable long id) {
        return addresses.makeDefault(currentUserId(), id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        addresses.delete(currentUserId(), id);
    }

    private long currentUserId() {
        Long userId = currentActor.currentActor().userId();
        if (userId == null) {
            throw CommerceException.forbidden("A signed-in customer account is required.");
        }
        return userId;
    }
}
