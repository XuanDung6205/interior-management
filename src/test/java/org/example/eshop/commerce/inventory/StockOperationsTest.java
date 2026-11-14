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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfEnvironmentVariable(named = "TV3_TEST_DB_URL", matches = ".*furniture_shop_test_.*")
@SpringJUnitConfig(StockOperationsTest.Config.class)
class StockOperationsTest {
    private static final long STAFF = 930001;
    private static final long SOURCE = 930001;
    private static final long DESTINATION = 930002;
    private static final long VARIANT = 930001;

    @TestConfiguration
    @EnableTransactionManagement
    @Import({StockOperationsService.class, JdbcInventoryAdapter.class})
    static class Config {
        @Bean DataSource dataSource() {
            return new DriverManagerDataSource(System.getenv("TV3_TEST_DB_URL"),
                    System.getenv().getOrDefault("DB_USERNAME", "root"), System.getenv("DB_PASSWORD"));
        }
        @Bean JdbcTemplate jdbc(DataSource source) { return new JdbcTemplate(source); }
        @Bean NamedParameterJdbcTemplate namedJdbc(DataSource source) { return new NamedParameterJdbcTemplate(source); }
        @Bean PlatformTransactionManager transactionManager(DataSource source) {
            return new DataSourceTransactionManager(source);
        }
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-11-14T10:00:00Z"), ZoneOffset.UTC); }
        @Bean CurrentActorPort actor() {
            return new CurrentActorPort() {
                @Override public CommerceActor currentActor() { return new CommerceActor(STAFF, null, Set.of("STAFF")); }
                @Override public void clearGuestIdentity() {}
            };
        }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired StockOperationsService service;
    @Autowired InventoryPort inventory;

