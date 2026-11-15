package org.example.eshop.commerce.inventory;

import org.example.eshop.commerce.common.CommerceException;
import org.example.eshop.commerce.integration.JdbcInventoryAdapter;
import org.example.eshop.commerce.port.CommerceActor;
import org.example.eshop.commerce.port.CurrentActorPort;
import org.example.eshop.commerce.port.InventoryPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfEnvironmentVariable(named = "TV3_TEST_DB_URL", matches = ".*furniture_shop_test_.*")
@SpringJUnitConfig(InventoryReceiptTest.Config.class)
class InventoryReceiptTest {
    private static final long STAFF = 920001;
    private static final long WAREHOUSE = 920001;
    private static final long VARIANT = 920001;

    @TestConfiguration
    @EnableTransactionManagement
    @Import({InventoryReceiptService.class, JdbcInventoryAdapter.class})
    static class Config {
        @Bean DataSource dataSource() {
            return new DriverManagerDataSource(System.getenv("TV3_TEST_DB_URL"),
                    System.getenv().getOrDefault("DB_USERNAME", "root"), System.getenv("DB_PASSWORD"));
        }
        @Bean JdbcTemplate jdbc(DataSource dataSource) { return new JdbcTemplate(dataSource); }
        @Bean NamedParameterJdbcTemplate namedJdbc(DataSource dataSource) {
            return new NamedParameterJdbcTemplate(dataSource);
        }
        @Bean PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-11-14T09:00:00Z"), ZoneOffset.UTC); }
        @Bean CurrentActorPort actor() {
            return new CurrentActorPort() {
                @Override public CommerceActor currentActor() { return new CommerceActor(STAFF, null, Set.of("STAFF")); }
                @Override public void clearGuestIdentity() {}
            };
        }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired InventoryReceiptService service;
    @Autowired InventoryPort inventory;

