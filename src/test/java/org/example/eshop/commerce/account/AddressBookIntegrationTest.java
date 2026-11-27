package org.example.eshop.commerce.account;

import org.example.eshop.commerce.CommerceApplication;
import org.example.eshop.commerce.common.CommerceException;
import org.example.eshop.commerce.port.CheckoutAddressPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(classes = CommerceApplication.class)
@ActiveProfiles("commerce")
@TestPropertySource(properties = "commerce.jobs.enabled=false")
@EnabledIfEnvironmentVariable(named = "TV3_TEST_DB_URL", matches = ".*furniture_shop_test_.*")
class AddressBookIntegrationTest {
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("TV3_TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("DB_USERNAME", "root"));
        registry.add("spring.datasource.password", () -> System.getenv("DB_PASSWORD"));
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AddressBookService addresses;

    @Autowired
    CheckoutAddressPort checkoutAddresses;

    private long ownerId;
    private long otherUserId;
    private String ownerUsername;
    private String otherUsername;

    @BeforeEach
    void createUsers() {
        String database = jdbc.queryForObject("SELECT DATABASE()", String.class);
        assertTrue(database.startsWith("furniture_shop_test_"));
        ownerUsername = createUser();
        otherUsername = createUser();
        ownerId = userId(ownerUsername);
        otherUserId = userId(otherUsername);
    }

    @AfterEach
    void removeUsers() {
        if (ownerUsername != null) {
            jdbc.update("DELETE FROM users WHERE username=?", ownerUsername);
        }
        if (otherUsername != null) {
            jdbc.update("DELETE FROM users WHERE username=?", otherUsername);
        }
    }

    @Test
    void createsDefaultsOrdersForCheckoutAndChangesDefaultWithoutUniqueConflict() {
        AddressResponse first = addresses.create(ownerId, request("Home", "1 Main Street"));
        AddressResponse second = addresses.create(ownerId, request("Office", "2 Main Street"));

        assertTrue(first.isDefault());
        assertFalse(second.isDefault());
        assertEquals(first.id(), checkoutAddresses.findByUserId(ownerId).get(0).id());

        AddressResponse selected = addresses.makeDefault(ownerId, second.id());

        assertTrue(selected.isDefault());
        assertEquals(second.id(), addresses.list(ownerId).get(0).id());
        assertEquals(second.id(), checkoutAddresses.findByUserId(ownerId).get(0).id());
        assertFalse(addresses.list(ownerId).get(1).isDefault());
    }

    @Test
    void updateAndDeleteAreRestrictedToTheOwningUser() {
        AddressResponse ownAddress = addresses.create(ownerId, request("Home", "1 Main Street"));
        AddressResponse foreignAddress = addresses.create(otherUserId, request("Home", "3 Main Street"));

        AddressResponse updated = addresses.update(ownerId, ownAddress.id(), request("Updated", "4 Main Street"));
        assertEquals("Updated", updated.label());
        assertEquals("4 Main Street", updated.addressLine1());

        CommerceException updateError = assertThrows(CommerceException.class,
                () -> addresses.update(ownerId, foreignAddress.id(), request("Hijack", "5 Main Street")));
        assertEquals(404, updateError.getStatus().value());
        assertEquals("ADDRESS_NOT_FOUND", updateError.getCode());

        CommerceException deleteError = assertThrows(CommerceException.class,
                () -> addresses.delete(ownerId, foreignAddress.id()));
        assertEquals(404, deleteError.getStatus().value());
        assertEquals(1, addresses.list(otherUserId).size());

        addresses.delete(ownerId, ownAddress.id());
        assertTrue(addresses.list(ownerId).isEmpty());
    }

    private String createUser() {
        String username = "address-" + UUID.randomUUID().toString().substring(0, 12);
        jdbc.update("""
                INSERT INTO users(username,email,password_hash,full_name,status)
                VALUES(?,?,?,'Address Test','ACTIVE')
                """, username, username + "@example.invalid", "{bcrypt}test-only-not-a-login-password");
        return username;
    }

    private long userId(String username) {
        return jdbc.queryForObject("SELECT id FROM users WHERE username=?", Long.class, username);
    }

    private static AddressRequest request(String label, String addressLine1) {
        return new AddressRequest(label, "Nguyen An", "+84901234567", addressLine1,
                null, "Ward 1", "District 1", "Ho Chi Minh City", null, "VN");
    }
}