    @BeforeEach void fixture() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).startsWith("furniture_shop_test_");
        cleanup();
        jdbc.update("INSERT INTO users(id,username,email,password_hash,full_name) VALUES(930001,'tv4-stock','tv4-stock@example.invalid','not-a-login-hash','Warehouse Staff')");
        jdbc.update("INSERT INTO categories(id,name,slug) VALUES(930001,'TV4 Stock','tv4-stock')");
        jdbc.update("INSERT INTO products(id,category_id,name,slug,status) VALUES(930001,930001,'TV4 Table','tv4-table','ACTIVE')");
        jdbc.update("INSERT INTO product_variants(id,product_id,sku,variant_key,name,price,status) VALUES(930001,930001,'TV4-TABLE-TEST','default','Default',100.00,'ACTIVE')");
        jdbc.update("INSERT INTO warehouses(id,code,name,is_active) VALUES(930001,'TV4-SOURCE','Source',1),(930002,'TV4-DEST','Destination',1)");
        jdbc.update("INSERT INTO inventory_balances(warehouse_id,variant_id,on_hand_quantity,reserved_quantity) VALUES(930001,930001,10,3)");
    }

    @AfterEach void cleanup() {
        if (jdbc == null) return;
        jdbc.update("DELETE FROM inventory_movements WHERE variant_id=930001");
        jdbc.update("DELETE FROM inventory_balances WHERE variant_id=930001");
        jdbc.update("DELETE FROM warehouses WHERE id IN (930001,930002)");
        jdbc.update("DELETE FROM product_variants WHERE id=930001");
        jdbc.update("DELETE FROM products WHERE id=930001");
        jdbc.update("DELETE FROM categories WHERE id=930001");
        jdbc.update("DELETE FROM users WHERE id=930001");
    }

    private StockAdjustmentRequest adjustment(int delta) {
        return new StockAdjustmentRequest(SOURCE, VARIANT, delta, "DC-001", "Kiểm kê thực tế");
    }

    private StockTransferRequest transfer(int quantity) {
        return new StockTransferRequest(SOURCE, DESTINATION, VARIANT, quantity, "CK-001", "Bổ sung kho đích");
    }

    @Test void adjustmentNeverConsumesReservedUnitsAndWritesOneMovementPerKey() {
        assertThatThrownBy(() -> service.adjust(adjustment(-8), "adjust-key-001"))
                .isInstanceOfSatisfying(CommerceException.class,
                        error -> assertThat(error.getCode()).isEqualTo("STOCK_RESERVED"));
        var first = service.adjust(adjustment(-7), "adjust-key-002");
        assertThat(first.balance().onHandQuantity()).isEqualTo(3);
        assertThat(first.balance().reservedQuantity()).isEqualTo(3);
        assertThat(first.balance().availableQuantity()).isZero();
        assertThat(service.adjust(adjustment(-7), "adjust-key-002").replayed()).isTrue();
        assertThatThrownBy(() -> service.adjust(adjustment(1), "adjust-key-002"))
                .isInstanceOfSatisfying(CommerceException.class,
                        error -> assertThat(error.getCode()).isEqualTo("IDEMPOTENCY_CONFLICT"));
        var second = service.adjust(adjustment(5), "adjust-key-003");
        assertThat(second.balance().availableQuantity()).isEqualTo(5);
        assertThat(inventory.availableQuantities(Set.of(VARIANT)).get(VARIANT)).isEqualTo(5);
        assertThat(service.movements(SOURCE, 0, 20).items()).hasSize(2);
    }

    @Test void transferMovesOnlyAvailableUnitsAndRecordsBothSidesAtomically() {
        var result = service.transfer(transfer(7), "transfer-key-001");
        assertThat(result.source().onHandQuantity()).isEqualTo(3);
        assertThat(result.source().reservedQuantity()).isEqualTo(3);
        assertThat(result.destination().onHandQuantity()).isEqualTo(7);
        assertThat(service.balance(DESTINATION, VARIANT).availableQuantity()).isEqualTo(7);
        assertThat(inventory.availableQuantities(Set.of(VARIANT)).get(VARIANT)).isEqualTo(7);
        assertThat(service.transfer(transfer(7), "transfer-key-001").replayed()).isTrue();
        assertThatThrownBy(() -> service.transfer(transfer(6), "transfer-key-001"))
                .isInstanceOfSatisfying(CommerceException.class,
                        error -> assertThat(error.getCode()).isEqualTo("IDEMPOTENCY_CONFLICT"));
        assertThat(service.movements(null, 0, 20).items()).extracting(StockOperationsService.MovementView::type)
                .containsExactly("TRANSFER_IN", "TRANSFER_OUT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movements WHERE variant_id=?",
                Integer.class, VARIANT)).isEqualTo(2);
    }

    @Test void rejectedTransferRollsBackDestinationRowAndMovements() {
        assertThatThrownBy(() -> service.transfer(transfer(8), "transfer-key-002"))
                .isInstanceOfSatisfying(CommerceException.class,
                        error -> assertThat(error.getCode()).isEqualTo("STOCK_UNAVAILABLE"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_balances WHERE warehouse_id=? AND variant_id=?",
                Integer.class, DESTINATION, VARIANT)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movements WHERE variant_id=?",
                Integer.class, VARIANT)).isZero();
        assertThat(service.balance(SOURCE, VARIANT).onHandQuantity()).isEqualTo(10);
    }

    @Test void transferRequiresTwoActiveWarehouses() {
        assertThatThrownBy(() -> service.transfer(new StockTransferRequest(SOURCE, SOURCE, VARIANT, 1,
                "CK-002", "Sai kho"), "transfer-key-003"))
                .isInstanceOfSatisfying(CommerceException.class,
                        error -> assertThat(error.getCode()).isEqualTo("INVALID_TRANSFER"));
        jdbc.update("UPDATE warehouses SET is_active=0 WHERE id=?", DESTINATION);
        assertThatThrownBy(() -> service.transfer(transfer(1), "transfer-key-004"))
                .isInstanceOfSatisfying(CommerceException.class,
                        error -> assertThat(error.getCode()).isEqualTo("WAREHOUSE_INACTIVE"));
    }
}
