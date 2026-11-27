package org.example.eshop.commerce.account;

import org.example.eshop.commerce.common.CommerceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AddressBookServiceTest {
    @Mock
    JdbcAddressBookRepository addresses;

    @InjectMocks
    AddressBookService service;

    @Test
    void firstAddressIsCreatedAsDefault() {
        var request = request();
        var created = response(10L, true);
        when(addresses.lockUser(7L)).thenReturn(true);
        when(addresses.countByUserId(7L)).thenReturn(0);
        when(addresses.insert(7L, request, true)).thenReturn(created);

        assertSame(created, service.create(7L, request));

        InOrder order = inOrder(addresses);
        order.verify(addresses).lockUser(7L);
        order.verify(addresses).countByUserId(7L);
        order.verify(addresses).insert(7L, request, true);
    }

    @Test
    void changingDefaultClearsOldDefaultBeforeSettingNewOne() {
        var existing = response(10L, false);
        var updated = response(10L, true);
        when(addresses.lockUser(7L)).thenReturn(true);
        when(addresses.findOwned(7L, 10L)).thenReturn(Optional.of(existing)).thenReturn(Optional.of(updated));
        when(addresses.clearDefaultForUser(7L)).thenReturn(1);
        when(addresses.setDefaultOwned(7L, 10L)).thenReturn(1);

        assertSame(updated, service.makeDefault(7L, 10L));

        InOrder order = inOrder(addresses);
        order.verify(addresses).lockUser(7L);
        order.verify(addresses).findOwned(7L, 10L);
        order.verify(addresses).clearDefaultForUser(7L);
        order.verify(addresses).setDefaultOwned(7L, 10L);
        order.verify(addresses).findOwned(7L, 10L);
    }

    @Test
    void foreignAddressDoesNotClearCurrentDefault() {
        when(addresses.lockUser(7L)).thenReturn(true);
        when(addresses.findOwned(7L, 99L)).thenReturn(Optional.empty());

        CommerceException error = assertThrows(CommerceException.class, () -> service.makeDefault(7L, 99L));

        assertEquals(404, error.getStatus().value());
        assertEquals("ADDRESS_NOT_FOUND", error.getCode());
        verify(addresses, never()).clearDefaultForUser(7L);
        verify(addresses, never()).setDefaultOwned(7L, 99L);
    }

    @Test
    void writeRequiresAnExistingAccount() {
        when(addresses.lockUser(7L)).thenReturn(false);

        CommerceException error = assertThrows(CommerceException.class, () -> service.create(7L, request()));

        assertEquals(404, error.getStatus().value());
        verify(addresses, never()).insert(7L, request(), true);
    }

    private static AddressRequest request() {
        return new AddressRequest("Home", "Nguyen An", "+84901234567", "1 Main Street",
                null, "Ward 1", "District 1", "Ho Chi Minh City", null, "VN");
    }

    private static AddressResponse response(long id, boolean isDefault) {
        return new AddressResponse(id, "Home", "Nguyen An", "+84901234567", "1 Main Street",
                null, "Ward 1", "District 1", "Ho Chi Minh City", null, "VN", isDefault);
    }
}