    @BeforeEach void fixture() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).startsWith("furniture_shop_test_");
        cleanup();
        jdbc.update("INSERT INTO users(id,username,email,password_hash,full_name) VALUES(920001,'tv4-receipt','tv4-receipt@example.invalid','not-a-login-hash','Warehouse Staff')");
        jdbc.update("INSERT INTO categories(id,name,slug) VALUES(920001,'TV4 Test','tv4-test')");
        jdbc.update("INSERT INTO products(id,category_id,name,slug,status) VALUES(920001,920001,'TV4 Sofa','tv4-sofa','ACTIVE')");
        jdbc.update("INSERT INTO product_variants(id,product_id,sku,variant_key,name,price,status) VALUES(920001,920001,'TV4-SOFA-TEST','default','Default',100.00,'ACTIVE')");
        jdbc.update("INSERT INTO warehouses(id,code,name,is_active) VALUES(920001,'TV4-TEST','TV4 test warehouse',1)");
    }

    @AfterEach void cleanup() {
        if (jdbc == null) return;
        jdbc.update("DELETE FROM inventory_movements WHERE warehouse_id=920001");
        jdbc.update("DELETE FROM inventory_balances WHERE warehouse_id=920001");
        jdbc.update("DELETE FROM warehouses WHERE id=920001");
        jdbc.update("DELETE FROM product_variants WHERE id=920001");
        jdbc.update("DELETE FROM products WHERE id=920001");
        jdbc.update("DELETE FROM categories WHERE id=920001");
        jdbc.update("DELETE FROM users WHERE id=920001");
    }

    private InventoryReceiptRequest request(int quantity) {
        return new InventoryReceiptRequest(WAREHOUSE, VARIANT, quantity, "PN-001", "Nhập từ nhà cung cấp");
    }

    @Test void receiptCreatesBalanceMovementAndReturnsCurrentAvailability() {
        var result = service.receive(request(5), "receipt-key-001");
        assertThat(result.replayed()).isFalse();
        assertThat(result.receivedQuantity()).isEqualTo(5);
        assertThat(result.onHandQuantity()).isEqualTo(5);
        assertThat(result.availableQuantity()).isEqualTo(5);
        assertThat(inventory.availableQuantities(Set.of(VARIANT)).get(VARIANT)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT version FROM inventory_balances WHERE warehouse_id=? AND variant_id=?",
                Long.class, WAREHOUSE, VARIANT)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT performed_by FROM inventory_movements WHERE id=?",
                Long.class, result.movementId())).isEqualTo(STAFF);
        assertThat(service.receipts(WAREHOUSE, 0, 20).items()).hasSize(1);
    }

    @Test void retryWithSameKeyNeverAddsStockAgainAndChangedBodyConflicts() {
        var first = service.receive(request(5), "receipt-key-002");
        var retry = service.receive(request(5), "receipt-key-002");
        assertThat(retry.replayed()).isTrue();
        assertThat(retry.movementId()).isEqualTo(first.movementId());
        assertThatThrownBy(() -> service.receive(request(6), "receipt-key-002"))
                .isInstanceOfSatisfying(CommerceException.class,
                        error -> assertThat(error.getCode()).isEqualTo("IDEMPOTENCY_CONFLICT"));
        assertThat(jdbc.queryForObject("SELECT on_hand_quantity FROM inventory_balances WHERE warehouse_id=? AND variant_id=?",
                Integer.class, WAREHOUSE, VARIANT)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movements WHERE warehouse_id=?",
                Integer.class, WAREHOUSE)).isEqualTo(1);
    }

    @Test void simultaneousRetriesCommitOneReceipt() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var first = executor.submit(() -> { start.await(); return service.receive(request(5), "receipt-key-parallel"); });
            var second = executor.submit(() -> { start.await(); return service.receive(request(5), "receipt-key-parallel"); });
            start.countDown();
            var results = List.of(first.get(20, TimeUnit.SECONDS).replayed(), second.get(20, TimeUnit.SECONDS).replayed());
            assertThat(results).containsExactlyInAnyOrder(false, true);
        } finally { executor.shutdownNow(); }
        assertThat(jdbc.queryForObject("SELECT on_hand_quantity FROM inventory_balances WHERE warehouse_id=? AND variant_id=?",
                Integer.class, WAREHOUSE, VARIANT)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movements WHERE warehouse_id=?",
                Integer.class, WAREHOUSE)).isEqualTo(1);
    }

    @Test void receiptPreservesReservationsAndRejectsInactiveWarehouse() {
        jdbc.update("INSERT INTO inventory_balances(warehouse_id,variant_id,on_hand_quantity,reserved_quantity) VALUES(?,?,10,7)",
                WAREHOUSE, VARIANT);
        var receipt = service.receive(request(5), "receipt-key-003");
        assertThat(receipt.onHandQuantity()).isEqualTo(15);
        assertThat(receipt.reservedQuantity()).isEqualTo(7);
        assertThat(receipt.availableQuantity()).isEqualTo(8);
        assertThat(inventory.availableQuantities(Set.of(VARIANT)).get(VARIANT)).isEqualTo(8);
        jdbc.update("UPDATE warehouses SET is_active=0 WHERE id=?", WAREHOUSE);
        assertThatThrownBy(() -> service.receive(request(1), "receipt-key-004"))
                .isInstanceOfSatisfying(CommerceException.class,
                        error -> assertThat(error.getCode()).isEqualTo("WAREHOUSE_INACTIVE"));
        assertThat(jdbc.queryForObject("SELECT on_hand_quantity FROM inventory_balances WHERE warehouse_id=? AND variant_id=?",
                Integer.class, WAREHOUSE, VARIANT)).isEqualTo(15);
    }

    @Test void overflowAndMissingKeyDoNotWriteMovement() {
        jdbc.update("INSERT INTO inventory_balances(warehouse_id,variant_id,on_hand_quantity) VALUES(?,?,2147483647)",
                WAREHOUSE, VARIANT);
        assertThatThrownBy(() -> service.receive(request(1), "receipt-key-005"))
                .isInstanceOfSatisfying(CommerceException.class,
                        error -> assertThat(error.getCode()).isEqualTo("STOCK_LIMIT"));
        assertThatThrownBy(() -> service.receive(request(1), null))
                .isInstanceOfSatisfying(CommerceException.class,
                        error -> assertThat(error.getCode()).isEqualTo("VALIDATION_ERROR"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movements WHERE warehouse_id=?",
                Integer.class, WAREHOUSE)).isZero();
    }
}
