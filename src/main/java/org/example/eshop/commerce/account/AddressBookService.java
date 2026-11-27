package org.example.eshop.commerce.account;

import org.example.eshop.commerce.common.CommerceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AddressBookService {
    private final JdbcAddressBookRepository addresses;

    public AddressBookService(JdbcAddressBookRepository addresses) {
        this.addresses = addresses;
    }

    @Transactional(readOnly = true)
    public List<AddressResponse> list(long userId) {
        return addresses.findByUserId(userId);
    }

    @Transactional
    public AddressResponse create(long userId, AddressRequest request) {
        lockUser(userId);
        boolean firstAddress = addresses.countByUserId(userId) == 0;
        return addresses.insert(userId, request, firstAddress);
    }

    @Transactional
    public AddressResponse update(long userId, long addressId, AddressRequest request) {
        lockUser(userId);
        if (addresses.findOwned(userId, addressId).isEmpty()) {
            throw addressNotFound();
        }
        addresses.updateOwned(userId, addressId, request);
        return addresses.findOwned(userId, addressId).orElseThrow(AddressBookService::addressNotFound);
    }

    @Transactional
    public AddressResponse makeDefault(long userId, long addressId) {
        lockUser(userId);
        if (addresses.findOwned(userId, addressId).isEmpty()) {
            throw addressNotFound();
        }
        addresses.clearDefaultForUser(userId);
        if (addresses.setDefaultOwned(userId, addressId) != 1) {
            throw addressNotFound();
        }
        return addresses.findOwned(userId, addressId).orElseThrow(AddressBookService::addressNotFound);
    }

    @Transactional
    public void delete(long userId, long addressId) {
        lockUser(userId);
        if (addresses.deleteOwned(userId, addressId) != 1) {
            throw addressNotFound();
        }
    }

    private void lockUser(long userId) {
        if (!addresses.lockUser(userId)) {
            throw CommerceException.notFound("ACCOUNT_NOT_FOUND", "Customer account was not found.");
        }
    }

    private static CommerceException addressNotFound() {
        return CommerceException.notFound("ADDRESS_NOT_FOUND", "Address was not found.");
    }
}
